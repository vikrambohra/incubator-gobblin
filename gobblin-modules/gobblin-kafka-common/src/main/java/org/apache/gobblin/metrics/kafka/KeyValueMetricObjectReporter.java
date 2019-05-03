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

package org.apache.gobblin.metrics.kafka;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.apache.avro.generic.GenericRecord;
import org.apache.commons.lang3.tuple.Pair;

import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import com.typesafe.config.Config;

import lombok.extern.slf4j.Slf4j;

import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.metrics.MetricReport;
import org.apache.gobblin.metrics.reporter.MetricReportReporter;
import org.apache.gobblin.util.AvroUtils;
import org.apache.gobblin.util.ConfigUtils;


@Slf4j
public class KeyValueMetricObjectReporter extends MetricReportReporter {

  private static final String PUSHER_CONFIG = "pusherConfig";
  private static final String PUSHER_CLASS = "pusherClass";
  private static final String PUSHER_KEYS = "pusherKeys";

  private Optional<List<String>> keys = Optional.absent();
  protected final String randomKey;
  protected KeyValuePusher pusher;
  private Optional<Map<String, String>> namespaceOverride;
  protected final String topic;

  public KeyValueMetricObjectReporter(Builder<?> builder, Config config) {
    super(builder, config);

    this.topic = builder.topic;
    this.namespaceOverride = builder.namespaceOverride;

    Config pusherConfig = ConfigUtils.getConfigOrEmpty(config, PUSHER_CONFIG).withFallback(config);
    String pusherClassName =
        ConfigUtils.getString(config, PUSHER_CLASS, PusherUtils.DEFAULT_KEY_VALUE_PUSHER_CLASS_NAME);
    this.pusher =
        PusherUtils.getKeyValuePusher(pusherClassName, builder.brokers, builder.topic, Optional.of(pusherConfig));
    this.closer.register(this.pusher);

    randomKey = String.valueOf(new Random().nextInt(100));
    if (config.hasPath(PUSHER_KEYS)) {
      List<String> keys = Splitter.on(",").omitEmptyStrings().trimResults().splitToList(config.getString(PUSHER_KEYS));
      this.keys = Optional.of(keys);
    } else {
      log.warn("Key not assigned from config. Please set it with property {}",
          ConfigurationKeys.METRICS_REPORTING_KAFKAPUSHERKEYS);
      log.warn("Using generated number " + randomKey + " as key");
    }
  }

  @Override
  protected void emitReport(MetricReport report) {
    GenericRecord record = AvroUtils.overrideNameAndNamespace(report, this.topic, this.namespaceOverride);
    this.pusher.pushKeyValueMessages(Lists.newArrayList(Pair.of(buildKey(report), record)));
  }

  private String buildKey(MetricReport report) {

    String key = randomKey;
    if (this.keys.isPresent()) {

      StringBuilder keyBuilder = new StringBuilder();
      for (String keyPart : keys.get()) {
        Optional value = AvroUtils.getFieldValue(report, keyPart);
        if (value.isPresent()) {
          keyBuilder.append(value.get().toString());
        } else {
          log.error("{} not found in the MetricReport. Setting key to {}", keyPart, key);
          keyBuilder = null;
          break;
        }
      }

      key = (keyBuilder == null) ? key : keyBuilder.toString();
    }

    return key;
  }

  public static abstract class Builder<T extends Builder<T>> extends MetricReportReporter.Builder<T> {
    protected String brokers;
    protected String topic;
    protected Optional<Map<String, String>> namespaceOverride = Optional.absent();

    public T namespaceOverride(Optional<Map<String, String>> namespaceOverride) {
      this.namespaceOverride = namespaceOverride;
      return self();
    }

    public KeyValueMetricObjectReporter build(String brokers, String topic, Config config)
        throws IOException {
      this.brokers = brokers;
      this.topic = topic;
      return new KeyValueMetricObjectReporter(this, config);
    }
  }

  public static class BuilderImpl extends Builder<BuilderImpl> {

    @Override
    protected BuilderImpl self() {
      return this;
    }
  }

  public static class Factory {

    public static BuilderImpl newBuilder() {
      return new BuilderImpl();
    }
  }
}
