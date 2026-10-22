# Veil (v1.8.1)

Veil is a free, open-source, **fully on-device** nudity filter for Android 14+.
It watches the screen with MediaProjection, runs the NudeNet v3 detector
on every captured frame, and covers exposed regions with a **pixelate** censor
drawn in small overlay windows. Screen content never leaves the phone. Since
v1.6 Veil has the INTERNET permission, used **only** for model downloads you
start yourself (see below).

v1 is deliberately minimal. It has one censor style (pixelate) and is built for
**low latency**. There is no browser, recording, stats, presets, PIN or
translations.

> **Status:** v1 ran on a Galaxy S23 FE (censoring works; visible lag while
> scrolling). v1.1 targets that scroll lag. It compiles and its core logic is
> unit-tested on the JVM, but **v1.1 has not run on a device yet.**

## What's new in v1.8.1 (steadier boxes)

Feedback on v1.8 (Galaxy S23 FE): "still a bit jittery". v1.8.1 makes the
censor boxes steadier **without adding lag**. Nothing is censored earlier
than before, and no box is drawn smaller than the detection. It has only been
measured in a synthetic replay (below), **not yet on a device**.

What changed:

* **Filtered velocity, not position.** Page-scroll velocity and object
  velocity now go through a 1-euro filter, which smooths steady motion but
  follows starts and stops at once. A stop or reversal resets the filter,
  so there is no overshoot. Positions still come straight from the newest
  frame and detection.
* **Tiny velocities are ignored.** A soft dead zone stops sub-pixel detector
  noise from being extrapolated 60–200 ms ahead.
* **Still boxes stay still.** v1.8 decided "this object is moving" from
  1.5 px of detector noise about once a second, and then the box switched
  geometry (20–30 px jumps). Starting to move now needs a clearer signal.
  Real motion is still caught within one detection, and a still box always
  grows at once to cover a new detection.
* **Size noise:** moving boxes grow at once but shrink slowly.
* **Irregular capture gaps** (dropped frames) no longer brake the extrapolation
  and then snap it forward.
* **Pixel blocks:** still boxes keep their snapped block edges until the
  detection is clearly past them. Moving boxes keep a whole-block grid
  anchored to the box, with hysteresis on the block count, so the pattern no
  longer re-cuts every frame.
* **Rounding:** overlay windows (and the accessibility-mode sheet) now take
  their width and height from the box size and round the centre, so edges no
  longer flicker by 1 px. One extra pixel keeps the box covering.

**New setting: Performance › Speed › Box smoothing.** Normal (the default),
Strong (steadiest) or Off (exactly v1.8, for comparison).

### Measured (synthetic replay, `tools/jitter_sim.sh`)

The replay runs the real tracker, box-shaping and rounding code on a
simulated S23 FE timeline:

* 120 Hz display; vsync-aligned capture at about 60 fps with dropped frames;
* detector noise of 1.5 px centre and 2 px size, plus ±4 % scale flips;
* about 20 ms detection;
* screen px, 12 seeds.

Jitter is the frame-to-frame box-centre movement that does not match the
content (0 = moves exactly with it). Lag ms below 0 means the box is ahead
of the content (the intended lead).

| scenario | jitter RMS / p95 (v1.8 → Normal → Strong) | leak % (v1.8 → Normal → Strong) | lag ms (v1.8 → Normal) | block-count changes/s (v1.8 → Normal) |
|---|---|---|---|---|
| stationary | 5.43 / 8.0 → 3.04 / 0.0 → 2.37 / 0.0 | 0.83 → 0.16 → 0.07 | 0 → 0 | 7.1 → 3.1 |
| slow scroll | 5.06 / 8.5 → 3.52 / 3.3 → 2.85 / 2.7 | 0.61 → 0.36 → 0.04 | −11.3 → −13.4 | 7.5 → 3.5 |
| steady scroll | 7.90 / 12.3 → 6.67 / 12.2 → 6.18 / 12.2 | 0.76 → 0.69 → 0.19 | −12.0 → −13.0 | 10.3 → 5.3 |
| fling + stop | 13.6 / 22.4 → 13.5 / 13.8 → 13.0 / 12.9 | 3.33 → 1.86 → 1.72 | −23.4 → −26.4 | 10.1 → 5.6 |
| moving object | 12.5 / 28.5 → 7.33 / 13.3 → 6.81 / 11.2 | 1.12 → 0.27 → 0.37 | −16.8 → −16.4 | 23.0 → 9.6 |

Fling overshoot after the stop: 33 → 20 px (Strong 16). The full table is in
`tools/jitter_report.md`. The fling's single largest frame step is the
catch-up in the first ~40 ms of a sudden fling. It happens in every mode.

## What's new in v1.8 (portrait input, accessibility mode, steadier prediction)

v1.8 adapts ideas from a static review of another on-device filter to Veil's
own GPU path. Everything is implemented from scratch here; no code, assets,
text or model from that app are used. **v1.8 has not run on a device yet**:
it compiles, its logic is unit-tested on the JVM and the models were checked
on the build machine (numbers below).

### 1. Portrait model input (new default, bundled)

A phone screen is about 1 : 2.17 (S23 FE capture 590 × 1280). v1.7's square
320 × 320 letterbox put the whole screen into a 148 × 320 strip; 54 % of the
input was padding. v1.8 exports the NudeNet 320n weights at fixed portrait
shapes (FP16 TFLite, sides multiples of 32) and picks the input layout from
the capture aspect:

| model | input | scale on 590 × 1280 | input pixels vs 320² | box CPU, 4 thr / 1 thr | est. S23 FE GPU | where |
|---|---|---|---|---|---|---|
| 320n square (v1.7 default) | 320 × 320 | 0.250 | 1.00× | 6.3 / 18.4 ms | 16–24 ms (measured) | download |
| **320n p224 (default)** | 224 × 480 | **0.375** (1.5×) | 1.05× | 6.3 / 19.5 ms | ~17–25 ms | **bundled** |
| 320n p256 | 256 × 544 | 0.425 | 1.36× | 7.8 / 25.8 ms | ~22–33 ms | download |
| 320n p288 | 288 × 608 | 0.475 | 1.71× | 9.6 / 32.7 ms | ~27–41 ms | download |
| 320n p320 | 320 × 704 | 0.542 | 2.20× | 11.6 / 41.8 ms | ~35–53 ms | download |

