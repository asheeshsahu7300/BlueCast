package com.example.btscreenstream

import java.util.UUID

object BtConstants {
    val APP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    // Packet Framing Magic Headers (16-bit)
    const val MAGIC_HEADER_AVC: Short = 0xAA55.toShort()
    const val MAGIC_HEADER_HEVC: Short = 0xAA56.toShort()
    const val MAGIC_HEADER_AUDIO_AAC: Short = 0xAA57.toShort()

    // Byte-level markers for resilient sliding-window packet synchronization
    const val MAGIC_BYTE_1: Byte = 0xAA.toByte()
    const val MAGIC_BYTE_2_AVC: Byte = 0x55.toByte()
    const val MAGIC_BYTE_2_HEVC: Byte = 0x56.toByte()
    const val MAGIC_BYTE_2_AUDIO_AAC: Byte = 0x57.toByte()

    // Wi-Fi High-Speed Network Ports
    const val TCP_PORT = 8888
    const val UDP_DISCOVERY_PORT = 8889

    // --- NVC-BT Profiles for Bluetooth (HEVC + Intra-Refresh + Super-Resolution Reconstruction) ---

    // Profile 1: Balanced NVC 60 FPS (Default / Recommended) - 2.25x sharper than 384p
    // Macroblock-aligned (multiple of 16), exact 19.5:9 aspect ratio (576 / 1248 = 0.4615)
    const val BT_BALANCED_WIDTH = 576
    const val BT_BALANCED_HEIGHT = 1248
    const val BT_BALANCED_DPI = 320 // xhdpi native density for crisp vector text
    const val BT_BALANCED_BITRATE = 650_000 // 650 kbps calibrated ingestion

    // Profile 2: Ultra NVC 720p (Max Spatial Detail) - 3.5x sharper
    const val BT_ULTRA_WIDTH = 720
    const val BT_ULTRA_HEIGHT = 1568
    const val BT_ULTRA_DPI = 360
    const val BT_ULTRA_BITRATE = 720_000 // 720 kbps calibrated ingestion

    // Profile 3: Compact NVC (Low Bandwidth fallback)
    const val BT_COMPACT_WIDTH = 384
    const val BT_COMPACT_HEIGHT = 832
    const val BT_COMPACT_DPI = 240
    const val BT_COMPACT_BITRATE = 500_000 // 500 kbps calibrated ingestion

    // Profile 4: Ultra Sharp NVC (Matches Phone A 1080x2400 aspect ratio 9:20, 320 DPI for integer font rendering)
    const val BT_4K_WIDTH = 1600
    const val BT_4K_HEIGHT = 720
    const val BT_4K_PORTRAIT_WIDTH = 720
    const val BT_4K_PORTRAIT_HEIGHT = 1600
    const val BT_4K_DPI = 320 // 480 / 1.5 = 320 DPI (xhdpi exact canonical Android viewport)
    const val BT_4K_BITRATE = 750_000 // 750 kbps CBR: optimal balance of high-motion clarity and 35% Bluetooth headroom

    // Audio Bitrate
    const val BT_AUDIO_BITRATE = 48_000 // 48 kbps AAC-LC (~6 KB/s, leaves 94% bandwidth for video)

    // Defaults for standard NVC Bluetooth stream
    const val BT_DEFAULT_WIDTH = BT_4K_PORTRAIT_WIDTH
    const val BT_DEFAULT_HEIGHT = BT_4K_PORTRAIT_HEIGHT
    const val BT_DEFAULT_DPI = BT_4K_DPI
    const val BT_DEFAULT_BITRATE = BT_4K_BITRATE
    const val BT_VIDEO_FPS = 60
    const val BT_IFRAME_INTERVAL = 1 // 1s GOP: instant recovery, zero reference drift, clean P-frames

    // --- High-Speed Wi-Fi Profile (Unconstrained 1080p @ 60 FPS) ---
    const val WIFI_VIDEO_WIDTH = 1080
    const val WIFI_VIDEO_HEIGHT = 1920
    const val WIFI_VIDEO_DPI = 320
    const val WIFI_VIDEO_BITRATE = 8_000_000 // 8.0 Mbps
    const val WIFI_VIDEO_FPS = 60
    const val WIFI_IFRAME_INTERVAL = 1
}
