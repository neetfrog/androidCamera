# RawSnap

Lightweight Camera2 app with RAW capture and manual controls

It supports:
- Photo capture (JPEG)
- RAW capture / DNG when supported by the device
- Video recording in H.264 MP4
- Manual exposure, white balance, focus, zoom and flash controls
- Grid overlays, level indicator, live histogram and timer
- Front/back camera switching
- Audio level meter while recording

## Build Requirements

- **Android Studio** Flamingo / Hedgehog or newer
- **JDK 17**
- **Android device** with Camera2 support (API 24+)

## Quick Start

1. Open the project in **Android Studio**
2. Let Gradle sync
3. Connect a physical Android device
4. Run the app

### Command-line build

```powershell
.\gradlew assembleDebug
.\gradlew assembleRelease
```

The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Current app layout

The main source is under `app/src/main/java/com/procamera/app/`.

Key modules:
- `MainActivity.kt` — permissions, edge-to-edge setup, compose entrypoint
- `viewmodel/CameraViewModel.kt` — camera lifecycle, capture, recording, UI state
- `camera/Camera2Controller.kt` — Camera2 preview, photo/raw capture, histogram
- `camera/VideoRecorder.kt` — MediaRecorder recording and save handling
- `ui/CameraScreen.kt` — viewfinder, overlays, controls and HUD

## File output

Saved media is stored in:
- JPEG / DNG: `DCIM/RawSnap/`
- Video: `Movies/RawSnap/`

## Permissions

Required runtime permissions:
- `android.permission.CAMERA`
- `android.permission.RECORD_AUDIO`
- `android.permission.WRITE_EXTERNAL_STORAGE` (API ≤ 28)
- `android.permission.READ_MEDIA_IMAGES` / `android.permission.READ_MEDIA_VIDEO` (API 33+)

## Notes

- RAW capture only works on devices with RAW sensor support
- Video uses H.264 encoder and configurable resolution
- Histogram is computed from live YUV preview frames
