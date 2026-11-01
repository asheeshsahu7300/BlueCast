# 📡 BlueCast (NVC-BT)

<p align="center">
  <img src="assets/live_stream_demo.png" alt="BlueCast Live Streaming over Bluetooth" width="340" />
</p>

<p align="center">
  <strong>Ultra-Low-Latency (<40ms) Screen & Audio Mirroring over Pure Bluetooth RFCOMM for Android.</strong>
  <br />
  <em>Zero Wi-Fi. Zero Hotspot. Zero Root. 100% Native Hardware Acceleration.</em>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_10+-3DDC84?style=flat&logo=android&logoColor=white" alt="Platform" />
  <img src="https://img.shields.io/badge/Transport-Bluetooth_RFCOMM_Classic-0082FC?style=flat&logo=bluetooth&logoColor=white" alt="Bluetooth RFCOMM" />
  <img src="https://img.shields.io/badge/Video-Hardware_HEVC_H.265-orange?style=flat" alt="HEVC H.265" />
  <img src="https://img.shields.io/badge/Audio-AAC--LC_Synchronized-blueviolet?style=flat" alt="AAC Audio" />
  <img src="https://img.shields.io/badge/Shader-OpenGL_ES_9--Tap_CAR-red?style=flat&logo=opengl&logoColor=white" alt="OpenGL ES CAR" />
  <img src="https://img.shields.io/badge/License-Apache_2.0-blue?style=flat" alt="License" />
</p>

---

## 📖 Overview

Streaming video over Bluetooth Classic has historically been considered impractical: standard Bluetooth RFCOMM throughput is limited to **~1.2–1.4 Mbps** at the application layer, and Android screens operate at high resolutions (1080p / 2400p) at 60 FPS.

**BlueCast** breaks through this constraint using an engineered combination of:
1. **Narrowband Video Compression (NVC)**: Hardware-accelerated HEVC (H.265) configured in strictly bounded Constant Bitrate mode (**750 kbps CBR**) with 1-second keyframe intervals.
2. **Reference Integrity Protection**: Resilient packet framing and resynchronization engine that completely prevents macroblock shearing and tearing during packet jitter.
3. **GPU Contrast-Adaptive Reconstruction (CAR)**: Real-time 9-tap isotropic GLES post-processing filter that reconstructs sharp text and vector icon edges on the receiver.
4. **Decoupled Asynchronous Audio**: Low-overhead AAC-LC (48 kbps) system audio capture routed over an independent ingestion worker.

---

## ⚡ Performance Benchmarks

Measured live over Bluetooth RFCOMM between **OnePlus Nord CE 5G** (Broadcaster) and **Vivo 1818** (Receiver):

| Metric | Measured Value | Theoretical Budget / Ceiling |
| :--- | :--- | :--- |
| **Transport Throughput** | **`0.3 – 0.6 Mbps`** | 1.2 Mbps Bluetooth RFCOMM limit (Leaves **35–50% headroom**) |
| **Display Framerate** | **`39.5 – 46.4 FPS`** | Dynamic 60 FPS target |
| **End-to-End Latency** | **`< 40 ms`** | Sub-perceptual responsiveness for UI navigation |
| **Video Resolution** | **`720 × 1600`** *(9:20 ratio)* | Integer macroblock alignment ($45 \times 100$ MBs) |
| **Target Density** | **`320 DPI (xhdpi)`** | Exact canonical Android 360dp viewport scaling |
| **Video Frame Loss** | **`0%` tearing** | Reference chain preserved via clean IDR gating |
| **Audio Latency** | **Synchronous** | Monotonic sample-based PTS pacing |

---

## 🏗️ System Architecture

