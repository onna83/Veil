# Veil (v1)

Veil is a free, open-source, **fully on-device** nudity filter for Android 14+.
It watches the screen with MediaProjection, runs the NudeNet v3 `320n` detector
on every captured frame, and covers exposed regions with a **pixelate** censor
drawn in small overlay windows. It has no network permission, so nothing ever
leaves the phone.

v1 is deliberately minimal. It has one censor style (pixelate) and is built for
**low latency**. There is no browser, recording, stats, presets, PIN or
translations.

> **Status:** compiles, and the core logic is unit-tested on the JVM. It has
> **not yet run on a real device.** See "Testing status" below.

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
./gradlew assembleDebug           # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest       # JVM tests (decode, NMS, preprocess, tracker, guard)
```

The APK is arm64-v8a only, to keep it small. To run on an x86_64 emulator, add
`"x86_64"` to `abiFilters` in `app/build.gradle.kts`.

To rebuild the model, run `tools/convert_model.sh`. To regenerate the test
reference data from NudeNet's own Python code, run `tools/make_reference.py`
and `tools/make_tflite_reference.py`. Those scripts use only synthetic images
and one SFW sample photo.

## Install and test on your phone

1. Copy `veil-v1-debug.apk` to the phone and open it. Allow "Install unknown
   apps" for the app you opened it with (Files or Chrome). Or run
   `adb install veil-v1-debug.apk`.
2. Open **Veil**, tap **Allow display over other apps**, and enable Veil.
3. Tap **Allow notifications**. The notification carries the Stop button.
4. Optional: switch on **Debug HUD** to see backend, detector ms per frame,
   FPS and capture-to-censor latency.
5. Tap **Start protection**. In the system dialog choose **"A single app"**,
   pick the app to filter, then tap Start. (Entire screen works, but our own
   censors get captured; see limitations.)
6. Stop from the notification, from the Android 15+ status-bar chip, or from
   the app.

For a useful bug report: `adb logcat -s VeilService VeilDetector VeilOverlay`,
a HUD screenshot, phone model and Android version.

## Limitations

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
    uncompressed, no INTERNET permission.
- **Not done:** any run on a real device or emulator (the build box had no
  KVM access). Unverified:
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
