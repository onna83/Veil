#!/usr/bin/env bash
# Rebuild app/src/main/assets/nudenet_320n.tflite from NudeNet's published weights.
# Tested with: Python 3.13, torch 2.13.0+cpu, ultralytics 8.4.182, litert-torch 0.9.4,
# ai-edge-litert 2.2.0. (ultralytics auto-installs litert-torch; if it pulls a CUDA
# torch, reinstall the matching CPU torch/torchvision afterwards.)
set -euo pipefail
cd "$(dirname "$0")/.."
WORK=${WORK:-/tmp/veil-model}
mkdir -p "$WORK" && cd "$WORK"
curl -fSL -o 320n.pt https://github.com/notAI-tech/NudeNet/releases/download/v3.4-weights/320n.pt
echo "1d25e219d536dcd6994651020d3c7cba642d13990e6eef934ed7a8ba650fb582  320n.pt" | sha256sum -c
python -c "from ultralytics import YOLO; YOLO('320n.pt').export(format='litert', imgsz=320)"
cp 320n.tflite "$OLDPWD/app/src/main/assets/nudenet_320n.tflite"
echo "Done. Input float32 [1,3,320,320] NCHW RGB/255; output [1,22,2100], boxes normalised (/320)."
