#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/sarah_pure_java_tests"
rm -rf "$TMP"
mkdir -p "$TMP/src/com/kiraworld/sarahtravel" "$TMP/out"
cp "$ROOT/android-app/app/src/main/java/com/kiraworld/sarahtravel/MemoryExtractor.java" "$TMP/src/com/kiraworld/sarahtravel/"
cp "$ROOT/android-app/app/src/main/java/com/kiraworld/sarahtravel/SarahPromptBuilder.java" "$TMP/src/com/kiraworld/sarahtravel/"
cp "$ROOT/android-app/app/src/main/java/com/kiraworld/sarahtravel/DemoSarah.java" "$TMP/src/com/kiraworld/sarahtravel/"
cp "$ROOT/android-app/app/src/main/java/com/kiraworld/sarahtravel/CalmSupport.java" "$TMP/src/com/kiraworld/sarahtravel/"
cp "$ROOT/android-app/app/src/main/java/com/kiraworld/sarahtravel/MediaSuggestionEngine.java" "$TMP/src/com/kiraworld/sarahtravel/"
cp "$ROOT/tests/PureCoreTest.java" "$TMP/src/com/kiraworld/sarahtravel/"
javac -d "$TMP/out" $(find "$TMP/src" -name '*.java')
java -cp "$TMP/out" com.kiraworld.sarahtravel.PureCoreTest
