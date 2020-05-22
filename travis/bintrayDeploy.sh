#!/bin/bash
set -e

echo "Starting $0 at " $(date)
PROJECT_VERSION=$(./gradlew properties -q | grep "version:" | awk '{print $2}')

echo "Project Version: $PROJECT_VERSION"
BUILD_VERSION=$PROJECT_VERSION-dev-${TRAVIS_BUILD_NUMBER}
echo "Build Version: $BUILD_VERSION"

echo "Pull request: [$TRAVIS_PULL_REQUEST], Travis branch: [$TRAVIS_BRANCH]"
# release only from master when no pull request build
if [ "$TRAVIS_BRANCH" = "master" ] && [ "$TRAVIS_PULL_REQUEST" = "false" ]
then
    echo "Uploading artifacts to bintray for version $BUILD_VERSION"
    ./gradlew -i bintrayUpload -Pversion=$BUILD_VERSION
fi