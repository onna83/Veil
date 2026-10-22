import time, numpy as np, sys
sys.path.insert(0, __import__('os').path.dirname(__file__))
from parity import MODELS, Model
for th in (4, 1):
    for k, (p, w, h) in MODELS.items():
        m = Model(p, w, h, threads=th); x = np.random.rand(h, w, 3).astype(np.float32)
        for _ in range(5): m.run(x)
        t = []
        for _ in range(30):
            t0 = time.perf_counter(); m.run(x); t.append((time.perf_counter() - t0) * 1000)
        t.sort(); print(f'{th} thr {k:12s} {w}x{h} px {w*h:6d} ({w*h/102400:.2f}x) median {t[15]:6.1f} ms p90 {t[27]:6.1f}')
