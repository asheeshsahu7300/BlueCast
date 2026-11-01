import struct
import subprocess
import sys
import time
import serial

MAGIC_BYTE_1 = b'\xAA'
MAGIC_BYTE_2_AVC = b'\x55'   # 0xAA55
MAGIC_BYTE_2_HEVC = b'\x56'  # 0xAA56

def run_receiver(port="COM4"):
    print("=" * 65)
    print(" NVC-BT Bluetooth Video Stream Receiver (PC / Laptop Display)")
    print(f" Port: {port} | Target UUID: SPP (00001101-...)")
    print(" Supports: HEVC (H.265) 60 FPS & AVC (H.264)")
    print("=" * 65)

    try:
        ser = serial.Serial(port, baudrate=921600, timeout=1)
    except Exception as e:
        print(f"[!] Failed to open {port}: {e}")
        print("    Try passing COM3 or other port: python receiver.py COM3")
        return

    print(f"[*] {port} opened successfully.")
    print("[*] Waiting for incoming NVC-BT stream from phone...")
    print("    1. Ensure phone is paired to this PC via Windows Bluetooth settings.")
    print("    2. On phone: Open app -> 'Run as Sender'.")
    print("    3. Select this PC and tap 'Start NVC-BT Stream via Bluetooth'.")
    print("-" * 65)

    ffplay = None
    active_codec = None
    frame_count = 0
    total_bytes = 0
    start_time = time.time()

    def launch_ffplay(codec_name):
        print(f"[*] Initializing FFplay hardware player for format: {codec_name.upper()}...")
        cmd = [
            "ffplay",
            "-f", codec_name,
            "-probesize", "32",
            "-analyzeduration", "0",
            "-flags", "low_delay",
            "-sync", "ext",
            "-framedrop",
            "-window_title", f"NVC-BT Screen Stream [{codec_name.upper()} @ 60 FPS]",
            "-i", "pipe:0"
        ]
        return subprocess.Popen(
            cmd,
            stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL
        )

    try:
        while True:
            # Resilient sliding-window header detection
            b1 = ser.read(1)
            if not b1 or b1 != MAGIC_BYTE_1:
                continue

            b2 = ser.read(1)
            if not b2 or (b2 != MAGIC_BYTE_2_HEVC and b2 != MAGIC_BYTE_2_AVC):
                continue

            detected_codec = "hevc" if b2 == MAGIC_BYTE_2_HEVC else "h264"

            # Launch player on first frame or if codec switches
            if ffplay is None or active_codec != detected_codec:
                if ffplay:
                    try:
                        ffplay.terminate()
                    except Exception:
                        pass
                ffplay = launch_ffplay(detected_codec)
                active_codec = detected_codec

            # Read 12-byte remainder of header: flags (2B), length (4B), pts (8B)
            header = ser.read(12)
            if len(header) < 12:
                continue

            flags, length, pts = struct.unpack(">HIq", header)

            if length <= 0 or length > 3_000_000:
                continue

            # Read exact payload
            payload = bytearray()
            while len(payload) < length:
                chunk = ser.read(length - len(payload))
                if not chunk:
                    break
                payload.extend(chunk)

            if len(payload) == length and ffplay and ffplay.stdin:
                try:
                    ffplay.stdin.write(payload)
                    ffplay.stdin.flush()
                except (BrokenPipeError, OSError):
                    print("\n[!] FFplay window closed by user.")
                    break

                frame_count += 1
                total_bytes += length
                now = time.time()
                if now - start_time >= 2.0:
                    fps = frame_count / (now - start_time)
                    kbps = (total_bytes * 8 / 1000) / (now - start_time)
                    print(f"[*] NVC-BT Stream -> {fps:.1f} FPS | {kbps:.1f} kbps | Codec: {active_codec.upper()}")
                    frame_count = 0
                    total_bytes = 0
                    start_time = now

    except KeyboardInterrupt:
        print("\n[*] Stopping receiver...")
    finally:
        ser.close()
        if ffplay and ffplay.stdin:
            try:
                ffplay.stdin.close()
            except Exception:
                pass
            ffplay.terminate()
        print("[*] Cleaned up and exited.")

if __name__ == "__main__":
    port_arg = sys.argv[1] if len(sys.argv) > 1 else "COM4"
    run_receiver(port_arg)