GPU numbers are the measured square-320 figure scaled by input pixels
(estimates). **Landscape** (full-screen video): the frame's left and right
halves (6 % overlap) are stacked into the portrait input ("fold"), so content
stays upright, rotation needs no model reload, and the scale is 0.313 for p224
(v1.7: 0.25). Boxes that straddle the seam are merged. Grid-boost tiles take
the model's aspect. The capture size is unchanged (long side 1280 with grid
boost).

**Recall check** (`tools/parity.py`, `tools/parity_vs_640m.py`; SFW images
only: 61 COCO-128 photos with people, the Ultralytics bus/zidane samples and a
synthetic feed page, placed on 221 portrait/landscape phone canvases; score ≥ 0.3):

| model | recall of 640m@640 boxes, portrait (all / non-face) | landscape (all / non-face) |
|---|---|---|
| 320n square (v1.7) | 41.3 % / 12.2 % | 47.6 % / 26.7 % |
| **320n p224** | **62.7 % / 36.7 %** | **52.4 % / 40.0 %** |
| 320n p256 | 66.7 % / 44.9 % | 69.0 % / 60.0 % |
| 320n p288 | 73.8 % / 53.1 % | 88.1 % / 100 % |
| 320n p320 | 81.7 % / 79.6 % | 88.1 % / 93.3 % |

p224 finds 116 boxes where v1.7 found 82. It keeps 62 of v1.7's 82 boxes; of
the 20 it drops, only 3 are confirmed by 640m (the rest are mostly faces near
the threshold). The export matches the PyTorch weights (max box difference
1e-6 FP32, 3e-4 FP16). Sample sets are small and contain no nudity, so these
numbers are a sanity check, not a benchmark of the censored classes.

### 2. GPU-side zero-copy capture: not done, on purpose

The CPU stages (global shift, scene-cut guard, skin/appearance guard, ring
checks, pixel colours for the censors) need the full RGBA frame every frame
anyway, so the frame is still copied once from the ImageReader. Neither
LiteRT 1.4's GpuDelegate nor LiteRT 2's TensorBuffer API (Java/Kotlin) can bind
an external AHardwareBuffer/GL texture as model input. That would need our
own NDK code that cannot be tested without a device. The portrait input
removes most of the letterbox waste instead: almost every input pixel is
content, and the padding is zero-filled only when the layout changes.

### 3. Optional Accessibility mode (Performance › Accessibility mode)

Off by default. The first switch-on opens a disclosure screen; Android's
accessibility setting must also be on. When active:
- **One full-screen TYPE_ACCESSIBILITY_OVERLAY window** (touch-through, fully
  opaque censors) draws all boxes in one view. A box move is a redraw on the
  next vsync, never a WindowManager relayout. When the mode is off or the
  service is not connected, the v1.7 per-box windows are used.
- **Event hints:** TYPE_VIEW_SCROLLED keeps "scrolling" true for 250 ms. A
  scroll is never treated as a scene cut, extrapolation keeps the page velocity
  through unsure shift estimates, and nothing is covered pre-emptively (the
  scroll-entry strip is off). A different activity or foreground window
  (window-state/windows-changed) marks the current boxes stale: the first full
  detector pass 200 ms later drops those it does not find again.
- **Android 14+ verification:** while a censor covers something, Veil takes
  `takeScreenshotOfWindow` of the foreground app window (at most every 500 ms,
  never while scrolling, backs off when the system throttles). That window does
  not contain Veil's overlay, so the detector can see what is under our own
  censors. Boxes not found twice are retired (once after a screen change), and
  the censor's pixels are refreshed from it. Kept in memory only, scaled to the
  capture size, never saved or sent.
- It reads **no text or view content**: only event types and times, the
  package/class of window-state events, and the id, type, layer and bounds of
  windows.

### 4. Stronger vsync extrapolation, without overshoot

Boxes are placed at `pos(tCap) + v · dt`. Up to a knee (capture delivery +
analysis + hop + draw-to-glass lead + 1.5 capture intervals: the age a box
normally has) `dt` is the real age, so boxes lead the motion exactly. Past
the knee no newer frame came although one was due, which with MediaProjection
usually means the content stopped. `dt` then saturates smoothly. Without a
scroll hint the tail is at most 40 ms; while the app reports scrolling it may
run to the per-model cap (180 ms for p224/square 320, 200–230 ms for larger
models). v1.7 extrapolated linearly to 200 ms, which overshot after a fling
stopped.

### HUD

New lines show the input layout (fit/fold, scale, fill), the prediction knee
and cap, and the accessibility state (overlay mode, scroll events, screen
changes, verifications / drops / failures).

## What's new in v1.7 (clean UI, floating control)

