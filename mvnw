#!/bin/sh

set -e

BASE_DIR=$(cd "$(dirname "$0")" && pwd)
WRAPPER_DIR="$BASE_DIR/.mvn/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/maven-wrapper.jar"
WRAPPER_PROPS="$WRAPPER_DIR/maven-wrapper.properties"

if [ ! -f "$WRAPPER_PROPS" ]; then
  echo "Maven Wrapper properties not found: $WRAPPER_PROPS" >&2
  exit 1
fi

wrapper_url=$(sed -n 's/^wrapperUrl=//p' "$WRAPPER_PROPS" | head -n 1)

if [ ! -f "$WRAPPER_JAR" ]; then
  if [ -z "$wrapper_url" ]; then
    echo "wrapperUrl is missing from $WRAPPER_PROPS" >&2
    exit 1
  fi

  mkdir -p "$WRAPPER_DIR"
  echo "Downloading Maven Wrapper from $wrapper_url"

  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$wrapper_url" -o "$WRAPPER_JAR"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$wrapper_url" -O "$WRAPPER_JAR"
  else
    echo "Neither curl nor wget is available to download Maven Wrapper." >&2
    exit 1
  fi
fi

exec java -classpath "$WRAPPER_JAR" "-Dmaven.multiModuleProjectDirectory=$BASE_DIR" "-Dmaven.user.home=$BASE_DIR/.m2" org.apache.maven.wrapper.MavenWrapperMain "$@"
