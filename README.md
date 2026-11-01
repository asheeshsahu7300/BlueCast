# BlueCast (NVC-BT)

**Ultra-Low-Latency Screen & Audio Mirroring over Pure Bluetooth RFCOMM for Android.**

BlueCast streams real-time Android screen capture and system audio between two Android devices entirely over Bluetooth RFCOMM without requiring Wi-Fi, hotspot, cellular data, or root permissions.

---

## ⚡ Key Highlights

- **Pure Bluetooth RFCOMM Transport**: Operates over standard Bluetooth Classic (RFCOMM/L2CAP), fitting entirely inside sustained Bluetooth throughput limits (~1.2 Mbps).
- **Sub-40ms End-to-End Latency**: Hardware-accelerated encoding and decoding pipeline eliminates buffer bloat.
- **Hardware HEVC (H.265) CBR**: Strictly bounded Constant Bitrate (~750 kbps) with 1-second GOP interval prevents congestion drops during fast screen motion.
- **Reference Integrity Protection**: Zero-tearing synchronization engine. Intermediate P-frames are never evicted randomly; if congestion occurs, the receiver cleanly syncs on the next pristine IDR keyframe (`NAL 16..21`).
- **GPU Contrast-Adaptive Reconstruction (CAR)**: Custom 9-tap isotropic GLES fragment shader dynamically enhances vector text and launcher icons without edge halos or smearing.
- **Synchronized AAC Audio**: Real-time system audio capture via `AudioPlaybackCapture` multiplexed alongside video frames.
- **Wi-Fi High-Speed Mode (Optional)**: Built-in UDP auto-discovery and TCP fallback for unconstrained 1080p @ 60 FPS streaming when on the same network.

---

## 🏗️ Architecture

```
Phone A (Broadcaster)                     Phone B (Receiver)
┌───────────────────────┐                 ┌────────────────────────┐
│ MediaProjection API   │                 │ Bluetooth RFCOMM Ingest│
│        │              │                 │          │             │
│ Hardware HEVC Encoder │                 │   Bounded Queue (16)   │
│  (CBR 750 kbps, 60fps)│                 │          │             │
│        │              │                 │ Hardware HEVC Decoder  │
│ AudioPlaybackCapture  │                 │    (SurfaceTexture)    │
│   (AAC-LC 48 kbps)    │                 │          │             │
│        │              │                 │  GLES 9-Tap CAR Shader │
│ Low-Latency Muxer     │                 │   (Isotropic Sharpen)  │
│        │              │                 │          │             │
│ Bluetooth RFCOMM Out  │ ══════════════> │  GLSurfaceView Display │
└───────────────────────┘  Bluetooth PHY  └────────────────────────┘
```

---

## 📱 Device Requirements

- **Sender**: Android 10+ (API 29+) with screen capture permission. Android 14+ supported.
- **Receiver**: Android 8.0+ (API 26+) with OpenGL ES 2.0+ and hardware HEVC decoding.
- **Bluetooth**: Bluetooth 4.2+ (EDR/LE). Devices must be paired in system Bluetooth settings prior to connection.

---

## 🚀 Building & Running

### 1. Build the APK
```bash
./gradlew assembleDebug
```
The resulting APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### 2. Install on Both Devices
```bash
adb -s <DEVICE_A_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <DEVICE_B_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Usage
1. Pair both devices in Android Settings -> Bluetooth.
2. On the **Receiver phone**: Open BlueCast -> Tap **"Run as Receiver"**.
3. On the **Broadcaster phone**: Open BlueCast -> Tap **"Run as Sender"**.
4. Select the paired Receiver phone from the list -> Tap **"Start NVC-BT Stream via Bluetooth"** -> Accept the screen record prompt.

---

## 📄 License
Apache License 2.0. See LICENSE for details.
