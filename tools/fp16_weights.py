#!/usr/bin/env python3
"""Store NudeNet tflite weights as FP16 (FLOAT_CASTING, weight-only).

Halves the model size. Activations stay FP32 on CPU (weights are dequantized
once at load) and the GPU runs FP16 anyway. On the SFW sample photos the max
score difference vs FP32 is 5e-4 and boxes move < 0.9 input px.
NOT integer quantization (no INT8: no calibration data with the target classes).

Usage: python fp16_weights.py in.tflite out.tflite   (needs ai-edge-quantizer)
"""
import sys
import ai_edge_quantizer as q
from ai_edge_quantizer import qtyping
from ai_edge_quantizer.recipe import AlgorithmName

src, dst = sys.argv[1], sys.argv[2]
qt = q.Quantizer(src)
qt.add_weight_only_config(
    regex=".*", operation_name=qtyping.TFLOperationName.ALL_SUPPORTED, num_bits=16,
    granularity=qtyping.QuantGranularity.TENSORWISE, algorithm_key=AlgorithmName.FLOAT_CASTING,
)
qt.quantize().export_model(dst, overwrite=True)
