/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.runtime.kafka;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.reflect.ConstructorUtils;

import com.codahale.metrics.Counter;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AbstractIdleService;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.gobblin.instrumented.Instrumented;
import org.apache.gobblin.kafka.client.DecodeableKafkaRecord;
import org.apache.gobblin.kafka.client.GobblinKafkaConsumerClient;
import org.apache.gobblin.kafka.client.KafkaConsumerRecord;
import org.apache.gobblin.metrics.MetricContext;
import org.apache.gobblin.metrics.Tag;
import org.apache.gobblin.runtime.metrics.RuntimeMetrics;
import org.apache.gobblin.source.extractor.extract.kafka.KafkaPartition;
import org.apache.gobblin.util.ConfigUtils;
import org.apache.gobblin.util.ExecutorsUtils;


/**
 * A high level consumer for Kafka topics. Subclasses should implement {@link HighLevelConsumer#processMessage(DecodeableKafkaRecord)}
 *
 * Note: each thread (queue) will block for each message until {@link #processMessage(DecodeableKafkaRecord)} returns
 *
 * If threads(queues) > partitions in topic, extra threads(queues) will be idle.
 *
 */
@Slf4j
public abstract class HighLevelConsumer<K,V> extends AbstractIdleService {

  public static final String CONSUMER_CLIENT_FACTORY_CLASS_KEY = "kafka.consumerClientClassFactory";
  private static final String DEFAULT_CONSUMER_CLIENT_FACTORY_CLASS =
      "org.apache.gobblin.kafka.client.Kafka09ConsumerClient$Factory";
  public static final String ENABLE_AUTO_COMMIT_KEY = "enable.auto.commit";
  public static final boolean DEFAULT_AUTO_COMMIT_VALUE = true;

  public static final String GROUP_ID_KEY = "group.id";
  // NOTE: changing this will break stored offsets
  private static final String DEFAULT_GROUP_ID = "KafkaJobSpecMonitor";
  public static final String OFFSET_COMMIT_NUM_RECORDS_THRESHOLD_KEY = "offsets.commit.num.records.threshold";
  public static final int DEFAULT_OFFSET_COMMIT_NUM_RECORDS_THRESHOLD = 100;
  public static final String OFFSET_COMMIT_TIME_THRESHOLD_SECS_KEY = "offsets.commit.time.threshold.secs";
  public static final int DEFAULT_OFFSET_COMMIT_TIME_THRESHOLD_SECS = 10;

  @Getter
  protected final String topic;
  protected final Config config;
  private final int numThreads;

  /**
   * {@link MetricContext} for the consumer. Note this is instantiated when {@link #startUp()} is called, so
   * {@link com.codahale.metrics.Metric}s used by subclasses should be instantiated in the {@link #createMetrics()} method.
   */
  @Getter
  private MetricContext metricContext;
  private Counter messagesRead;
  @Getter
  private final GobblinKafkaConsumerClient gobblinKafkaConsumerClient;
  private final ExecutorService mainExecutor;
  private final ExecutorService queueExecutor;
  private ExecutorService offsetCommitExecutor;
  private final BlockingQueue[] queues;
  private final AtomicInteger recordsProcessed;
  private final Map<KafkaPartition, Long> partitionOffsetsToCommit;
  private final boolean enableAutoCommit;
  private final int offsetsCommitNumRecordsThreshold;
  private final int offsetsCommitTimeThresholdSecs;

  private static final Config FALLBACK =
      ConfigFactory.parseMap(ImmutableMap.<String, Object>builder()
          .put(GROUP_ID_KEY, DEFAULT_GROUP_ID)
          .put(ENABLE_AUTO_COMMIT_KEY, DEFAULT_AUTO_COMMIT_VALUE)
          .build());

