"""Generate reference data for the JVM unit tests.

Uses NudeNet's own pre/post-processing (nudenet/nudenet.py, v3 branch,
copied next to this script at runtime) as ground truth:
  * preprocess_*.bin : synthetic RGBA frame and NudeNet's input blob
  * decode_case*.bin/.txt : synthetic raw head output and reference detections
  * labels.txt       : NudeNet's label order

Run: python tools/make_reference.py /path/to/nudenet.py app/src/test/resources
No real images are used (synthetic gradients/shapes and random tensors only).
"""
import importlib.util, json, sys, struct
import numpy as np
import cv2

nn_path, out_dir = sys.argv[1], sys.argv[2]
spec = importlib.util.spec_from_file_location("nudenet_ref", nn_path)
nn = importlib.util.module_from_spec(spec)
spec.loader.exec_module(nn)
LABELS = nn.__dict__["__labels"]
rng = np.random.default_rng(1234)

# ---------- preprocessing ----------
W, H = 270, 600
yy, xx = np.mgrid[0:H, 0:W]
img = np.zeros((H, W, 3), np.uint8)
img[..., 0] = (xx * 255 // (W - 1)).astype(np.uint8)            # R
img[..., 1] = (yy * 255 // (H - 1)).astype(np.uint8)            # G
img[..., 2] = ((xx + yy) % 64 * 4).astype(np.uint8)             # B
cv2.circle(img, (130, 300), 70, (250, 40, 90), -1)
cv2.rectangle(img, (20, 450), (200, 560), (10, 200, 30), -1)
img[::7, :, :] = 255 - img[::7, :, :]                           # fine detail
# NOTE on channel order: nudenet.py applies cv2.COLOR_RGBA2BGR and then
# blobFromImage(swapRB=True). For a 4-channel RGBA array (our case: Android
# RGBA_8888) that yields RGB, the order Ultralytics trained with. (For a
# 3-channel cv2.imread() BGR file the same code swaps twice and feeds BGR, a
# quirk we deliberately do not copy.)
rgba_img = np.concatenate([img, np.full((H, W, 1), 255, np.uint8)], 2)
blob, *_ = nn._read_image(rgba_img, 320)                         # [1,3,320,320] RGB/255
assert np.allclose(blob, cv2.dnn.blobFromImage(cv2.copyMakeBorder(img, 0, 0, 0, H - W, cv2.BORDER_CONSTANT), 1/255., (320, 320), swapRB=False))
stride = W * 4 + 64                                             # padded rows like ImageReader
rgba = np.zeros((H, stride), np.uint8)
rgba[:, : W * 4] = np.concatenate([img, np.full((H, W, 1), 255, np.uint8)], 2).reshape(H, W * 4)
with open(f"{out_dir}/preprocess_input.bin", "wb") as f:
    f.write(struct.pack("<iii", W, H, stride))
    f.write(rgba.tobytes())
blob.astype("<f4").tofile(f"{out_dir}/preprocess_expected.bin")

# ---------- decode ----------
N, C = 2100, 18
def make_raw():
    raw = np.zeros((4 + C, N), np.float32)
    raw[4:] = rng.uniform(0, 0.15, (C, N))                     # background noise
    raw[0] = rng.uniform(0, 320, N); raw[1] = rng.uniform(0, 320, N)
    raw[2] = rng.uniform(2, 60, N); raw[3] = rng.uniform(2, 60, N)
    # planted objects (inside the image area of a 540x1200 frame -> x < 144)
    planted = []
    for k in range(14):
        cx, cy = rng.uniform(25, 120), rng.uniform(30, 290)
        w, h = rng.uniform(10, 40), rng.uniform(10, 40)
        cls = int(rng.integers(0, C))
        for d in range(int(rng.integers(1, 4))):               # duplicates for NMS
            i = int(rng.integers(0, N))
            raw[0, i] = cx + rng.uniform(-2, 2); raw[1, i] = cy + rng.uniform(-2, 2)
            raw[2, i] = w * rng.uniform(0.9, 1.1); raw[3, i] = h * rng.uniform(0.9, 1.1)
            raw[4:, i] = rng.uniform(0, 0.1, C)
            raw[4 + cls, i] = rng.uniform(0.21, 0.95)
            if k % 3 == 0:                                      # second-best class
                raw[4 + (cls + 5) % C, i] = rng.uniform(0.2, 0.4)
    return raw

def my_reference(raw, mask, thr, class_aware, iou_thr, side, maxx, maxy):
    """Independent numpy implementation of YoloDecoder semantics."""
    sc = raw[4:].copy(); sc[~mask] = -1
    best = sc.max(0); bc = sc.argmax(0)
    keep = best >= thr
    s = side / 320.0
    cx, cy, w, h = raw[0] * s, raw[1] * s, raw[2] * s, raw[3] * s
    x1 = np.clip(cx - w / 2, 0, maxx); y1 = np.clip(cy - h / 2, 0, maxy)
    x2 = np.clip(cx + w / 2, 0, maxx); y2 = np.clip(cy + h / 2, 0, maxy)
    idx = [i for i in np.where(keep)[0] if x2[i] > x1[i] and y2[i] > y1[i]]
    idx.sort(key=lambda i: -best[i])
    out = []
    alive = {i: True for i in idx}
    for i in idx:
        if not alive[i]: continue
        alive[i] = False
        out.append(dict(x1=float(x1[i]), y1=float(y1[i]), x2=float(x2[i]), y2=float(y2[i]), score=float(best[i]), cls=int(bc[i])))
        for j in idx:
            if not alive[j]: continue
            if class_aware and bc[j] != bc[i]: continue
            iw = min(x2[i], x2[j]) - max(x1[i], x1[j]); ih = min(y2[i], y2[j]) - max(y1[i], y1[j])
            if iw <= 0 or ih <= 0: continue
            inter = iw * ih
            u = (x2[i]-x1[i])*(y2[i]-y1[i]) + (x2[j]-x1[j])*(y2[j]-y1[j]) - inter
            if inter / u > iou_thr: alive[j] = False
    return out

FW, FH = 540, 1200
censored = {"FEMALE_BREAST_EXPOSED", "FEMALE_GENITALIA_EXPOSED", "MALE_GENITALIA_EXPOSED", "BUTTOCKS_EXPOSED", "ANUS_EXPOSED"}
cmask = np.array([l in censored for l in LABELS])
cases = []
for t in range(4):
    raw = make_raw()
    # NudeNet reference: argmax over all classes, score>=0.2 then NMSBoxes(0.25, 0.45), class-agnostic
    nref = nn._postprocess([raw[None]], FW and max(FW, FH) - FW, max(FW, FH) - FH, 0, 0, FW, FH, 320, 320)
    nref = [dict(cls=LABELS.index(d["class"]), score=d["score"], box=d["box"]) for d in nref]
    mine_all = my_reference(raw, np.ones(C, bool), 0.25, False, 0.45, max(FW, FH), FW, FH)
    mine_cens = my_reference(raw, cmask, 0.2, True, 0.45, max(FW, FH), FW, FH)
    raw.astype("<f4").tofile(f"{out_dir}/decode_case{t}.bin")
    with open(f"{out_dir}/decode_case{t}.txt", "w") as f:
        f.write(f"frame {FW} {FH}\n")
        for d in nref:
            f.write("nudenet %d %.6f %d %d %d %d\n" % (d["cls"], d["score"], *d["box"]))
        for tag, lst in (("agnostic", mine_all), ("censored", mine_cens)):
            for d in lst:
                f.write("%s %d %.6f %.4f %.4f %.4f %.4f\n" % (tag, d["cls"], d["score"], d["x1"], d["y1"], d["x2"], d["y2"]))
    print(f"case {t}: nudenet {len(nref)} dets, agnostic {len(mine_all)}, censored/aware {len(mine_cens)}")
with open(f"{out_dir}/labels.txt", "w") as f:
    f.write("\n".join(LABELS) + "\n")
print("labels", LABELS)
