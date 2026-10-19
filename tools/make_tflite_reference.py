"""Real-model reference: run the bundled LiteRT model on an SFW photo
(Ultralytics' bus.jpg sample) and store its raw output plus NudeNet-style
reference detections at a low threshold. Checks that the app's decode of the
*normalised* LiteRT output equals NudeNet's post-processing of the ONNX
output for the same input.

Run: python tools/make_tflite_reference.py nudenet.py 320n.onnx app/src/main/assets/nudenet_320n.tflite bus.jpg app/src/test/resources
"""
import sys, importlib.util, time
import numpy as np, cv2, onnxruntime as ort
from ai_edge_litert.interpreter import Interpreter
nn_path, onnx_path, tfl_path, img_path, out = sys.argv[1:6]
spec = importlib.util.spec_from_file_location("nn", nn_path); nn = importlib.util.module_from_spec(spec); spec.loader.exec_module(nn)
L = nn.__dict__["__labels"]
bgr = cv2.imread(img_path); H, W = bgr.shape[:2]; side = max(H, W)
pad = cv2.copyMakeBorder(bgr, 0, side - H, 0, side - W, cv2.BORDER_CONSTANT)
x = cv2.dnn.blobFromImage(pad, 1 / 255.0, (320, 320), swapRB=True)  # RGB
sess = ort.InferenceSession(onnx_path)
ref = sess.run(None, {"images": x})[0]
it = Interpreter(model_path=tfl_path, num_threads=4); it.allocate_tensors()
it.set_tensor(it.get_input_details()[0]["index"], x)
for _ in range(3): it.invoke()
t = time.time()
for _ in range(20): it.invoke()
print("LiteRT CPU (this x86 box, 4 threads): %.1f ms" % ((time.time() - t) / 20 * 1000))
y = it.get_tensor(it.get_output_details()[0]["index"])[0]
print("max |onnx - tflite*320| boxes:", np.abs(ref[0, :4] - y[:4] * 320).max(), "scores:", np.abs(ref[0, 4:] - y[4:]).max())
y.astype("<f4").tofile(f"{out}/tflite_bus_output.bin")
# NudeNet post-processing logic with a lower threshold, float boxes, class-agnostic NMS
o = np.transpose(ref[0]); boxes, scores, cls = [], [], []
for r in o:
    s = r[4:]; m = s.max()
    if m >= 0.05:
        cx, cy, w, h = r[:4] * side / 320
        boxes.append([cx - w / 2, cy - h / 2, w, h]); scores.append(float(m)); cls.append(int(s.argmax()))
idx = cv2.dnn.NMSBoxes(boxes, scores, 0.05, 0.45)
with open(f"{out}/tflite_bus_expected.txt", "w") as f:
    f.write(f"frame {W} {H}\n")
    for i in sorted(idx, key=lambda i: -scores[i]):
        bx, by, bw, bh = boxes[i]
        x1, y1 = max(0, bx), max(0, by); x2, y2 = min(W, bx + bw), min(H, by + bh)
        f.write("%d %.6f %.3f %.3f %.3f %.3f\n" % (cls[i], scores[i], x1, y1, x2, y2))
        print(L[cls[i]], round(scores[i], 3), [round(v) for v in (x1, y1, x2, y2)])