  public HighLevelConsumer(String topic, Config config, int numThreads, Optional<GobblinKafkaConsumerClient> clientOverride) {
    this.topic = topic;
    this.numThreads = numThreads;
    this.config = config.withFallback(FALLBACK);
    this.gobblinKafkaConsumerClient = clientOverride.isPresent() ? clientOverride.get() : createConsumerClient(config);
    this.gobblinKafkaConsumerClient.subscribe(this.topic);
    this.mainExecutor = Executors.newSingleThreadExecutor();
    this.queueExecutor = Executors.newFixedThreadPool(this.numThreads);
    this.queues = new LinkedBlockingQueue[numThreads];
    for(int i=0;i<queues.length;i++) {
      this.queues[i] = new LinkedBlockingQueue();
    }
    this.recordsProcessed = new AtomicInteger(0);
    this.partitionOffsetsToCommit = new ConcurrentHashMap<>();
    this.enableAutoCommit = ConfigUtils.getBoolean(config, ENABLE_AUTO_COMMIT_KEY, DEFAULT_AUTO_COMMIT_VALUE);
    this.offsetsCommitNumRecordsThreshold = ConfigUtils.getInt(config, OFFSET_COMMIT_NUM_RECORDS_THRESHOLD_KEY, DEFAULT_OFFSET_COMMIT_NUM_RECORDS_THRESHOLD);
    this.offsetsCommitTimeThresholdSecs = ConfigUtils.getInt(config, OFFSET_COMMIT_TIME_THRESHOLD_SECS_KEY, DEFAULT_OFFSET_COMMIT_TIME_THRESHOLD_SECS);
  }

  public HighLevelConsumer(String topic, Config config, int numThreads) {
    this(topic, config, numThreads, Optional.absent());
  }

