#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
runtime=java
if [ -n "${JAVA_HOME:-}" ]; then runtime="$JAVA_HOME/bin/java"; fi
exec "$runtime" -jar AI-Office-Java.jar "$@"