**Main screen** (Material 3, light and dark): app name and icon, then a
protection card with one Start/Stop button, a status line ("Protection is
on/off/paused") and the hint to pick "A single app". The floating control
switch is in this card too. Below that:
- a **What to censor** card: Exposed, Covered, Belly, Male chest and Faces.
  Female/Male faces appear only while Faces is on;
- a **Censor** card: the pixel size slider and a live preview at that size
  (left third original, the rest pixelated as the overlay will do it);
- a short list to the secondary screens.

Secondary screens, each with a back arrow:

| screen | contents |
|---|---|
| Detection model | Models grouped as TFLite (GPU) and official ONNX (CPU). One primary action per row (Download / Resume / Cancel / Use; none on the model in use). Size and speed note as secondary text. Delete is in the row's ⋮ menu. *Import model file…* and *Model host URL…* are in the top-bar overflow. One-line network note in the footer. |
| Performance | Backend (Auto / GPU OpenCL / GPU GL / CPU, picked in a dialog) and NNAPI for ONNX (classic build only). Benchmark. Grid boost, fresh-frame wait (new switch, default on = v1.6.1 behaviour), scroll prediction slider. Guards: scene-cut, skin (appearance), scroll-entry strip. |
| Debug | Debug HUD (default off; an existing value is kept) and timing log (VeilCSV). |
| About | Version, runtime, licence (AGPL-3.0-or-later), NudeNet credit, privacy, network, capture mode, limits. |

The permission buttons are gone: Start asks for overlay and notification
permission when needed, as before. All settings use the same keys, so
existing values carry over, and they apply live exactly as before. There
was no separate 256 switch left; the pre-1.5 `fast256` value still migrates
to the 320n@256 model.

**HUD** starts collapsed to one line (`model · det fps · e2e ms`, plus
`PAUSED` when paused). Tap it to expand the full text and tap again to
collapse. It is touchable only within its own bounds.

**Floating control** (default on, switch on the main screen). This is a
small bubble shown only while protection runs:
- drag it and it snaps to the nearer side edge; its side and height are
  remembered;
- it fades to 35 % when idle;
- tap it for a 4x2 menu:
  - Pause/Resume. Paused shows an amber bubble with pause bars, `PAUSED` in
    the HUD and a notification note. The pipeline keeps running while
    paused, so resume is instant;
  - Model. Lists the bundled and downloaded models and switches with the
    existing live hot-swap (full-screen pixelation until the first
    detection);
  - Pixel size (cycles 6/10/16/24/32 dp);
  - Faces, Covered, HUD, Settings, Stop;
- a tap outside closes the menu without consuming the tap.

Both windows are NOT_FOCUSABLE, so touches outside them go to the app
underneath. All the UI work is on the main thread from user input. The
capture and detector threads only get a one-shot re-publish on
pause/resume. In full-screen capture, the bubble, the menu and the HUD are
registered with the renderer like Veil's own censors: they are masked from
the shift, scene and skin guards, and detections lying >= 60 % on them are
dropped. In single-app capture they aren't captured at all. The bubble glyph
is neutral grey.

Build: Material 3 needs Material Components and AppCompat. To keep the
classic zip under 25 MB, debug builds are now **shrunk with R8 without
obfuscation** (`app/proguard-rules.pro` keeps all Veil classes and the
ONNX Runtime / TFLite / LiteRT JNI classes whole). The androidx-added
`DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is removed, so the permissions
are unchanged from v1.6.1.

Screenshots: `ScreensTest` (Robolectric, native graphics) renders every
screen in light and dark, plus the bubble, menu, model list, paused state
and HUD. Output goes to `$VEIL_SCREENS_DIR`, default `app/build/veil-screens`.

## What's new in v1.6.1 (latency: boxes follow motion again)

On-phone HUD (S23 FE, Reels, 320n GPU) before the fix: shift 16.4 ms,
analyze+publish 17.7 ms, post 10.0 ms, queue 10.4 ms, detector e2e 103 ms,
tiles 7.8/s on a full-screen video, and boxes trailing a dancing body.

- **Shift estimator rewritten** (`ShiftEstimator.kt`). The old code was
  unchanged since v1.2, but its cost grows with capture size: profile
  reads scale with h·w and the search with h². The 1280-px capture
  added in v1.3 for grid boost made it ~2.5x more work than at 960. Its
  row pass also read every 8th pixel of every row (32-byte steps), so it
  touched all ~53k cache lines of the 3 MB frame on every frame. On the
  phone (GPU saturating memory) that pass, not the arithmetic, is the
  likely cost: the other passes on the same thread are within 1.3–3x of the
  box, the old shift was ~36x. Changes:
  - rows at half vertical resolution;
  - 3 one-cache-line stripes per band;
  - columns every 2nd pixel, every 16th row;
  - a 3-level search pyramid;
  - a branch-free path when nothing is masked;
  - V-fit sub-pixel interpolation.
  Accuracy is unchanged (worst 0.02 px in the 590x1280 test). It is self-tuning: if the
  measured EMA exceeds 2 ms it switches to "lite" (rows every 4th, columns
  every 32nd row) and back below 0.6 ms. The HUD shows `shift X (prof Y srch Z [lite])`.
- **Tracker is motion-adaptive** (`Tracker.kt`). Measurements are timed at
  their frame's capture time and predicted forward. Before, a detection
  60–100 ms old was treated as "now", so a moving body always lagged by
  speed x latency. Moving tracks get zero smoothing. Velocity and centre
  come from a least-squares line through the last 3 detections. Motion is
  decided from the box's *smaller* side: the old deadband of 6 % of the
  largest side (18 px on a 300-px body) classified most dancing as
  "still noise" and halved the velocity. Moving boxes are not snapped to
  the pixel grid and their colours aren't blended. Still boxes keep the
  v1.5 anti-jitter behaviour. Lead padding is halved, because prediction
  now covers the latency.
- **Grid boost only on grids**. Tiles run only when the frame shows narrow
  uniform vertical gutters (found in the shift pass, debounced), never
  within 1.5 s of a large full-frame detection, and full passes keep at
  least 2/3 of the detector. Full-screen video and single large photos get
  0 tiles. The HUD shows `grid boost: idle (no grid)` or
  `idle (large subject)`.
- **Fresh-frame wait** (`FreshWait.kt`). If the detector frees up while
  the newest frame is already older than 0.4 x the capture interval, it
  waits for the next frame. Mean age of the shown detection drops by about
  3.6 ms, at a cost of ~10 % detector rate (simulated). The HUD's queue
  line shows the wait separately.
- Box regression tests: `V161PerfTest`, `V161MotionTest`, `FreshWaitTest`.
  These measure shift accuracy and cost against the old estimator, the
  capture-thread cost, gutter detection, and dancing-body lag (centre lag
  23.9 → 11.7 px, 0 px uncovered).

## What's new in v1.6 (download and switch models in the app)

**Model manager** (main screen, "Detection models"). Each model shows its name,
size, a speed note and its status (bundled / not downloaded / partly downloaded /
downloading with a progress bar / downloaded). Buttons: **Download** (becomes
**Cancel** while running), **Delete** and **Use**. **Use** applies immediately.
If protection is running, the capture keeps going and is not re-consented. Veil
builds a new pipeline for the model, swaps it in on the capture thread and
resizes the capture if the model needs a different size. It pixelates the whole
frame until the new model's first detection, so nothing slips through while the
model loads. The debug HUD's first line shows the active model id.

| id | runtime | source | size | speed note (S23 FE) |
|---|---|---|---|---|
| `320n_320` | TFLite GPU | **bundled** | 6.2 MB | measured ~16–24 ms (default) |
| `320n_256` | TFLite GPU | your host | 6.2 MB | est. ~12–18 ms |
| `320n_640` | TFLite GPU | your host | 6.3 MB | est. ~40–70 ms |
| `640m_640` | TFLite GPU | your host | 52.0 MB | est. ~120–300 ms |
| `onnx_320n_480` / `onnx_320n_640` | ONNX Runtime CPU | **official NudeNet release** | 12.2 MB (one shared file) | est. ~30–60 / 50–100 ms |
| `onnx_640m_480` / `onnx_640m_640` | ONNX Runtime CPU | **official NudeNet release** | 103.5 MB (one shared file) | est. ~250–500 / 400–800 ms |

"est." means estimated from desktop measurements and has not been measured on
the phone. Use the in-app benchmark (it also handles ONNX models: CPU vs. NNAPI).

**Network use.** INTERNET is used only when you tap Download. HTTPS is
mandatory: cleartext is blocked by the network security config, which also
trusts only system CAs. There are no analytics, no update checks and no
background jobs. A download runs on a plain thread for as long as the app
process lives; a partial `.dl` file is resumed (HTTP Range) on the next tap.
Up to 5 attempts are made with backoff. Every file is checked against a size
and SHA-256 **hard-coded in the app** (`ModelSize` in `Detector.kt`) before
it is renamed into place. A file that doesn't match is deleted and never
loaded. GitHub's API asset endpoint is tried first (`Accept:
application/octet-stream`, redirects followed by hand), then the public
download URL. An HTML response (a login or rate-limit page) is rejected.

**Hosting: how each source works**
- **Official ONNX (works out of the box).** The `320n.onnx` and `640m.onnx`
  files from `notAI-tech/NudeNet` release `v3.4-weights`, run with ONNX
  Runtime Android 1.31 on CPU/XNNPACK (NNAPI optional under Advanced). This
  needs no hosting of your own, but there is no GPU. **Classic build only**:
  ORT adds ~13 MB compressed and would push the litert2 zip past 25 MB.
- **TFLite GPU variants (needs a host).** No trustworthy public mirror of
  these TFLite conversions exists, and converting on the phone isn't
  feasible. Upload the three files from `/workspace/out/veil-model-host/`
  (names and hashes in `SHA256SUMS`) to any HTTPS location, for example a
  GitHub release. Then enter the base URL under Advanced → "TFLite model host".
  Veil appends the file name. The shipped default
  (`ModelSize.DEFAULT_BASE_URL`, a `YOUR-GITHUB-USER` template) counts as
  "not configured". *(v1.8: the default is now the project's own release
  `onna83/Veil` v1.8, which hosts every TFLite variant.)*
- **Manual import (offline fallback).** "Import model file(s)…" accepts any
  catalog file: the TFLite variants, the 640m split part zips or the `.onnx`
  files. The file is recognised by its SHA-256.

Only `320n@320` is bundled now; the 256 and 640 variants of 320n left the APK.

## What's new in v1.5 (less jitter, face bug, pixel size, bigger models)

**Face bug (pixelated male faces with Faces off), root cause.** It was the skin-colour *appearance guard*, not the
detector or the class filter. The guard ignores classes: it covers skin that is new compared with the detector's last
frame. In reels a face is skin that keeps moving, so its leading edge counted as new skin every few frames. A box
appeared on the face, the next detector pass released it, and it re-appeared (9 re-triggers per second in the test).
Fix: the comparison is now motion-tolerant. Skin counts as new only if the detector saw no skin within ±3 cells (~36 px).
Genuinely new skin elsewhere still triggers in the same frame.
Hardening:
- The decoder drops an anchor when a disabled face or male-chest class scores ≥ 2× the best enabled class there, so a
  face can never come out as a low-scoring enabled class.
- "Exposed" never included MALE_BREAST_EXPOSED. It is now a separate **Male chest** switch, off by default.

**Jitter.**
- Tracker: while an object is still (measured displacement < max(4 px, 6 % of its size)) its velocity decays instead of
  chasing noise. Edges get hysteresis: grow immediately to the detection plus a small margin, shrink only when the
  detection is clearly inside. Real motion is followed almost directly, the box always contains the detection, and
  first appearance is immediate.
- Motion-lead padding only applies above 0.05 px/ms.
- Censor boxes snap to a pixel grid anchored to the page content, so blocks move with what they cover and boxes resize
  in whole blocks. Block colours are smoothed when the grid is unchanged (no shimmer).
- Overlay windows get a transparent margin (8 px or 8 %), so small changes redraw instead of relayouting the window.
- Simulation result: still-object box jitter 4.2 → 1.0 px (−77 %), no under-coverage.

**Pixel size.** A slider from 4 to 40 dp, default 10 dp (v1.4 blocks were about 1/7 of the box, often 20–30 dp).
Applies live.

**Detection model** (Advanced, applies at next start; the HUD shows `model …`):

| option | input | file | box CPU, 4 threads (measured) | S23 FE GPU (estimate) |
|---|---|---|---|---|
| 320n @ 320 (default) | 320 | bundled, 6.2 MB | 7.3 ms | measured 16–24 ms |
| 320n @ 256 | 256 | bundled, 6.2 MB | – | ~0.7× of 320 |
| 320n @ 640 | 640 | bundled, 6.3 MB | 23.4 ms | 40–70 ms (4× FLOPs, 8.1 GFLOPs) |
| 640m @ 640 | 640 | import, 52.0 MB FP16 | 177 ms | 120–300 ms (78.7 GFLOPs) |

640m does not fit a 25 MB attachment (48 MB zipped). It ships as `veil-model-640m.part{1,2,3}of3.zip` (~16 MB each).
In the app, tap *Import 640m model…* and select all three parts. They are joined and SHA-256-verified, so a wrong file
is never loaded. Grid-boost tiles are off with 640m (too slow), and capture is capped at 1280 px for 640-input models.

## What's new in v1.4 (choose what to censor)

Settings → **What to censor** (persisted, applied live while running; no restart needed):

| toggle | NudeNet classes | default | start / keep threshold |
|---|---|---|---|
| Exposed intimate parts | FEMALE_BREAST_EXPOSED, FEMALE_GENITALIA_EXPOSED, MALE_GENITALIA_EXPOSED, BUTTOCKS_EXPOSED, ANUS_EXPOSED | on | 0.30 / 0.20 (unchanged safety bias) |
| Covered / clothed versions | FEMALE_BREAST_COVERED, FEMALE_GENITALIA_COVERED, BUTTOCKS_COVERED, ANUS_COVERED | off | 0.40 / 0.25 |
| Exposed belly | BELLY_EXPOSED | off | 0.40 / 0.25 |
| Faces → Female / Male | FACE_FEMALE, FACE_MALE | off (sub-options on) | 0.35 / 0.20 |

The label names were checked against NudeNet's label list and the model metadata (`Labels.kt`).
- The fast-motion keep threshold is 0.6 × keep.
- The decoder, the full-frame and tile passes, and the tracker all use the enabled set. Turning a group off drops its boxes at once.
- The scene-cut guard is only active when at least one group is enabled.
- The skin-colour appearance guard only runs when exposed parts or belly are enabled. Faces do not make it more aggressive: skin-coloured new content is too common to do that without flicker.
- The HUD has a `censor:` line, e.g. `exposed+covered+faces(F,M)`.

## What's new in v1.3 (no pre-emptive covering while scrolling, grids, fast motion, icon)

**Scrolling stays clean.** The scroll-entry strip guard is now **off by default** (Settings → Advanced).
The appearance guard never triggers while a scroll is in progress or for 150 ms afterwards, and any
appearance boxes it was holding are dropped. The scene-cut guard never fires during or just after a
scroll, drops itself if the user starts scrolling, and is released by the first full-frame detector
result computed on the cut frame or a later one. Scroll compensation and per-vsync prediction are unchanged.

**Small images / grid boost (default ON).** At 320 px input, a whole phone screen shrinks feed and gallery
thumbnails to a few dozen pixels, so the detector misses them. Evaluation on synthetic SFW feed pages
(`/workspace/models/gridtest/`, ground truth = detections on each thumbnail crop), measured as recall:

| strategy | 2-column grid | 3-column | 4-column |
|---|---|---|---|
| full frame only (320/480/640 input) | ≈0 | ≈0 | ≈0 |
| full + 3 width bands | 0–0.3 | – | – |
| **full + 10 tiles (2 across, 15 % overlap), 960 capture** | 0.73 | 0.33–0.42 | 0.12–0.17 |
| **same, 1280 capture (v1.3 default)** | 0.87 | – | – |
| 21 tiles (3 across) | – | 0.67 | 0.36 |

With boost on, capture is 4× the model size on the long side (590×1280 for 320), so each tile is about 1:1
with the model input. A `TileScheduler` interleaves passes:
- While any tile is "hot" (it contains skin-coloured cells and has scrolled in, moved, changed, or never been
  checked), passes alternate full, tile, full, tile, so full-frame detection stays at about half the pass rate.
- When the full frame already shows large content, the pattern is full, full, tile.
- Otherwise one cold tile every 4th pass, so idle screens stay mostly full-frame.

Tile detections are mapped back to frame coordinates and merged by the tracker's IoU association. A
detection cut off by a tile edge can only grow a track, never shrink it. Tile tracks stay alive for 1.2 s,
until their tile is revisited. Tile passes release appearance-guard boxes only inside the tile.

**Fast motion and jump cuts.**
- Tracks that were moving faster than 0.25 px/ms when last seen:
  - accept weaker detections (score ≥ 0.12 instead of the normal keep threshold), which is typical of motion blur;
  - survive 800 ms of misses (normally 400 ms);
  - react faster to velocity changes (blend 0.8);
  - coast with less damping.
- Box padding in the motion direction is speed × detector age (clamped to 30–120 ms), plus global scroll velocity × 20 ms.
- The cut frame keeps inference priority, so the guard is released after about one detector latency.

**Icon (v1.3.1).** The default is the pixel-censored peach on dark plum (`tools/make_icon.py`). Variants in the
same spirit are generated by `tools/make_peach_variants.py` (B neon peach with a censor bar, C juicy glossy peach
with a pixel patch and sparkles, D minimal flat peach for small sizes). Each has background, foreground and
monochrome XMLs in `/workspace/out` (`veil-peach-*_*.xml`) to swap into `res/drawable`. The neon censor-bar
concepts (`tools/make_icon_neon.py`) are kept as alternatives.

### HUD (v1.3 additions)
`grid boost: N tiles of S px, hot n, full x/s tile y/s, hot-tile cycle ms` shows the tile count and size, how
many tiles are hot now, the full-frame and tile pass rates, and how long it takes to revisit every hot tile (EMA).

## What's new in v1.2 (new-object latency)

The first S23 FE run of v1.1 (Exynos 2200 / Xclipse 920, 120 Hz, single-app
mode, video) estimated 133 ms for a new object. Changes:

- **Shift estimator: 18.7 ms → expected < 1 ms.** The column profiles walked
  the frame column by column (17 KB jumps), which missed the cache on almost
  every read. They are now built row-major, with half the samples. The same
  stall had saturated the capture thread, which also caused the 45 fps capture
  cap and `post 11.9 ms` (detections waited for the busy thread).
- **Appearance guard** (new, on by default). Each frame keeps a 12-px cell
  grid of skin-tone fraction (YCbCr rule tested on all 10 Monk Skin Tone
  scale tones). It is compared with the grid of the last frame the detector
  processed, shifted by the scroll since. Cells that became clearly more
  skin, in groups of ≥ 4 cells and ≥ 2×2, are pixelated in the same pass that
  publishes tracks, about one capture frame after the content appears. The
  box stays until a detector result covers that content (at least 80 ms).
  It ignores static content, our own censors (masked), cells inside tracks
  and thin moving edges. Known false positives: cream, beige, wood and
  warm-grey content when it newly appears. ~0.1–0.3 ms per frame.
- **Auto backend = measured.** On first start, GPU-OpenCL, GPU-OpenGL and
  CPU-XNNPACK×4 are each timed on the device and the fastest is kept. The
  choice is cached per app version / SoC / model size. The in-app benchmark
  also updates it. The HUD shows `auto→…`.
- **Capture size follows the model.** The long side is 3 × model input (960
  for 320, so 444×960 on a 19.5:9 screen, ~33 % fewer pixels than 540×1170).
- **Overlay.** A new state is applied the moment it reaches the main thread,
  not on the next frame callback. The HUD shows `hop`, which replaces
  `frame-wait`; frame-wait also counted animation frames and was inflated.
  The probe latency is used only once full-screen capture is confirmed;
  before that, dark video content could fake a hit, as on the S23 FE
  (`lead 55`).
- **FP16 weights.** Both models store weights as FP16 (`tools/fp16_weights.py`),
  half the size. On the SFW samples: max score change 5e-4, boxes < 0.9 input
  px. Not INT8.

## What's new in v1.1 (scroll / video latency)

v1 moved censors only when the detector produced a result, from a frame that
was already ~25 ms old by then, and did everything on one thread. While
scrolling, the box therefore trailed the content by (scroll speed x total
latency). v1.1 decouples box movement from the detector:

1. **Global scroll compensation at capture rate.** Each captured frame, a
   row/column luma-projection matcher (`ShiftEstimator`, ~0.5 ms) measures how
   far the page moved, with our own censors and the probe masked out. Every
   track, including those under our own censor in full-screen capture, is
   moved by that shift at once.
2. **Detection-latency compensation.** Detections come from frame N but arrive
   a few frames later. They are moved by the scroll accumulated since frame N
   before they are merged, so they don't pull boxes backwards.
3. **Forward prediction on every vsync.** Boxes carry page velocity plus
   object velocity. A single Choreographer callback re-places every box each
   vsync at `pos(tCapture) + v x (frameTime + lead - tCapture)`. That covers
   the whole chain: capture delivery, analysis, the wait for vsync, and
   render + composition + scan-out (`lead`, measured in full-screen mode,
   otherwise 2.5 vsync). **Scroll prediction** (+0 to 60 ms) is a live slider
   for tuning on the device.
4. **Pipelined threads.** The capture thread copies, analyses, publishes and
   preprocesses into one of two input slots. The inference thread always takes
   the newest slot, so preprocessing of N+1 overlaps inference of N. Inference
   never waits for UI work, and the UI never waits for inference. ImageReader
   holds 3 buffers.
5. **Cheaper overlay movement.** Each window is the box plus about 120 ms of
   headroom in the direction of motion, and is transparent outside the box.
   While the box stays inside, moving it is just a redraw at a new offset,
   with no WindowManager relayout. Unused windows are parked off-screen
   rather than set to GONE, so their surfaces stay alive. Six are pre-warmed.
   There is never a full-screen window, which respects the Android 12+
   untrusted-touch rule.
6. **Full-screen capture handled explicitly.** A 6 px probe window toggles
   colour, and from it Veil detects whether its own overlays are captured. It
   also measures draw-to-capture latency. In full-screen mode:
   - tracks under our censor follow the scroll and are kept for up to 20 s;
   - they are re-validated against the uncovered ring of content around the
     box (`RingCheck`). If the surroundings stop matching, the censor is
     released so the detector can look again.
7. **Scroll-entry guard** (single-app mode only). While scrolling, the strip
   of newly revealed content at the entering edge is pixelated until the
   detector has seen it. Its size is the scroll the detector hasn't seen,
   plus speed x latency, plus 3 %. It is off in full-screen mode, because
   there the guard itself would be captured and hide the content from the
   detector. The scene-cut guard is kept. It masks our censors and does not
   fire during a confident scroll.
8. **Detector options.** Backend selector (Auto / GPU-OpenCL / GPU-OpenGL /
   CPU) and an in-app benchmark. Two build flavors:
   - `classic`: LiteRT 1.4 + GPU delegate, as in v1;
   - `litert2`: LiteRT 2.3 `CompiledModel` with the new GPU engine. It
     installs side by side as "Veil (LiteRT 2)".

   Experimental **256 px** input: export verified, but scores are noticeably
   lower and some classes flip on test photos, so it misses more. Off by
   default. No INT8 (no calibration data with the target classes). No NPU:
   LiteRT publishes NPU runtimes for Qualcomm, MediaTek and Google Tensor only,
   not Samsung Exynos.
9. **Per-stage HUD + CSV log**, see below.

### Debug HUD fields (v1.1)

```
<backend> | <SoC>
capture: SINGLE APP | FULL SCREEN | detecting…   <frame size>
cap  <captured fps> (int <ms between frames>) deliver <image ts -> our thread> copy <ms>
shift <ms> guard <ms> analyze+publish <ms> pre <ms>
appear guard: <ms>, boxes <n>, new cells <n>, fired <total>
det <detections/s>: queue <ms waiting in slot> infer <ms> decode <ms> post <ms back to capture thread>
overlay: hop <publish -> applied on main> apply <ms> layout <ms per updateViewLayout> relayout <n/s> draw <n/s>
probe draw→cap <ms or n/a> lead <prediction ms> vsync <ms>
e2e est: tracked <ms> | new obj: guard <ms>, detector <ms>   (ESTIMATES from the stages above)
scroll <dx>,<dy> px/frame ok|?  | tracks <n> (occ <n>) ring-drop <n>
```

"Track path" is the latency for content that is already tracked, which
prediction compensates. "New object" is the time until a newly appeared object
is first covered.

CSV: enable "Log per-frame timings", then
`adb logcat -s VeilCSV > veil.csv`. `F,` lines are frames, `D,` lines are
detections, and the header lines are printed at start.

## What it censors

The class list is hardcoded in `Labels.kt`: `FEMALE_BREAST_EXPOSED`,
`FEMALE_GENITALIA_EXPOSED`, `MALE_GENITALIA_EXPOSED`, `BUTTOCKS_EXPOSED`,
`ANUS_EXPOSED`. A track starts at confidence 0.30 and is kept alive by
detections down to 0.20. An anchor's score is its maximum over these classes
only, so a box whose top class is, say, "covered" is still censored if it also
scores high on an exposed class.

## How it works (latency path)

1. **Capture.** A VirtualDisplay at short side ≈ 540 px, with the content's
   aspect ratio, feeds an `ImageReader` (`RGBA_8888`, `maxImages = 2`). The app
   always takes `acquireLatestImage()`, copies the frame once into a reused
   buffer, and closes the image immediately. Any backlog is dropped.
2. **Scene-change guard** (toggle, on by default). This builds a 32×32 grid of
   sub-sampled colour and luma (~16k pixel reads) and diffs it against the
   previous frame. If ≥ 18 % of cells jump (a video cut or a fast scroll), the
   changed area is pixelated **immediately, before inference**. The detector
   result for that same frame then clears the guard. The guard is drawn as the
   grid's solid cells, so when it is captured again it reproduces the same
   averages and does not re-trigger itself.
3. **Refresh before inference.** Existing tracks get fresh pixel blocks from
   the new frame and are published before the model runs. A censored video
   stays a live mosaic, never a stale one.
4. **Inference** runs on a dedicated high-priority thread
   (`THREAD_PRIORITY_URGENT_DISPLAY` where allowed). Preprocessing is
   NudeNet-compatible: pad right/bottom to a square, bilinear resize to 320,
   RGB/255, NCHW. It writes straight into a reused float array, with no Bitmaps
   and no per-frame buffers.
5. **Decode + class-aware NMS** (IoU 0.45) uses preallocated arrays.
6. **Tracker.**
   - Greedy IoU matching against constant-velocity predictions.
   - A box is **shown on its first hit** and hidden only after **400 ms**
     without a detection.
   - EMA smoothing, but the box always contains the newest measurement.
   - 10 % padding per side (20 % total), plus extra padding in the direction
     of motion.
7. **Rendering.**
   - One `TYPE_APPLICATION_OVERLAY` window per box, from a pool with 4
     pre-created. Flags: `NOT_TOUCHABLE | NOT_FOCUSABLE | LAYOUT_NO_LIMITS`.
     Move animations are off.
   - Windows are updated only when a box moves more than 2 px.
   - Every vsync, boxes are extrapolated along their velocity to "now + one
     frame", so they lead motion and cover the pipeline latency.
   - Each window shows the region's average-colour blocks, upscaled with
     nearest-neighbour. If no clean pixels exist, it shows a solid dark fill.
8. **Self-occlusion (entire-screen capture).** Each frame, the app checks
   whether the captured pixels under a drawn box equal the blocks it drew. If
   they do, the track is "occluded":
   - it is kept alive and frozen in place, and keeps its last clean pixels;
   - it expires on a scene cut, or after 3 s without a real detection.

## Inference backend: LiteRT (TFLite), GPU first, CPU fallback

> **v1.8:** the bundled model is `assets/nudenet_320n_p224x480.tflite`
> (320n weights, FP16, 6.2 MB). Input is `[1,3,480,224]` NCHW; output is
> `[1,22,2205]` with boxes normalised per axis (x / 224, y / 480). The square
> 320 file and all other TFLite variants download from
> `https://github.com/onna83/Veil/releases/download/v1.8/` (the new default
> host, so no setup is needed). A stored v1.6/v1.7 host URL that still holds
> the `YOUR-GITHUB-USER` template is replaced by it. The details below describe
> the v1.1–v1.7 square export.

