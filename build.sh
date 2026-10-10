#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
compiler=javac
archiver=jar
if [ -n "${JAVA_HOME:-}" ]; then compiler="$JAVA_HOME/bin/javac"; archiver="$JAVA_HOME/bin/jar"; fi
mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
"$compiler" --release 17 --add-modules jdk.httpserver -encoding UTF-8 -d build/classes @build/sources.txt
cp -R src/main/resources/. build/classes/
"$archiver" --create --file AI-Office-Java.jar --main-class tw.jimmy.aioffice.Main -C build/classes .
printf '%s\n' 'Built AI-Office-Java.jar'
