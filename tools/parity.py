"""v1.8 recall parity: square 320 letterbox (v1.7) vs portrait models (fit / fold).
SFW images only (COCO val people photos, ultralytics samples, a synthetic feed page).
Each image is placed on phone-like canvases (590x1280 portrait; 1280x590 landscape)
exactly as Veil captures a screen, then every model sees the canvas through its layout.
Reference = square 320 detections (score >= T). A reference box counts as recalled
when the candidate has the same class at IoU >= 0.5 (candidate score >= T - 0.05)."""
import glob, json, sys, time, numpy as np, cv2
from ai_edge_litert.interpreter import Interpreter

T = 0.30
M = '/workspace/models'
MODELS = {
    'S320 (v1.7)': (f'{M}/export/fp16/nudenet_320n_fp16.tflite', 320, 320),
    'P224': (f'{M}/portrait/nudenet_320n_p224x480_fp16.tflite', 224, 480),
    'P256': (f'{M}/portrait/nudenet_320n_p256x544_fp16.tflite', 256, 544),
    'P288': (f'{M}/portrait/nudenet_320n_p288x608_fp16.tflite', 288, 608),
    'P320': (f'{M}/portrait/nudenet_320n_p320x704_fp16.tflite', 320, 704),
}

def fit_parts(w, h, ow, oh):
    inv = max(w / ow, h / oh)
    return [(0, 0, w, h, 0, 0, inv)]

