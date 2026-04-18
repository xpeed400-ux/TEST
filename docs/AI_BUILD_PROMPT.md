# 🤖 ProCam S23 FE — AI Auto-Build Prompt Pack

This file is the **master prompt** for driving a large-language-model (Gemini 2.5 Pro,
Claude Sonnet 4.x, or any tool-using LLM) through the remaining implementation work on
top of the skeleton in this repo.

The prompt is organised as **Base Context** followed by **individual task prompts** that
you paste one-at-a-time. Each task is self-contained, lists the files the model is
allowed to touch, and ends with concrete acceptance criteria so the model knows when
to stop.

---

## 0 · Base Context (paste ONCE at the start of every session)

```text
You are a senior Android engineer working inside the ProCam S23 FE codebase.

Hardware target: Samsung Galaxy S23 FE (Exynos 2200, Mali-G710, Sensor GN3).
SDK:            minSdk 30, targetSdk 34, compileSdk 34.
Language:       Kotlin 1.9, AGP 8.5.2, Gradle 8.7, Material 3, ViewBinding.

Architecture packages (do not move, do not rename):
  com.procam.s23fe
    ├── camera/      Camera2 session, manual controls, EIS crop, AE/AWB lock
    ├── core/        RecordingProfile, ManualControls, DevicePreset
    ├── gpu/         LutEngine, LutRenderThread, LutCatalog, LutLibrary, CubeLutParser
    ├── photo/       PhotoCapture
    ├── recorder/    ProCamRecorder
    ├── thermal/     ThermalManager
    └── ui/          MainActivity + widgets

Hard rules:
  1. **Never change** CONTROL_AE_TARGET_FPS_RANGE logic in CameraController — it must
     stay clamped to (fps, fps). This is the fix for the documented "60fps collapse".
  2. **Never weaken** the audio spec: stereo / 48 kHz / 256 kbps AAC is non-negotiable.
  3. All numeric clamps for ISO/shutter/focus/EV/WB/crop go through DevicePreset.
     Do NOT hard-code bounds anywhere else.
  4. All heavy pixel work must run on the GPU via LutEngine + LutRenderThread.
     CPU fallback is forbidden for live frame processing.
  5. Rotation math lives in one place: ProCamRecorder.computeOrientationHint.
  6. Every change must include or update a unit test under `app/src/test/`.
  7. Keep existing public function signatures; extend, don't break.
  8. Never introduce a new permission without updating AndroidManifest.xml AND
     wiring the runtime permission request in MainActivity.ensurePermissions().

Deliverable format per task:
  - A list of files you modified or created (absolute paths).
  - Full final content of each modified file.
  - A one-paragraph summary of what changed and why.
  - The exact `./gradlew` command I should run to verify.
```

---

## 1 · Task: Tap-to-focus with `CONTROL_AF_REGIONS`

```text
Extend tap-to-focus from "animated reticle only" to "actually tell the camera to
focus there".

Files you may touch:
  app/src/main/java/com/procam/s23fe/camera/CameraController.kt
  app/src/main/java/com/procam/s23fe/ui/MainActivity.kt
  app/src/test/java/com/procam/s23fe/camera/AfRegionMathTest.kt  (new)

Specification:
- Add `CameraController.focusAt(normalizedX: Float, normalizedY: Float)` that:
    a) Converts the (0..1, 0..1) preview coordinate into a sensor-space Rect using
       the *current* SCALER_CROP_REGION (so EIS crop is respected).
    b) Sends a one-shot CONTROL_AF_TRIGGER_START with CONTROL_AF_REGIONS set to a
       150x150 sensor-px box around the tapped point (weight = 1000).
    c) After AF converges (CONTROL_AF_STATE FOCUSED_LOCKED or NOT_FOCUSED_LOCKED),
       resumes the previous repeating request.
- MainActivity's preview OnTouchListener calls focusAt(ev.x/width, ev.y/height).
- The coordinate-mapping function must be pure (no Android deps) so it's testable.
  Put it in a private helper with an @VisibleForTesting wrapper.

Acceptance:
  ./gradlew :app:test
  AfRegionMathTest covers: top-left tap → sensor rect in top-left quadrant of crop;
  center tap → centered rect; rect never exceeds crop bounds.
```

## 2 · Task: Live histogram feed

```text
Wire the existing HistogramView to real frame data.

Files:
  app/src/main/java/com/procam/s23fe/camera/CameraController.kt
  app/src/main/java/com/procam/s23fe/ui/widgets/Histogrammer.kt
  app/src/main/java/com/procam/s23fe/ui/MainActivity.kt

Specification:
- Add a 3rd output surface to the CaptureSession: an ImageReader at 320x180 YUV_420_888,
  maxImages = 2.
- On each frame (throttle to every 6th via a counter), call Histogrammer.fromYPlane
  and post the 256-int histogram to HistogramView.submit on the main thread.
- Close the Image in a `try/finally`.
- The ImageReader thread must NOT be the camera thread (use a dedicated HandlerThread
  named "ProCamHistogram").

Acceptance:
  Histogram redraws on the HUD while camera is live. No leak in logcat
  ("ImageReader_JNI" warnings) over a 2-minute session. Adds HistogramFeedTest
  that mocks Image and verifies throttling (only every 6th frame consumed).
```