- **Model.** `320n.pt` was converted on the build machine with Ultralytics
  8.4.182 (`export(format="litert")`, which uses litert-torch) to
  `assets/nudenet_320n.tflite`.
  - Size: 11.6 MB, FP32 weights.
  - Input: `[1,3,320,320]`, NCHW.
  - Output: `[1,22,2100]`, with boxes normalised by 320.
  - The op set is small and GPU-friendly: CONV_2D, ADD, MUL, LOGISTIC,
    CONCAT, SLICE, SOFTMAX, TRANSPOSE, PAD, MAX_POOL, RESIZE_NN, RESHAPE, SUB.
  - Every tensor is rank ≤ 4.
- **Runtime.** `com.google.ai.edge.litert` 1.4.2 (classic Interpreter API).
  1. Try the **GPU delegate**: precision loss allowed (FP16 compute),
     sustained-speed preference, and kernel caching in `codeCacheDir` for fast
     restarts.
  2. If the delegate is unsupported or fails its warm-up inference, fall back
     to **XNNPACK CPU** with 2–4 threads.
  3. The HUD shows which backend is in use.
- **Why not ONNX Runtime?** It would have worked, since the research report's
  default was ORT + XNNPACK. But the user asked for the lowest latency, and the
  LiteRT GPU path is the accelerated option. NNAPI is deprecated, and the
  export converted cleanly. Both runtimes have similar XNNPACK CPU speed, so
  the CPU fallback costs nothing.