```
                    ┌────────────────────────────────────────────────────────┐
                    │               BROADCASTER (Phone A)                    │
                    └────────────────────────────────────────────────────────┘
                                                 │
          ┌──────────────────────────────────────┴──────────────────────────────────────┐
          ▼                                                                             ▼
┌───────────────────────────────┐                                             ┌───────────────────────────────┐
│     MediaProjection API       │                                             │   AudioPlaybackCapture API    │
│  (720x1600 @ 320 DPI Surface) │                                             │      (16-bit PCM @ 48kHz)     │
└──────────────┬────────────────┘                                             └──────────────┬────────────────┘
               │                                                                             │
               ▼                                                                             ▼
┌───────────────────────────────┐                                             ┌───────────────────────────────┐
│     Hardware HEVC Encoder     │                                             │     Hardware AAC Encoder      │
│  (OMX.qcom / c2.android)      │                                             │    (c2.android.aac.encoder)   │
│   • CBR 750 kbps @ 60 FPS     │                                             │   • 48 kbps AAC-LC            │
│   • 1.0s Keyframe Interval    │                                             │   • Sample-based PTS sync     │
└──────────────┬────────────────┘                                             └──────────────┬────────────────┘
               │                                                                             │
               └──────────────────────────────┬──────────────────────────────────────────────┘
                                              │
                                              ▼
                              ┌───────────────────────────────┐
                              │  Low-Latency Packet Muxer     │
                              │  • Magic framing (0xAA56/57)  │
                              │  • Bounded queue (16 packets) │
                              │  • Immediate socket flushing  │
                              └───────────────┬───────────────┘
                                              │
══════════════════════════════════════════════╪══════════════════════════════════════════════════
                                              │ Bluetooth RFCOMM (~1.2 Mbps PHY)
══════════════════════════════════════════════╪══════════════════════════════════════════════════
                                              │
                    ┌─────────────────────────┴──────────────────────────────┐
                    │                 RECEIVER (Phone B)                     │
                    └────────────────────────────────────────────────────────┘
                                              │
                              ┌───────────────▼───────────────┐
                              │ Non-Blocking Socket Ingest    │
                              │ • Sliding-window byte syncer  │
                              │ • Length bounds validation    │
                              └───────────────┬───────────────┘
                                              │
          ┌───────────────────────────────────┴───────────────────────────────────┐
          ▼                                                                       ▼
┌───────────────────────────────────┐                                   ┌───────────────────────────────────┐
│     Video Processing Pipeline     │                                   │     Audio Processing Pipeline     │
│  • Bounded queue (16 frames)      │                                   │  • Bounded queue (32 packets)     │
│  • Reference Integrity Gate:      │                                   │  • Dedicated playback thread      │
│    Drops P-frames during jitter,  │                                   │  • Non-blocking AudioTrack writes │
│    waits for IDR (NAL 16..21)     │                                   │                                   │
└─────────────────┬─────────────────┘                                   └─────────────────┬─────────────────┘
                  │                                                                       │
                  ▼                                                                       ▼
┌───────────────────────────────────┐                                   ┌───────────────────────────────────┐
│    Hardware MediaCodec Decoder    │                                   │       AudioTrack (STREAM_MUSIC)   │
│  (Renders direct to GLES Surface) │                                   │        (48 kHz Stereo Output)     │
└─────────────────┬─────────────────┘                                   └───────────────────────────────────┘
                  │
                  ▼
┌───────────────────────────────────┐
│  GPU CAR Reconstruction Shader    │
│  • 9-Tap isotropic edge filter    │
│  • Local contrast detection       │
│  • Clamped anti-halo boundaries   │
└─────────────────┬─────────────────┘
                  │
                  ▼
┌───────────────────────────────────┐
│     GLSurfaceView Presentation    │
│     (Fullscreen display render)   │
└───────────────────────────────────┘
```

---

## 🔬 Core Innovations

### 1. Reference Integrity Protection (Zero-Tearing Engine)
In HEVC compression, inter-predicted P-frames reference previously reconstructed macroblocks. In unconstrained networks, dropping a single P-frame causes the decoder to apply motion vectors against the wrong base picture—producing horizontal shearing, sliced icons, and visual artifacts.

