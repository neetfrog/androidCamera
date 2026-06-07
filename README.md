# ProCamera — Fully Featured Android Camera App

Professional Android camera app built with **Camera2 API** + **Jetpack Compose**.

## Features

| Category        | Capability                                                    |
|-----------------|---------------------------------------------------------------|
| **Photo**       | JPEG capture + RAW / DNG capture                              |
| **Video**       | H.264, up to 4K, configurable bitrate                        |
| **LOG Video**   | S-Log3-inspired flat tonemap curve for maximum dynamic range  |
| **Slow Motion** | 120 fps (device-dependent)                                    |
| **Manual**      | ISO 100–12800, shutter 1/4000–4s, WB 2000–10000 K, MF        |
| **Overlays**    | Rule-of-thirds / golden ratio / 1:1 grid, level indicator     |
| **Histogram**   | Live RGB + luma histogram                                     |
| **Audio Meter** | PPM-style stereo level meter during recording                 |
| **Zoom**        | Smooth pinch-to-zoom; 1×/2×/4× quick presets                 |
| **UI**          | Full edge-to-edge Material 3, dark theme, Compose             |

## Build Requirements

- **Android Studio** Hedgehog (2023.1.1) or newer
- **JDK 17**
- **Android device** with Camera2 FULL/LEVEL_3 hardware (API 24+)

## Quick Start

1. Open the project root folder in **Android Studio**
2. Let Gradle sync (it will download the wrapper JAR automatically)
3. Connect a physical Android device — emulators lack Camera2 RAW support
4. Press **Run** (▶)

### Command-line build (after Android Studio has initialised the wrapper)

```bash
# Debug APK
./gradlew assembleDebug

# Release APK  (set up signing first in app/build.gradle.kts)
./gradlew assembleRelease
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

## Project Structure

```
app/src/main/java/com/procamera/app/
├── MainActivity.kt                 — Entry point, permission handling
├── data/
│   └── CameraState.kt             — All data classes & enums
├── camera/
│   ├── Camera2Controller.kt       — Full Camera2 session management
│   ├── VideoRecorder.kt           — MediaRecorder wrapper
│   └── OrientationSensor.kt       — Rotation vector → pitch/roll
├── viewmodel/
│   └── CameraViewModel.kt         — State machine, lifecycle coordination
└── ui/
    ├── CameraScreen.kt            — Main composable screen
    ├── PermissionScreen.kt        — Permission rationale screen
    ├── theme/                     — Material 3 dark theme
    └── components/
        ├── ViewfinderSurface.kt   — TextureView + pinch zoom + double-tap flip
        ├── LevelIndicator.kt      — Artificial horizon (Canvas)
        ├── Histogram.kt           — RGB/luma histogram (Canvas)
        ├── GridOverlay.kt         — Thirds / golden / square grids (Canvas)
        ├── ManualControls.kt      — Full side panel: ISO, SS, WB, focus, fps
        ├── AudioLevelMeter.kt     — PPM VU meters
        ├── TopControls.kt         — Flash, timer, grid, histogram, level, peaking
        └── BottomControls.kt      — Mode selector, capture button, zoom, flip
```

## LOG Color Space

The LOG video profile applies a custom `TonemapCurve` to the Camera2 capture request, lifting blacks and compressing highlights — similar to S-Log3 — to preserve dynamic range in post-production. Grading in editing software will reveal the full tonal range.

Enable LOG by selecting the **LOG** capture mode in the mode selector.

## RAW / DNG

RAW capture uses `ImageFormat.RAW_SENSOR` and `DngCreator` (Android standard). The resulting `.dng` file contains full sensor data and is compatible with Adobe Lightroom, Capture One, RawTherapee, etc.

## Saved Files

| Type  | Location                                        |
|-------|-------------------------------------------------|
| JPEG  | `<External>/Android/data/com.procamera.app/files/DCIM/ProCamera/` |
| DNG   | same as JPEG                                    |
| Video | `<External>/Android/data/com.procamera.app/files/Movies/ProCamera/` |

## Permissions

| Permission            | Purpose                  |
|-----------------------|--------------------------|
| `CAMERA`              | All camera operations    |
| `RECORD_AUDIO`        | Video audio track        |
| `WRITE_EXTERNAL_STORAGE` | Saving on API ≤ 28    |
| `READ_MEDIA_IMAGES/VIDEO` | Gallery on API 33+  |

## Notes

- RAW capture requires a device with `REQUEST_AVAILABLE_CAPABILITIES_RAW` support
- Slow-motion (120 fps) availability depends on device hardware
- 4K video requires sufficient storage speed; use UHS-II or internal storage