- **Equivalence check.** On this build machine:
  - The `.tflite` output equals the original `320n.onnx` output: scores within
    2e-6, boxes within 0.001 px after ×320.
  - Speed is about 5 ms per frame for either on the x86 box's CPU. This is not
    a phone number.
- **No INT8 yet.** Full INT8 needs calibration images that contain the target
  classes. Calibrating on SFW images only would quietly hurt recall on exactly
  the classes we censor. FP32 weights run as FP16 on the GPU delegate.
- **Channel order.** `nudenet.py` converts with `COLOR_RGBA2BGR` and then
  `blobFromImage(swapRB=True)`.
  - For 3-channel `cv2.imread` input, that swaps twice and feeds **BGR**.
  - For 4-channel input it feeds **RGB**.
  - Ultralytics trains in RGB, so Veil feeds RGB. On an SFW sample photo, RGB
    produced the expected FACE/FEET detections; BGR produced none.

## Build

Requirements: JDK 17 and an Android SDK with platform 35 (AGP installs the
build-tools it needs).

```bash
export JAVA_HOME=/path/to/jdk-17
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug               # both flavors: app/build/outputs/apk/{classic,litert2}/debug/
./gradlew testClassicDebugUnitTest    # JVM tests (decode, NMS, preprocess, tracker, guards, shift, ring, probe, scroll sim)
```

