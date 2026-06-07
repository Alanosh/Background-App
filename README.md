# VideoBackgroundRemoval (VBR)

Personal Android app for offline AI background removal and replacement.

## Hardware Target
- **Device**: Mali-G57 GPU, 2.7GB RAM
- **Android**: 10–14 (API 29–34)
- **Processing**: 720p internal, 1080p export upscale

## Setup

### 1. MediaPipe Model
Download the selfie segmentation model and place at:
```
app/src/main/assets/mediapipe/selfie_segmentation.tflite
```
Download from:
https://storage.googleapis.com/mediapipe-models/image_segmenter/selfie_segmenter/float16/latest/selfie_segmenter.tflite

### 2. Build
```bash
./gradlew assembleDebug
```

### 3. Install
```bash
adb install app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

## Architecture

```
core/pipeline      — FramePipeline, FramePool (GPU thread sacred)
core/segmentation  — MediaPipe wrapper, mask post-processing
core/compositing   — Compositor, BackgroundRenderer, EffectsProcessor
core/selection     — Lasso, MagneticLasso, Brush tools, UndoStack
core/adjustment    — Brightness/contrast/saturation/hue/sharpness (ColorMatrix)
media/             — VideoDecoder, VideoEncoder, BackgroundFrameCache, ExportManager
ui/                — Screens, Components, EditorViewModel
util/              — MemoryGuard, MediaUtils
```

## Thread Map

| Thread        | Responsibility                              |
|---------------|---------------------------------------------|
| Main          | UI only — never waits on anything           |
| GPU           | FramePipeline compositing — sacred          |
| Decode (IO)   | VideoDecoder — pushes frames into FramePool |
| Cache (IO)    | BackgroundFrameCache pre-decode             |
| Export (IO)   | ExportManager — fully independent           |

## Memory Budget (2.7GB device)

| Allocation              | Size     |
|-------------------------|----------|
| OS + Android overhead   | ~1.2GB   |
| App budget              | ~1.5GB   |
| FramePool (3x 720p)     | ~9.3MB   |
| Segmentation model      | ~2MB     |
| BG frame cache (100fr)  | ~22.5MB  |
| UndoStack (20x ALPHA_8) | ~18MB    |
| **Total tracked**       | ~52MB    |

## Features
- AI background removal (MediaPipe Selfie Segmentation, fully offline)
- Lasso, Magnetic Lasso, Brush selection refinement tools
- Background: image, video (pre-cached), solid color, blur
- Subject glow effect with intensity control
- Independent subject/background color grading
- Brightness, contrast, saturation, hue, sharpness sliders
- Edge feather slider (eliminates fringe)
- Subject horizontal flip
- Playback speed: 0.5x / 1x / 2x
- Video trim (remux, no re-encode)
- Video split at multiple points
- Export at 480p / 720p / 1080p with size estimate
- Audio preserved without re-encoding
- Works on Android 10–14