BlueCast implements **Strict Reference Gating**:
- The queues never evict single random P-frames.
- If RF congestion is detected, all pending video packets are purged and the pipeline enters `SYNCING` state.
- Intermediate P-frames are cleanly discarded before reaching the decoder.
- When an IDR keyframe arrives (`NAL 16..21`), the recovery gate unlocks (`LOCKED` state) and feeding resumes against a pristine intra-coded frame.

### 2. Contrast-Adaptive Edge Reconstruction (CAR)
Rather than simple sharpening (which accentuates low-frequency compression blocks in dark gradients), BlueCast employs an **isotropic 9-tap CAR shader**:
```glsl
// Local luma contrast modulation
float localContrast = maxL - minL;
float edgeWeight = smoothstep(0.06, 0.24, localContrast);
float sharpFactor = uSharpness * edgeWeight;

vec3 sharp = c + detail * sharpFactor;

// Strict bounding prevents overshoot halos on text
vec3 minRgb = min(c, min(min(n, s), min(w, e))) - 0.06;
vec3 maxRgb = max(c, max(max(n, s), max(w, e))) + 0.06;
gl_FragColor = vec4(clamp(sharp, max(vec3(0.0), minRgb), min(vec3(1.0), maxRgb)), center.a);
```
- **Text & UI Icons**: Contrast exceeds `0.24`, triggering full reconstruction sharpening.
- **Flat & Dark Gradients**: Contrast remains below `0.06`, preventing noise and macroblock grid amplification.

---

## 📦 Packet Framing Specification

Each transmission payload is wrapped with a 16-byte fixed-size binary header:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|       Magic Header (2B)       |          Flags (2B)           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                       Payload Length (4B)                     |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                   Presentation Timestamp (8B)                 +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        Payload Data ...                       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

* **Magic Headers**:
  - `0xAA56` — HEVC (H.265) Video Stream
  - `0xAA55` — AVC (H.264) Video Stream
  - `0xAA57` — AAC-LC Audio Stream
* **Flags**: Encodes `MediaCodec.BUFFER_FLAG_KEY_FRAME` (`0x01`) and `BUFFER_FLAG_CODEC_CONFIG` (`0x02`).
* **Timestamp**: Microsecond-accurate presentation timestamp (`ptsUs`).

---

## 🚀 Getting Started

### Prerequisites
- Two Android devices (Android 10+ recommended for Broadcaster; Android 8.0+ for Receiver).
- Android SDK installed with `adb`.

### 1. Build the APK
```bash
git clone https://github.com/asheeshsahu7300/BlueCast.git
cd BlueCast
./gradlew assembleDebug
```

### 2. Install on Both Devices
```bash
# Install to Receiver
adb -s <RECEIVER_DEVICE_ID> install -r app/build/outputs/apk/debug/app-debug.apk

# Install to Broadcaster
adb -s <BROADCASTER_DEVICE_ID> install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Connection Setup
1. **Bluetooth Pairing**: Open Android Settings on both phones and pair them.
2. **Start Receiver**:
   - Open **BlueCast** on Phone B.
   - Tap **"Run as Receiver (Phone B - Display Screen)"**.
3. **Start Broadcaster**:
   - Open **BlueCast** on Phone A.
   - Tap **"Run as Sender (Phone A - Screen Capture)"**.
   - Select Phone B from the paired devices list.
   - Tap **"Start NVC-BT Stream via Bluetooth"**.
   - Confirm the Android system screen record dialog.

---

## 🛠️ Codebase Structure

```
app/src/main/java/com/example/btscreenstream/
├── BtConstants.kt              # Bitrate, resolution, and packet framing definitions
├── MainActivity.kt             # Mode selector (Sender vs Receiver) & permissions
├── SenderActivity.kt           # Bluetooth device discovery & profile selection UI
├── MediaProjectionService.kt   # Foreground capture service, HEVC/AAC encoders & muxer
└── ReceiverActivity.kt         # RFCOMM ingestion, MediaCodec decoder & CAR GLES renderer
```

---

## 📄 License

Licensed under the **Apache License, Version 2.0**. See [LICENSE](LICENSE) for details.