The APK is arm64-v8a only, to keep it small. To run on an x86_64 emulator, add
`"x86_64"` to `abiFilters` in `app/build.gradle.kts`.

To rebuild the model, run `tools/convert_model.sh`. To regenerate the test
reference data from NudeNet's own Python code, run `tools/make_reference.py`
and `tools/make_tflite_reference.py`. Those scripts use only synthetic images
and one SFW sample photo.

## Install and test on your phone

1. Copy `veil-v1.1-debug.apk` (and optionally `veil-v1.1-litert2-debug.apk`) to the phone and open it. Allow "Install unknown
   apps" for the app you opened it with (Files or Chrome). Or run
   `adb install veil-v1.1-debug.apk`.
2. Open **Veil**, tap **Allow display over other apps**, and enable Veil.
3. Tap **Allow notifications**. The notification carries the Stop button.
4. Optional: switch on **Debug HUD** for per-stage timings (fields above).
5. Tap **Start protection**. In the system dialog choose **"A single app"**,
   pick the app to filter, then tap Start. This is strongly recommended:
   Veil's censors are then not captured, so the detector always sees the real
   content and the scroll guard works. Entire screen works too, but Veil is
   blind under its own censors there and has to follow them by scroll
   estimation.
6. Stop from the notification, from the Android 15+ status-bar chip, or from
   the app.

