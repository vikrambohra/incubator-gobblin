#!/bin/bash
set -e

echo "Starting $0 at " $(date)
PROJECT_VERSION=$(./gradlew properties -q | grep "version:" | awk '{print $2}')

echo "Project Version: $PROJECT_VERSION"
BUILD_VERSION=$PROJECT_VERSION-dev-${TRAVIS_BUILD_NUMBER}
echo "Build Version: $BUILD_VERSION"

echo "Testing Bintray publication by uploading in dry run mode"
./gradlew bintrayUpload -Pversion=$BUILD_VERSION

echo "Is this a travis pull request build and on which branch??? "
echo "Pull request: [$TRAVIS_PULL_REQUEST], Travis branch: [$TRAVIS_BRANCH]"
# release only from master when no pull request build

if [ "$TRAVIS_BRANCH" = "bintrayPub" ] && [ "$TRAVIS_PULL_REQUEST" = "true" ]
then
    echo "Branch bintrayPub and PR true"
fi

if [ "$TRAVIS_BRANCH" = "bintrayPub" ] && [ "$TRAVIS_PULL_REQUEST" = "false" ]
then
    echo "Branch bintrayPub and PR false"
fi

if [ "$TRAVIS_BRANCH" = "master" ] && [ "$TRAVIS_PULL_REQUEST" = "true" ]
then
    echo "Branch master and PR true"
fi

if [ "$TRAVIS_BRANCH" = "master" ] && [ "$TRAVIS_PULL_REQUEST" = "false" ]
then
    echo "Branch master and PR false"
fi