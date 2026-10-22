#!/usr/bin/env bash
# Veil v1.8.1 synthetic jitter replay: runs the REAL tracker / box-shaping /
# rounding code (JVM unit test JitterSim) on simulated S23 FE timelines and
# writes tools/jitter_report.md (v1.8 = "Off" vs v1.8.1 Normal / Strong).
# No images are used; the "content" is a synthetic rectangle.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew --no-daemon testClassicDebugUnitTest --tests '*JitterSimTest*' "$@"
cp app/build/jitter/report.md tools/jitter_report.md
echo "wrote tools/jitter_report.md"
