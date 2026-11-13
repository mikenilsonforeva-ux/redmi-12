#!/usr/bin/env bash
# Runs the plain-JVM unit tests (no Android SDK required).
# Usage: tests/run_tests.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -d "$OUT" \
    "$ROOT/src/com/android/fmradio/FmSignalHistory.java" \
    "$ROOT/tests/com/android/fmradio/FmSignalHistoryTest.java"
java -cp "$OUT" com.android.fmradio.FmSignalHistoryTest
