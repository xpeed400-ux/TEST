# 📷 ProCam S23 FE

A high-bitrate, fully-manual video camera app tailored for the Samsung Galaxy
S23 FE (Exynos 2200 · Mali-G710 · GN3 main sensor). Implements the *One-Pass
완성본 시방서* — FPS locks, stereo audio enforcement, GPU LUT pipeline, and the
S23 FE-specific tuning presets.

## Highlights

| Area | Implementation |
|---|---|
| FPS lock (60→30 collapse fix) | `camera/CameraController` forces `CONTROL_AE_TARGET_FPS_RANGE = (fps, fps)` on every repeating request |
| AE / AWB lock on record | `CameraController.onRecordingStarted()` latches both locks |
| 100 Mbps FHD / 60 Mbps UHD | `recorder/ProCamRecorder` (MediaRecorder H.264 High 5.1) |
| Stereo 48 kHz / 256 kbps AAC | Enforced in `ProCamRecorder.prepare()` |
| Orientation bug fix | `ProCamRecorder.computeOrientationHint` = `(sensorOrientation + deviceDegrees) % 360` |
| Hardware + software EIS | `CONTROL_VIDEO_STABILIZATION_MODE_ON` + configurable 15/20/25 % `SCALER_CROP_REGION` center crop |
| GPU LUT pipeline | OES texture → fragment shader → 3D LUT → preview **and** record surfaces |
| `.cube` format | `gpu/CubeLutParser` + `assets/luts/*.cube` auto-loaded by `gpu/LutLibrary` |
| Built-in LUTs | 4 procedural (Cinema Warm/Cool, Teal & Orange, Soft Film) + 2 `.cube` samples |
| S23 FE presets | `core/DevicePreset` — ISO/shutter clamps, 8 AE presets, 8 WB presets, anti-banding |
| HUD | Figma-style dark glass Material 3 overlay (left/right strips + record timer) |
| Composition aids | Rule-of-thirds grid · horizon line · luminance histogram · animated AF target |
| Thermal throttle | `thermal/ThermalManager` derates bitrate 100 → 80 → 60 % |
| Photo | JPEG + DNG + Clean-5MP downscale paths |
| Storage | `DCIM/ProCam/{Video,Photo,RAW}/` + MediaStore publishing |

## Package layout

```
com.procam.s23fe
├── camera/     CameraController, WhiteBalance
├── core/       RecordingProfile, ManualControls, DevicePreset
├── gpu/        LutEngine, LutRenderThread, LutCatalog, LutLibrary, CubeLutParser
├── photo/      PhotoCapture
├── recorder/   ProCamRecorder
├── thermal/    ThermalManager
└── ui/         MainActivity + HUD widgets
```

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Requirements: **JDK 17**, Android SDK 34.
AGP 8.5.2 · Kotlin 1.9.24 · Gradle 8.7 · Material 3 · ViewBinding
`minSdk 30`, `targetSdk 34`, `compileSdk 34`.

## Open in Android Studio

1. Unzip / clone the project → open the root folder via **File → Open…**
2. Android Studio will prompt for the SDK path on first sync (writes `local.properties`)
3. Run ▶ on a connected S23 FE (Android 13+)

## AI auto-build

See `docs/AI_BUILD_PROMPT.md` — a ready-made prompt pack that drives Gemini 2.5 / Claude
Sonnet through the remaining tasks (tap-to-focus AF regions, histogram feed, DNG
metadata, thermal auto-recovery, Settings screen, CI) one PR-sized chunk at a time.

## Developer contract (from spec §17)

- ✅ Enforce exact FPS using `CONTROL_AE_TARGET_FPS_RANGE`
- ✅ Lock AE/AWB at recording start
- ✅ Implement 100 Mbps FHD recording via MediaRecorder
- ✅ Stereo audio (48 kHz / 256 kbps AAC)
- ✅ LUT via GPU shader pipeline
- ✅ EIS using `SCALER_CROP_REGION` (15/20/25 % configurable)
- ✅ Rotation via sensor + rotation hint