For a useful bug report: `adb logcat -s VeilService VeilDetector VeilOverlay VeilPipeline VeilBench`,
a HUD screenshot, phone model and Android version.

## Limitations

- v1.1 scroll handling assumes one rigid shift of the whole frame per axis.
  Parallax, sticky headers or a scrolling region next to a static one give a
  compromise shift. The ring check releases tracks that no longer match.
  Diagonal motion is approximated by its dominant axis.
- Full-screen capture: if a censored track is released (ring mismatch, or 20 s
  without confirmation), the content can be visible for about one detection
  cycle before it is detected again. The probe shows a tiny dark square at the
  left edge, mid-height (single-app: it is removed after ~2 s).
- Overlay windows with motion headroom are transparent around the box but
  still swallow touches in that headroom (Android touch-occlusion rule). The
  headroom is only added along the current motion and is capped at 25 % of
  the screen.

- **Never zero-latency.** Android hands us frames only after they have been
  composed for display, so a newly appearing region can be visible for roughly
  one capture interval plus one inference before the censor lands. The scene
  guard, show-on-first-hit, padding and motion lead reduce this window; they
  cannot remove it.
- **FLAG_SECURE content** appears black to MediaProjection and cannot be
  filtered. Examples: incognito tabs, Netflix and other DRM video, banking
  apps.