  protected GobblinKafkaConsumerClient createConsumerClient(Config config) {
    String kafkaConsumerClientClass = ConfigUtils.getString(config, CONSUMER_CLIENT_FACTORY_CLASS_KEY,
        DEFAULT_CONSUMER_CLIENT_FACTORY_CLASS);

    try {
      Class clientFactoryClass = Class.forName(kafkaConsumerClientClass);
      final GobblinKafkaConsumerClient.GobblinKafkaConsumerClientFactory factory =
          (GobblinKafkaConsumerClient.GobblinKafkaConsumerClientFactory)
              ConstructorUtils.invokeConstructor(clientFactoryClass);

      return factory.create(config);
    } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InstantiationException | InvocationTargetException e) {
      throw new RuntimeException("Failed to instantiate Kafka consumer client " + kafkaConsumerClientClass, e);
    }
  }

  /**
   * Called once on {@link #startUp()} to start metrics.
   */
  @VisibleForTesting
  protected void buildMetricsContextAndMetrics() {
    this.metricContext = Instrumented.getMetricContext(new org.apache.gobblin.configuration.State(ConfigUtils.configToProperties(config)),
        this.getClass(), getTagsForMetrics());
    createMetrics();
  }

  @VisibleForTesting
  protected void shutdownMetrics() throws IOException {
    if (this.metricContext != null) {
      this.metricContext.close();
    }
  }

  /**
   * Instantiates {@link com.codahale.metrics.Metric}s. Called once in {@link #startUp()}. Subclasses should override
   * this method to instantiate their own metrics.
   */
  protected void createMetrics() {
    this.messagesRead = this.metricContext.counter(RuntimeMetrics.GOBBLIN_KAFKA_HIGH_LEVEL_CONSUMER_MESSAGES_READ);
  }

  /**
   * @return Tags to be applied to the {@link MetricContext} in this object. Called once in {@link #startUp()}.
   * Subclasses should override this method to add additional tags.
   */
  protected List<Tag<?>> getTagsForMetrics() {
    List<Tag<?>> tags = Lists.newArrayList();
    tags.add(new Tag<>(RuntimeMetrics.TOPIC, this.topic));
    tags.add(new Tag<>(RuntimeMetrics.GROUP_ID, ConfigUtils.getString(this.config, GROUP_ID_KEY, DEFAULT_GROUP_ID)));
    return tags;
  }

  /**
   * Called every time a message is read from the queue. Implementation must be thread-safe if {@link #numThreads} is
   * set larger than 1.
   */
  protected abstract void processMessage(DecodeableKafkaRecord<K,V> message);

  @Override
  protected void startUp() {
    buildMetricsContextAndMetrics();
    processQueues();
    if(!enableAutoCommit) {
      offsetCommitExecutor = Executors.newSingleThreadExecutor();
      offsetCommitExecutor.execute(() -> startOffsetCommitter());
    }
    mainExecutor.execute(() -> startConsume());
  }

  /**
   * Consumes {@link KafkaConsumerRecord}s and adds to a queue
   * Note: All records from a KafkaPartition are added to the same queue.
   * A queue can contain records from multiple partitions if partitions > numThreads(queues)
   */
  private void startConsume() {
    log.info("Starting consumption.. " + Thread.currentThread().getName());
    try {
      while (true) {
        Iterator<KafkaConsumerRecord> itr = this.gobblinKafkaConsumerClient.consume();
        while (itr.hasNext()) {
          KafkaConsumerRecord record = itr.next();
          int idx = record.getPartition() % numThreads;
          queues[idx].put(record);
        }

        Thread.sleep(50);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void processQueues() {
    for(BlockingQueue queue : queues) {
      queueExecutor.execute(new QueueProcessor(queue));
    }
  }

  /**
   * A thread that commits offsets to kafka at regular intervals
   */
  private void startOffsetCommitter() {
    log.info("Starting offset committer.. ");
    long startTime = System.currentTimeMillis();
    while(true) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }

      if(recordsProcessed.intValue() >= offsetsCommitNumRecordsThreshold || ((System.currentTimeMillis() - startTime) / 1000 >= offsetsCommitTimeThresholdSecs)) {
        Map<KafkaPartition, Long> copy = new HashMap<>(partitionOffsetsToCommit);
        partitionOffsetsToCommit.clear();
        recordsProcessed.set(0);
        startTime = System.currentTimeMillis();
        gobblinKafkaConsumerClient.commitOffsets(copy);
      }
    }
  }

  @Override
  public void shutDown() {
    ExecutorsUtils.shutdownExecutorService(this.mainExecutor, Optional.of(log), 5000, TimeUnit.MILLISECONDS);
    ExecutorsUtils.shutdownExecutorService(this.queueExecutor, Optional.of(log), 5000, TimeUnit.MILLISECONDS);
    if(this.offsetCommitExecutor != null) {
      ExecutorsUtils.shutdownExecutorService(this.offsetCommitExecutor, Optional.of(log), 5000, TimeUnit.MILLISECONDS);
    }
    try {
      this.gobblinKafkaConsumerClient.close();
      this.shutdownMetrics();
    } catch (IOException e) {
      log.warn("Failed to shut down consumer client or metrics ", e);
    }
  }

  /**
   * Polls a {@link BlockingQueue} indefinitely for {@link KafkaConsumerRecord}
   * Processes each record and maintains a count for #recordsProcessed
   * Also records the latest offset for every {@link KafkaPartition} if auto commit is disabled
   * Note: Messages in this queue will always be in order for every {@link KafkaPartition}
   */
  class QueueProcessor implements Runnable {
    private final BlockingQueue<KafkaConsumerRecord> queue;

    public QueueProcessor(BlockingQueue queue) {
      this.queue = queue;
    }

    @Override
    public void run() {
      log.info("Starting queue processing.. " + Thread.currentThread().getName());
      try {
        while(true) {
          KafkaConsumerRecord record = queue.take();
          messagesRead.inc();
          processMessage((DecodeableKafkaRecord)record);
          recordsProcessed.incrementAndGet();

          if(!HighLevelConsumer.this.enableAutoCommit) {
            KafkaPartition partition = new KafkaPartition.Builder().withId(record.getPartition()).withTopicName(HighLevelConsumer.this.topic).build();
            partitionOffsetsToCommit.put(partition, record.getOffset());
          }
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