## 3 · Task: DNG capture with proper metadata

```text
Implement RAW (DNG) photo capture.

Files:
  app/src/main/java/com/procam/s23fe/camera/CameraController.kt
  app/src/main/java/com/procam/s23fe/photo/PhotoCapture.kt
  app/src/main/java/com/procam/s23fe/ui/MainActivity.kt
  app/src/main/res/layout/activity_main.xml   (add shutter button; see design tokens)

Specification:
- Add a stillSurface pair: JPEG ImageReader (max sensor size, JPEG, maxImages=2) AND
  RAW_SENSOR ImageReader (sensor size, RAW_SENSOR format, maxImages=2).
- Add `CameraController.capturePhoto(outputs: PhotoOutputs)` that issues a burst with
  CaptureRequest.TEMPLATE_STILL_CAPTURE. Use setTag so onCaptureCompleted can route
  results to PhotoCapture.saveJpeg / saveDng.
- For DNG, write via DngCreator.writeImage(file, rawImage). Include orientation
  matching recorder's hint formula.
- Add a shutter button in bottom bar, styled matching Hud.Chip / Hud.RecordButton.
- Respect DevicePreset.ISO_MAX_PHOTO ceiling (6400) while still in photo mode.

Acceptance:
  Tapping the shutter in photo mode writes one JPEG + one DNG per press into
  DCIM/ProCam/{Photo,RAW}/. New unit test: PhotoOutputsRoutingTest.
```

## 4 · Task: Thermal-aware auto-recovery

```text
Upgrade ThermalManager so it reacts to PowerManager.OnThermalStatusChangedListener
in real time, not just at recording start.

Files:
  app/src/main/java/com/procam/s23fe/thermal/ThermalManager.kt
  app/src/main/java/com/procam/s23fe/recorder/ProCamRecorder.kt
  app/src/main/java/com/procam/s23fe/ui/MainActivity.kt

Specification:
- Expose ThermalManager.observe(scope: CoroutineScope, onChange: (Int) -> Unit).
- While recording, if status crosses into MODERATE, display an amber HUD toast and
  reduce the live requested bitrate by stopping+restarting the recorder segment
  with the derated value. Use a "rolling MP4" trick (new file, new prepare, new
  start) to avoid losing footage — chain files PROCAM_TS_p1.mp4, _p2.mp4, etc.
- Never drop below 24 Mbps.
- When status recovers to LIGHT/NONE, keep the derated value until recording ends
  (do NOT upshift mid-take — avoids encoder re-negotiation).

Acceptance:
  ThermalRecoveryTest asserts: MODERATE → next segment is at 80%, SEVERE → 60%,
  never below 24 Mbps; no upshift recorded during a single take.
```

## 5 · Task: Real .cube artist LUTs

```text
Replace the two placeholder .cube files in app/src/main/assets/luts/ with real
artist-authored LUTs.

Inputs I will provide in chat:
  - Two .cube files as attachments or gists.

You must:
  - Validate via CubeLutParser that each file parses to size in {17, 33, 65} and
    sample count is exact.
  - If a LUT has a domain outside 0..1, confirm our parser normalises correctly.
  - Add a unit test that reads each asset at runtime via LutLibrary and checks
    size + label.

Acceptance:
  ./gradlew :app:testDebugUnitTest passes.
```

## 6 · Task: Settings screen (SharedPreferences-backed)

```text
Add a Settings screen reachable from a gear icon in the top bar.

Files:
  app/src/main/java/com/procam/s23fe/ui/SettingsActivity.kt  (new)
  app/src/main/res/layout/activity_settings.xml              (new, Material 3 cards)
  app/src/main/java/com/procam/s23fe/core/Settings.kt        (new DataStore wrapper)
  app/src/main/AndroidManifest.xml
  app/src/main/res/layout/activity_main.xml

Options to expose:
  - Default profile (FHD_HIGH / FHD_STANDARD / UHD)
  - Default EIS crop (15/20/25%)
  - Save Clean 5MP alongside JPEG (bool)
  - Show rule-of-thirds grid (bool)
  - Show horizon (bool)
  - Default LUT by id
  - Audio source preference (CAMCORDER / MIC)

Persist via androidx.datastore.preferences.
```

## 7 · Task: CI (`:app:assembleDebug`)

```text
Add GitHub Actions workflow `.github/workflows/android.yml` that:
  - Runs on push to main and on PR to main
  - Uses actions/setup-java@v4 with Temurin 17
  - Caches ~/.gradle
  - Runs `./gradlew :app:lint :app:testDebugUnitTest :app:assembleDebug`
  - Uploads app/build/outputs/apk/debug/*.apk as an artifact

No other code changes.
```

---

## Usage recipe

1. Paste **§0 Base Context** once.
2. Pick ONE task (§1 … §7) and paste it.
3. Wait for the model to return the file diff + verification command.
4. Run the verification command locally.
5. Commit.
6. Move to the next task.

---

## Why this split?

- Each task is **≤ ~500 LoC of churn** → fits comfortably in a single LLM turn.
- Hard rules in the Base Context prevent regressions on the spec's critical bits
  (60fps lock, audio enforcement, rotation math, GPU pipeline).
- Every task ships with an acceptance test → you can gate PRs mechanically.
