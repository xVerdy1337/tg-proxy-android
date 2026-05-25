#!/bin/bash

# Simple gradlew wrapper script
# Downloads Gradle if not present

GRADLE_VERSION="8.4"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
GRADLE_DIR="$GRADLE_USER_HOME/wrapper/dists/gradle-$GRADLE_VERSION-bin"
GRADLE_ZIP="$GRADLE_DIR/gradle-$GRADLE_VERSION-bin.zip"
GRADLE_HOME="$GRADLE_DIR/gradle-$GRADLE_VERSION"

if [ ! -d "$GRADLE_HOME" ]; then
    echo "Downloading Gradle $GRADLE_VERSION..."
    mkdir -p "$GRADLE_DIR"
    curl -L "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$GRADLE_ZIP"
    unzip -q "$GRADLE_ZIP" -d "$GRADLE_DIR"
    mv "$GRADLE_DIR/gradle-$GRADLE_VERSION" "$GRADLE_HOME"
fi

exec "$GRADLE_HOME/bin/gradle" "$@"
