import sys; sys.path.insert(0, __import__('os').path.dirname(__file__))
from parity import *
import glob, collections
paths = sorted(glob.glob(f'{M}/parity/img/*.jpg')) + [f'{M}/bus.jpg', f'{M}/zidane.jpg']
allc = canvases(paths)
feed = cv2.cvtColor(cv2.imread(f'{M}/gridtest/feed_page.jpg'), cv2.COLOR_BGR2RGB)
allc.append(('feed_page', cv2.resize(feed, (590, int(feed.shape[0] * 590 / feed.shape[1])))[:1280]))
ref = Model(*MODELS['S320 (v1.7)'])
cands = {k: Model(*MODELS[k]) for k in ['P224', 'P256', 'P288', 'P320']}
gt = Model(f'{M}/export/fp16/nudenet_640m_fp16.tflite', 640, 640, threads=8)
# (a) control: P224 fed the canvas at S320's scale (0.25) -> should reproduce S320
agree = tot = 0
for name, c in allc:
    if c.shape[1] > c.shape[0]: continue
    R, _ = detect(ref, c, T)
    small = cv2.resize(c, (int(c.shape[1] * 0.25 / 0.375 + 0.5), int(c.shape[0] * 0.25 / 0.375 + 0.5)))
    C, _ = detect(cands['P224'], small, T - 0.05)
    k = small.shape[1] / c.shape[1]
    for r in R:
        tot += 1
        rr = [v * k for v in r[:4]]
        if any(d[5] == r[5] and iou1(rr, d) >= 0.5 for d in C): agree += 1
print(f'control P224 at v1.7 scale: {agree}/{tot} of v1.7 boxes reproduced')
# (b) recall of 640m@640 boxes (score>=0.4), faces excluded and included
NOFACE = lambda c: c not in (1, 12)
res = collections.defaultdict(lambda: [0, 0, 0, 0])
for name, c in allc:
    kind = 'L' if c.shape[1] > c.shape[0] else 'P'
    G, _ = detect(gt, c, 0.4)
    models = {'S320 (v1.7)': ref, **cands}
    for k, m in models.items():
        D, _ = detect(m, c, T)
        for g in G:
            hit = any(d[5] == g[5] and iou1(g, d) >= 0.5 for d in D)
            st = res[(k, kind)]
            st[0] += 1; st[1] += hit
            if NOFACE(g[5]): st[2] += 1; st[3] += hit
for (k, kind), (n, h, nf, hf) in sorted(res.items(), key=lambda t: (t[0][1], t[0][0])):
    print(f'{kind} {k:12s} recall of 640m boxes: all {h}/{n} = {100*h/max(n,1):5.1f}%   non-face {hf}/{nf} = {100*hf/max(nf,1):5.1f}%')