def fold_parts(w, h, ow, oh, ov=0.06):
    half = min(w, (w + 1) // 2 + int(ov * w))
    inv = max(half / ow, 2.0 * h / oh)
    dh = int(round(h / inv))
    return [(0, 0, half, h, 0, 0, inv), (w - half, 0, half, h, 0, min(dh, oh), inv)]

def choose(w, h, ow, oh):
    f = fit_parts(w, h, ow, oh)
    if w <= h or oh < ow * 3 // 2: return f
    g = fold_parts(w, h, ow, oh)
    return g if 1 / g[0][6] >= 1.25 / f[0][6] else f

def render(img, parts, ow, oh):
    canvas = np.zeros((oh, ow, 3), np.float32)
    for sx, sy, sw, sh, dx, dy, inv in parts:
        dw = min(ow - dx, int(round(sw / inv))); dh = min(oh - dy, int(round(sh / inv)))
        if dw <= 0 or dh <= 0: continue
        crop = img[sy:sy + sh, sx:sx + sw]
        r = cv2.resize(crop, (int(round(sw / inv)), int(round(sh / inv))), interpolation=cv2.INTER_LINEAR)
        canvas[dy:dy + dh, dx:dx + dw] = r[:dh, :dw]
    return canvas

class Model:
    def __init__(self, path, w, h, threads=4):
        self.it = Interpreter(model_path=path, num_threads=threads); self.it.allocate_tensors()
        self.inp = self.it.get_input_details()[0]; self.out = self.it.get_output_details()[0]
        self.w, self.h = w, h
        self.nchw = list(self.inp['shape'][1:2]) == [3]
    def run(self, x):
        x = x[None]
        if self.nchw: x = x.transpose(0, 3, 1, 2)
        self.it.set_tensor(self.inp['index'], np.ascontiguousarray(x, np.float32)); self.it.invoke()
        return self.it.get_tensor(self.out['index'])[0]

def nms(b, s, c, iou=0.45):
    order = np.argsort(-s); keep = []
    for i in order:
        ok = True
        for j in keep:
            if c[i] == c[j] and iou1(b[i], b[j]) > iou: ok = False; break
        if ok: keep.append(i)
    return keep

def iou1(a, b):
    iw = max(0, min(a[2], b[2]) - max(a[0], b[0])); ih = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    i = iw * ih; u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i
    return i / u if u > 0 else 0

def detect(model, rgb, thr):
    H, W = rgb.shape[:2]
    parts = choose(W, H, model.w, model.h)
    x = render(rgb.astype(np.float32) / 255.0, parts, model.w, model.h)
    o = model.run(x)  # [22, N], boxes normalised per axis
    sc = o[4:].max(0); cl = o[4:].argmax(0); m = sc >= thr
    cx, cy, bw, bh = o[0][m] * model.w, o[1][m] * model.h, o[2][m] * model.w, o[3][m] * model.h
    b = np.stack([cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2], 1); s = sc[m]; c = cl[m]
    keep = nms(b, s, c)
    out = []
    for i in keep:
        x1, y1, x2, y2 = b[i]; mx, my = (x1 + x2) / 2, (y1 + y2) / 2
        k = 0
        for j, p in enumerate(parts):
            if p[5] <= my < p[5] + p[3] / p[6] + 1: k = j
        sx, sy, sw, sh, dx, dy, inv = parts[k]
        y1 = max(y1, dy); y2 = min(y2, dy + sh / inv)
        X1 = np.clip(sx + (x1 - dx) * inv, sx, sx + sw); X2 = np.clip(sx + (x2 - dx) * inv, sx, sx + sw)
        Y1 = np.clip(sy + (y1 - dy) * inv, sy, sy + sh); Y2 = np.clip(sy + (y2 - dy) * inv, sy, sy + sh)
        if X2 - X1 >= 1 and Y2 - Y1 >= 1: out.append((X1, Y1, X2, Y2, float(s[i]), int(c[i]), k))
    if len(parts) > 1:  # seam merge
        out.sort(key=lambda d: -d[4]); merged = []
        for d in out:
            for i, e in enumerate(merged):
                if e[5] == d[5] and e[6] != d[6]:
                    iw = min(e[2], d[2]) - max(e[0], d[0]); ih = min(e[3], d[3]) - max(e[1], d[1])
                    if iw > 0 and ih > 0 and iw * ih >= 0.5 * min((e[2]-e[0])*(e[3]-e[1]), (d[2]-d[0])*(d[3]-d[1])):
                        merged[i] = (min(e[0], d[0]), min(e[1], d[1]), max(e[2], d[2]), max(e[3], d[3]), e[4], e[5], e[6]); break
            else: merged.append(d)
        out = merged
    return out, parts

def canvases(paths):
    """phone screens: portrait 590x1280 (photo at full width, two vertical positions; a
    two-photo feed) and landscape 1280x590 (photo letterboxed, as a video player)."""
    imgs = [cv2.cvtColor(cv2.imread(p), cv2.COLOR_BGR2RGB) for p in paths]
    out = []
    for n, im in enumerate(imgs):
        h, w = im.shape[:2]
        for top in (120, 520):
            c = np.full((1280, 590, 3), 245, np.uint8)
            r = cv2.resize(im, (590, int(h * 590 / w)))
            hh = min(r.shape[0], 1280 - top); c[top:top + hh] = r[:hh]
            out.append((f'{n}:P@{top}', c))
        c = np.zeros((590, 1280, 3), np.uint8)
        s = min(1280 / w, 590 / h); r = cv2.resize(im, (int(w * s), int(h * s)))
        ox = (1280 - r.shape[1]) // 2; oy = (590 - r.shape[0]) // 2; c[oy:oy + r.shape[0], ox:ox + r.shape[1]] = r
        out.append((f'{n}:L', c))
    for n in range(0, len(imgs) - 1, 2):  # feed page: two posts stacked
        c = np.full((1280, 590, 3), 250, np.uint8); y = 60
        for im in imgs[n:n + 2]:
            h, w = im.shape[:2]; r = cv2.resize(im, (590, int(h * 590 / w))); hh = min(r.shape[0], 1280 - y)
            if hh <= 0: break
            c[y:y + hh] = r[:hh]; y += hh + 80
        out.append((f'{n}:F', c))
    return out

def main():
    paths = sorted(glob.glob(f'{M}/parity/img/*.jpg')) + [f'{M}/bus.jpg', f'{M}/zidane.jpg']
    cs = canvases(paths)
    feed = cv2.cvtColor(cv2.imread(f'{M}/gridtest/feed_page.jpg'), cv2.COLOR_BGR2RGB)
    cs.append(('feed_page', cv2.resize(feed, (590, int(feed.shape[0] * 590 / feed.shape[1])))[:1280]))
    models = {k: Model(*v) for k, v in MODELS.items()}
    ref_name = 'S320 (v1.7)'
    stats = {k: dict(P=[0, 0, 0, 0], L=[0, 0, 0, 0]) for k in models}  # ref, recalled, cand, cand-only
    for name, c in cs:
        kind = 'L' if c.shape[1] > c.shape[0] else 'P'
        ref, _ = detect(models[ref_name], c, T)
        for k, m in models.items():
            cand, parts = detect(m, c, T - 0.05)
            st = stats[k][kind]
            st[0] += len(ref)
            for r in ref:
                if any(d[5] == r[5] and iou1(r, d) >= 0.5 for d in cand): st[1] += 1
            strong = [d for d in cand if d[4] >= T]
            st[2] += len(strong)
            st[3] += sum(1 for d in strong if not any(r[5] == d[5] and iou1(r, d) >= 0.5 for r in ref))
    res = {}
    print(f'{len(cs)} canvases from {len(paths)} SFW images; threshold {T}')
    for k in models:
        for kind in 'PL':
            r, rec, n, only = stats[k][kind]
            res[f'{k} {kind}'] = dict(ref=r, recalled=rec, recall=rec / max(r, 1), dets=n, new=only)
            print(f'{k:12s} {"portrait " if kind=="P" else "landscape"}: recall of v1.7 boxes {rec}/{r} = {100*rec/max(r,1):5.1f}%  '
                  f'detections {n}  not found by v1.7 {only}')
    # layouts on the S23 FE capture
    for k, m in models.items():
        for W, H in ((590, 1280), (1280, 590)):
            p = choose(W, H, m.w, m.h)
            print(f'layout {k:12s} {W}x{H}: {"fold" if len(p) > 1 else "fit "} scale {1/p[0][6]:.3f}')
    json.dump(res, open(f'{M}/parity/parity.json', 'w'), indent=1)

if __name__ == '__main__':
    main()