- **Screen lock.** On Android 15 QPR1+ the system stops the projection when the
  screen locks. Veil shuts down cleanly; start it again after unlocking. Every
  session needs fresh consent.
- **Entire-screen mode.**
  - Our overlay is captured, so the detector cannot see under its own censor.
    Occluded tracks are kept alive (above) but expire after 3 s or on a scene
    cut. If the content is still there, a brief re-reveal for one inference
    is possible.
  - The guard (and censors captured with it) can briefly hide content.
  - Single-app mode avoids all of this. Per Google's docs only the chosen
    app's content is captured. This still has to be confirmed on a device.
- **Single-app mapping.** Censors are mapped to the full display. If the
  captured app is in split-screen or a freeform window, the platform does not
  report where it is on screen, and boxes will be misplaced. Use the app full
  screen.
- **Touch.** Touches inside a censor box are blocked (an Android 12+ rule for
  opaque overlays). A large guard area during a cut can briefly swallow a new
  tap there.
- **Accuracy** is NudeNet 320n's. It is a small model: expect misses on
  unusual angles and lighting, and some false positives.
- Supports Android 14+ (minSdk 34) only.

## Testing status

- **Done** (on the build machine):
  - JVM unit tests for label order (checked against NudeNet's source),
    decode, NMS, preprocessing, tracker, scene guard and pixel/occlusion
    helpers.
  - Decode vs NudeNet's `_postprocess`, on synthetic tensors and on real model
    output.
  - Preprocessing vs NudeNet's `_read_image`: max error 0.73/255.
  - Model equivalence check (TFLite vs ONNX).
  - APK checks: 16 KB page alignment of the native libraries, model stored
    uncompressed. (v1.6: INTERNET only for user-started model downloads.)
- v1.1 additions: shift estimator (integer/sub-pixel, masking our censors,
  static and unrelated content), ring check, capture probe, tracker shift,
  and an end-to-end scroll simulation. In that simulation (0.6 px/ms scroll,
  16 ms frames, detector 2 frames late), an uncompensated box trails by
  19 px. The v1.1 path stays within 0.3 px, and its 40 ms forward prediction
  within 0.4 px. This is synthetic data, not a device measurement.
- **Not done for v1.1:** any device run. Unverified:
  - every timing number and the expected gains;
  - whether VirtualDisplay delivers 60/120 fps on Samsung;
  - the LiteRT 2.3 GPU engine on Xclipse;
  - probe colour detection through Samsung's capture pipeline;
  - shift estimation on real apps.
- **v1 status:** no run on a real device or emulator at that time (the build
  box had no KVM access). Unverified then:
  - the GPU delegate on real GPUs;
  - real FPS and latency;
  - overlay placement on screens with cutouts;
  - rotation;
  - whether our overlay really is excluded from single-app capture.

## Credits and license

- **NudeNet** by notAI-tech (Praneeth Bedapudi et al.):
  https://github.com/notAI-tech/NudeNet. The bundled model is converted from
  NudeNet v3.4 `320n.pt` and is licensed **AGPL-3.0**.
- **LiteRT** (TensorFlow Lite runtime) by Google, Apache-2.0.
- The model was converted with Ultralytics (AGPL-3.0).

Veil itself is **AGPL-3.0-or-later**; see `LICENSE`. If you distribute it, or
a modified version, you must provide the corresponding source. The name,
code and icon are original to this project.
