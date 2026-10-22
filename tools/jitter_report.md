# Veil jitter replay (tools/jitter_sim.sh)

Screen px (1080 x 2340), 120 Hz, 12 seeds per scenario. jitter = frame-to-frame box-centre displacement error vs the content (0 = moves exactly with it); size = frame-to-frame width/height change; lag = box behind the content at the glass (negative = ahead); leak = vsyncs where the object is not fully covered; block shimmer = frame-to-frame shift of the pixel grid relative to the content; moves/s = vsyncs on which the box changed at all.

## stationary (video playing, nothing moves)

| mode | jitter RMS | jitter p95 | jitter max | size RMS | lag px | lag ms | overshoot px | leak % | block shimmer RMS | block-count changes/s | moves/s |
|---|---|---|---|---|---|---|---|---|---|---|---|
| v1.8 (Off) | 5.43 | 8.00 | 78.0 | 7.75 | 0.0 | 0.0 | 0.0 | 0.83 | 0.84 | 7.12 | 16.0 |
| v1.8.1 Normal | 3.04 | 0.00 | 56.3 | 4.80 | 0.0 | 0.0 | 0.0 | 0.16 | 0.54 | 3.10 | 5.9 |
| v1.8.1 Strong | 2.37 | 0.00 | 56.3 | 4.80 | 0.0 | 0.0 | 0.0 | 0.07 | 0.51 | 2.18 | 4.6 |

## slow scroll 0.15 px/ms (~275 screen px/s)

| mode | jitter RMS | jitter p95 | jitter max | size RMS | lag px | lag ms | overshoot px | leak % | block shimmer RMS | block-count changes/s | moves/s |
|---|---|---|---|---|---|---|---|---|---|---|---|
| v1.8 (Off) | 5.06 | 8.50 | 71.3 | 7.45 | -3.1 | -11.3 | 0.0 | 0.61 | 0.78 | 7.47 | 113.2 |
| v1.8.1 Normal | 3.52 | 3.26 | 63.9 | 5.44 | -3.7 | -13.4 | 0.0 | 0.36 | 0.63 | 3.48 | 112.7 |
| v1.8.1 Strong | 2.85 | 2.71 | 63.9 | 5.57 | -4.0 | -14.6 | 0.0 | 0.04 | 0.60 | 3.05 | 113.2 |

## steady scroll 0.8 px/ms (~1460 screen px/s)

| mode | jitter RMS | jitter p95 | jitter max | size RMS | lag px | lag ms | overshoot px | leak % | block shimmer RMS | block-count changes/s | moves/s |
|---|---|---|---|---|---|---|---|---|---|---|---|
| v1.8 (Off) | 7.90 | 12.31 | 83.0 | 7.70 | -17.5 | -12.0 | 0.0 | 0.76 | 0.80 | 10.30 | 108.9 |
| v1.8.1 Normal | 6.67 | 12.19 | 68.3 | 5.56 | -19.0 | -13.0 | 0.0 | 0.69 | 0.48 | 5.30 | 107.4 |
| v1.8.1 Strong | 6.18 | 12.19 | 67.8 | 5.37 | -19.1 | -13.1 | 0.0 | 0.19 | 0.43 | 4.55 | 107.4 |

## fling 2.5 px/ms decaying (tau 350 ms) then stop

| mode | jitter RMS | jitter p95 | jitter max | size RMS | lag px | lag ms | overshoot px | leak % | block shimmer RMS | block-count changes/s | moves/s |
|---|---|---|---|---|---|---|---|---|---|---|---|
| v1.8 (Off) | 13.61 | 22.39 | 181.1 | 9.57 | -17.0 | -23.4 | 33.4 | 3.33 | 0.95 | 10.09 | 89.2 |
| v1.8.1 Normal | 13.50 | 13.77 | 225.6 | 6.00 | -20.7 | -26.4 | 20.4 | 1.86 | 0.51 | 5.57 | 86.1 |
| v1.8.1 Strong | 13.03 | 12.94 | 212.6 | 5.86 | -22.1 | -30.3 | 16.4 | 1.72 | 0.41 | 4.87 | 84.9 |

## object moving in a video 0.25 px/ms, page still

| mode | jitter RMS | jitter p95 | jitter max | size RMS | lag px | lag ms | overshoot px | leak % | block shimmer RMS | block-count changes/s | moves/s |
|---|---|---|---|---|---|---|---|---|---|---|---|
| v1.8 (Off) | 12.52 | 28.46 | 79.3 | 10.03 | -7.7 | -16.8 | 0.0 | 1.12 | 3.04 | 23.01 | 107.1 |
| v1.8.1 Normal | 7.33 | 13.30 | 85.2 | 7.01 | -7.5 | -16.4 | 0.0 | 0.27 | 2.75 | 9.55 | 104.4 |
| v1.8.1 Strong | 6.81 | 11.23 | 85.2 | 6.02 | -7.5 | -16.3 | 0.0 | 0.37 | 2.65 | 3.85 | 104.2 |

