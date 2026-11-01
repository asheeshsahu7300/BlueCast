package com.example.btscreenstream

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class MediaProjectionService : Service() {

    private val TAG = "BtScreenStream"
    private var mediaProjection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var btSocket: BluetoothSocket? = null
    private var tcpSocket: Socket? = null
    private var audioRecord: AudioRecord? = null
    private var audioEncoder: MediaCodec? = null
    @Volatile
    private var isAudioStreaming = false
    private var isStreaming = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private data class FramePacket(
        val magic: Short,
        val flags: Int,
        val pts: Long,
        val payload: ByteArray
    )

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasMicPerm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                checkSelfPermission(android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }

            val type = if (hasMicPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }

            try {
                startForeground(101, notification, type)
            } catch (e: Exception) {
                Log.w(TAG, "startForeground with combined type failed: ${e.message}, retrying with mediaProjection only")
                try {
                    startForeground(101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                } catch (e2: Exception) {
                    Log.e(TAG, "startForeground with mediaProjection failed: ${e2.message}", e2)
                }
            }
        } else {
            startForeground(101, notification)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = createNotification("Initializing NVC-BT streaming engine...")
        startForegroundCompat(notification)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification("Initializing NVC-BT streaming engine...")
        startForegroundCompat(notification)

        if (isStreaming) {
            isStreaming = false
            isAudioStreaming = false
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
                audioEncoder?.stop()
                audioEncoder?.release()
                audioEncoder = null
                encoder?.stop()
                encoder?.release()
                encoder = null
                virtualDisplay?.release()
                virtualDisplay = null
                btSocket?.close()
                tcpSocket?.close()
            } catch (_: Exception) {}
        }

        val resultCode = intent?.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED

        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DATA", Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DATA") as? Intent
        }

        val targetIp = intent?.getStringExtra("TARGET_IP")

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra("DEVICE", BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra("DEVICE") as? BluetoothDevice
        }

        if (resultCode != Activity.RESULT_OK || resultData == null || (targetIp == null && device == null)) {
            Log.e(TAG, "Invalid parameters: resultCode=$resultCode, data=$resultData, ip=$targetIp, device=$device")
            showToast("Failed to start: missing parameters")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val reqWidth = intent?.getIntExtra("VIDEO_WIDTH", 0) ?: 0
        val reqHeight = intent?.getIntExtra("VIDEO_HEIGHT", 0) ?: 0
        val reqDpi = intent?.getIntExtra("VIDEO_DPI", 0) ?: 0
        val reqBitrate = intent?.getIntExtra("VIDEO_BITRATE", 0) ?: 0

        // In Android 14+, acquire MediaProjection immediately on main thread while foreground service token is fresh
        val mpManager = getSystemService(MediaProjectionManager::class.java)
        val mp = try {
            mpManager.getMediaProjection(resultCode, resultData)
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}", e)
            showToast("Screen capture permission error: ${e.message}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        mediaProjection = mp

        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection onStop called")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }, mainHandler)

        val isBluetooth = (device != null)
        updateNotification(if (isBluetooth) "Connecting via Bluetooth to ${device?.name}..." else "Connecting via Wi-Fi to $targetIp...")

        Thread {
            try {
                val outputStream: OutputStream
                if (isBluetooth) {
                    Log.d(TAG, "Connecting to Bluetooth device: ${device?.name}...")
                    val s = connectBluetoothSocket(device!!)
                    btSocket = s
                    outputStream = s.outputStream
                    Log.d(TAG, "Bluetooth connected successfully!")
                    showToast("Connected via Bluetooth! Starting NVC-BT 60 FPS Engine...")
                    updateNotification("NVC-BT 60 FPS active -> ${device.name}")
                } else {
                    Log.d(TAG, "Connecting via TCP to $targetIp:${BtConstants.TCP_PORT}...")
                    val s = Socket()
                    tcpSocket = s
                    s.tcpNoDelay = true
                    s.sendBufferSize = 512 * 1024
                    s.connect(InetSocketAddress(targetIp, BtConstants.TCP_PORT), 5000)
                    outputStream = s.getOutputStream()
                    Log.d(TAG, "Connected via Wi-Fi TCP successfully!")
                    showToast("Connected via Wi-Fi! Streaming 1080p @ 60fps...")
                    updateNotification("Wi-Fi 1080p 60fps active -> $targetIp")
                }

                startNvcBtEncodingAndSending(outputStream, isBluetooth, reqWidth, reqHeight, reqDpi, reqBitrate)
            } catch (e: Exception) {
                Log.e(TAG, "Connection or stream error: ${e.message}", e)
                showToast("Connection failed: ${e.message}")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun connectBluetoothSocket(device: BluetoothDevice): BluetoothSocket {
        try {
            val s = device.createInsecureRfcommSocketToServiceRecord(BtConstants.APP_UUID)
            s.connect()
            return s
        } catch (_: Exception) {}

        try {
            val s = device.createRfcommSocketToServiceRecord(BtConstants.APP_UUID)
            s.connect()
            return s
        } catch (_: Exception) {}

        val m = device.javaClass.getMethod("createInsecureRfcommSocket", Int::class.javaPrimitiveType)
        val s = m.invoke(device, 1) as BluetoothSocket
        s.connect()
        return s
    }

    private fun startNvcBtEncodingAndSending(
        rawOutputStream: OutputStream,
        isBluetooth: Boolean,
        reqWidth: Int = 0,
        reqHeight: Int = 0,
        reqDpi: Int = 0,
        reqBitrate: Int = 0
    ) {
        val currentMp = mediaProjection ?: return

        var actualWidth = if (isBluetooth) {
            if (reqWidth > 0) reqWidth else BtConstants.BT_DEFAULT_WIDTH
        } else BtConstants.WIFI_VIDEO_WIDTH

        var actualHeight = if (isBluetooth) {
            if (reqHeight > 0) reqHeight else BtConstants.BT_DEFAULT_HEIGHT
        } else BtConstants.WIFI_VIDEO_HEIGHT

        val dpi = if (isBluetooth) {
            if (reqDpi > 0) reqDpi else BtConstants.BT_DEFAULT_DPI
        } else BtConstants.WIFI_VIDEO_DPI

        val bitrate = if (isBluetooth) {
            if (reqBitrate > 0) reqBitrate else BtConstants.BT_DEFAULT_BITRATE
        } else BtConstants.WIFI_VIDEO_BITRATE

        val fps = if (isBluetooth) {
            if (actualWidth >= 3840 || actualHeight >= 3840) 30 else BtConstants.BT_VIDEO_FPS
        } else BtConstants.WIFI_VIDEO_FPS

        var selectedMime = MediaFormat.MIMETYPE_VIDEO_HEVC
        var magicHeader = BtConstants.MAGIC_HEADER_HEVC

        try {
            isStreaming = true

            // Configure Video Format (Defaulting to HEVC / H.265 for NVC-BT)
            val format = MediaFormat.createVideoFormat(selectedMime, actualWidth, actualHeight).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, BtConstants.BT_IFRAME_INTERVAL)

                try {
                    setInteger(MediaFormat.KEY_PRIORITY, 0) // Realtime lowest-latency priority
                } catch (_: Exception) {}

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                    } catch (_: Exception) {}
                }

                // Frame pacing heartbeat: fires if no new frame within 50ms (idle 20 FPS floor)
                // Prevents spamming duplicate frames over Bluetooth, keeping buffers completely empty for zero lag
                try {
                    setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 50_000L)
                } catch (_: Exception) {}

                // Constant Bitrate (CBR) mode: strictly enforces 600 kbps ceiling,
                // preventing high-bandwidth bursts during screen swipes from overflowing the Bluetooth socket
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                } catch (_: Exception) {
                    try {
                        setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    } catch (_: Exception) {}
                }

                try {
                    setInteger(MediaFormat.KEY_COMPLEXITY, 0)
                } catch (_: Exception) {}

                // Let hardware CBR rate controller smoothly adapt QP across motion without bursting
                // This guarantees the 600 kbps envelope is strictly respected even during fast screen swipes.
            }

            // Create Encoder: Try HEVC at requested dimensions; test start() inside safe block
            var inputSurface: android.view.Surface? = null
            val enc = try {
                Log.d(TAG, "Creating HEVC encoder: ${actualWidth}x${actualHeight} @ ${fps}fps (${bitrate / 1_000_000.0} Mbps)...")
                val hevcEnc = MediaCodec.createEncoderByType(selectedMime)
                hevcEnc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = hevcEnc.createInputSurface()
                hevcEnc.start()
                hevcEnc
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start 4K HEVC encoder (${actualWidth}x${actualHeight}): ${e.message}. Falling back to 1920x1080...")
                actualWidth = 1920
                actualHeight = 1080
                format.setInteger(MediaFormat.KEY_WIDTH, actualWidth)
                format.setInteger(MediaFormat.KEY_HEIGHT, actualHeight)
                format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                val fallbackEnc = MediaCodec.createEncoderByType(selectedMime)
                fallbackEnc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                inputSurface = fallbackEnc.createInputSurface()
                fallbackEnc.start()
                fallbackEnc
            }

            encoder = enc
            val surface = inputSurface ?: throw IllegalStateException("Failed to create input surface")

            virtualDisplay = currentMp.createVirtualDisplay(
                "NvcBtMirrorDisplay",
                actualWidth,
                actualHeight,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface, null, null
            )

            // Ultra-Low-Latency Jitter Buffer: capacity 16 buffers audio + video (<260ms ceiling, absorbs momentary RF retry)
            val sendQueue = ArrayBlockingQueue<FramePacket>(16)

            val transmissionThread = Thread {
                val bufferedOut = java.io.BufferedOutputStream(rawOutputStream, 65536)
                val headerBytes = ByteArray(16)
                val headerBuffer = ByteBuffer.wrap(headerBytes)
                try {
                    while (isStreaming) {
                        val packet = sendQueue.poll(50, TimeUnit.MILLISECONDS) ?: continue

                        headerBuffer.clear()
                        headerBuffer.putShort(packet.magic)
                        headerBuffer.putShort(packet.flags.toShort())
                        headerBuffer.putInt(packet.payload.size)
                        headerBuffer.putLong(packet.pts)

                        bufferedOut.write(headerBytes, 0, 16)
                        bufferedOut.write(packet.payload)
                        // Flush each packet immediately so Bluetooth RFCOMM transmits without user-space buffer bloat
                        bufferedOut.flush()
                    }
                    bufferedOut.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Transmission worker socket error: ${e.message}")
                    isStreaming = false
                }
            }
            transmissionThread.start()

            // Start Audio Capture and AAC Encoding (routes system/YouTube/game audio over Bluetooth)
            startAudioCaptureAndSending(currentMp, sendQueue)

            val bufferInfo = MediaCodec.BufferInfo()
            var sentFrames = 0
            var droppedFrames = 0
            var lastMetricsTime = System.currentTimeMillis()
            var waitingForSyncFrame = false
            var lastSyncRequestTime = 0L
            var latestCodecConfig: FramePacket? = null

            // Non-blocking encoder dequeue loop
            while (isStreaming) {
                val outIndex = try {
                    enc.dequeueOutputBuffer(bufferInfo, 10_000)
                } catch (e: IllegalStateException) {
                    break
                }
                if (outIndex >= 0) {
                    val outBuffer = enc.getOutputBuffer(outIndex)
                    if (outBuffer != null && bufferInfo.size > 0) {
                        outBuffer.position(bufferInfo.offset)
                        outBuffer.limit(bufferInfo.offset + bufferInfo.size)

                        val payload = ByteArray(bufferInfo.size)
                        outBuffer.get(payload)

                        val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                        val isKey = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        val packet = FramePacket(magicHeader, bufferInfo.flags, bufferInfo.presentationTimeUs, payload)

                        if (isConfig) {
                            latestCodecConfig = packet
                            while (!sendQueue.offer(packet, 10, TimeUnit.MILLISECONDS)) {
                                val stale = sendQueue.poll()
                                if (stale != null && stale.magic != BtConstants.MAGIC_HEADER_AUDIO_AAC) droppedFrames++
                            }
                        } else if (!isBluetooth) {
                            sendQueue.offer(packet)
                            sentFrames++
                        } else {
                            if (waitingForSyncFrame) {
                                if (isKey) {
                                    waitingForSyncFrame = false
                                    latestCodecConfig?.let { sendQueue.offer(it) }
                                    sendQueue.offer(packet)
                                    sentFrames++
                                    Log.d(TAG, "NVC-BT: Clean IDR keyframe sent, reference integrity restored")
                                } else {
                                    // Discard intermediate P-frame to prevent sending corrupted reference
                                    droppedFrames++
                                }
                            } else if (isKey) {
                                latestCodecConfig?.let { sendQueue.offer(it) }
                                sendQueue.offer(packet)
                                sentFrames++
                            } else {
                                // P-frame: only queue if capacity exists; if full, enter safe resync (never send broken chain!)
                                if (sendQueue.remainingCapacity() > 0) {
                                    sendQueue.offer(packet)
                                    sentFrames++
                                } else {
                                    // Queue full: channel congested. Purge video and wait for clean IDR!
                                    val audioPackets = sendQueue.filter { it.magic == BtConstants.MAGIC_HEADER_AUDIO_AAC }
                                    sendQueue.clear()
                                    audioPackets.forEach { sendQueue.offer(it) }
                                    waitingForSyncFrame = true
                                    droppedFrames++
                                    try {
                                        enc.setParameters(Bundle().apply {
                                            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                                        })
                                    } catch (_: Exception) {}
                                }
                            }
                        }

                        // Telemetry reporting
                        val now = System.currentTimeMillis()
                        if (now - lastMetricsTime >= 3000) {
                            val elapsedSec = (now - lastMetricsTime) / 1000.0
                            val fpsActual = sentFrames / elapsedSec
                            Log.d(TAG, "NVC-BT Status: codec=$selectedMime, FPS=${String.format("%.1f", fpsActual)}, queued=${sendQueue.size}, dropped=$droppedFrames, waitingSync=$waitingForSyncFrame")
                            sentFrames = 0
                            droppedFrames = 0
                            lastMetricsTime = now
                        }
                    }
                    enc.releaseOutputBuffer(outIndex, false)
                }
            }

            transmissionThread.join(1000)

        } catch (e: Exception) {
            Log.e(TAG, "Streaming engine error: ${e.message}", e)
        } finally {
            isAudioStreaming = false
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioEncoder?.stop()
                audioEncoder?.release()
            } catch (_: Exception) {}
            audioRecord = null
            audioEncoder = null

            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startAudioCaptureAndSending(
        mp: MediaProjection,
        sendQueue: ArrayBlockingQueue<FramePacket>
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

        Thread {
            try {
                val sampleRate = 44100
                val channelConfig = AudioFormat.CHANNEL_IN_STEREO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
                if (minBufferSize <= 0) return@Thread

                val audioConfig = AudioPlaybackCaptureConfiguration.Builder(mp)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()

                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(audioConfig)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufferSize * 4)
                    .build()

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord failed to initialize")
                    return@Thread
                }

                val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, BtConstants.BT_AUDIO_BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val aacEnc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    start()
                }

                audioRecord = record
                audioEncoder = aacEnc
                isAudioStreaming = true
                record.startRecording()
                Log.d(TAG, "AudioPlaybackCapture and AAC 64 kbps encoder started successfully")

                val pcmBuffer = ByteArray(2048)
                val bufferInfo = MediaCodec.BufferInfo()
                var totalAudioSamples = 0L
                var audioPacketCount = 0

                while (isStreaming && isAudioStreaming) {
                    val readBytes = record.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readBytes > 0) {
                        val inIdx = aacEnc.dequeueInputBuffer(5_000)
                        if (inIdx >= 0) {
                            val inBuf = aacEnc.getInputBuffer(inIdx)
                            inBuf?.clear()
                            inBuf?.put(pcmBuffer, 0, readBytes)
                            // 16-bit stereo PCM: 2 channels * 2 bytes = 4 bytes per sample frame
                            val numSamples = readBytes / 4
                            val ptsUs = (totalAudioSamples * 1_000_000L) / sampleRate
                            totalAudioSamples += numSamples
                            aacEnc.queueInputBuffer(inIdx, 0, readBytes, ptsUs, 0)
                        }
                    }

                    var outIdx = aacEnc.dequeueOutputBuffer(bufferInfo, 0)
                    while (outIdx >= 0) {
                        val outBuf = aacEnc.getOutputBuffer(outIdx)
                        if (outBuf != null && bufferInfo.size > 0) {
                            val aacBytes = ByteArray(bufferInfo.size)
                            outBuf.position(bufferInfo.offset)
                            outBuf.limit(bufferInfo.offset + bufferInfo.size)
                            outBuf.get(aacBytes)

                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                Log.i(TAG, "Audio AAC encoder produced csd-0 (size=${aacBytes.size} bytes)")
                            }

                            if (audioPacketCount++ % 150 == 0) {
                                val exactSamplePos = (bufferInfo.presentationTimeUs * sampleRate) / 1_000_000L
                                Log.i(TAG, "Audio monotonic PTS: ${bufferInfo.presentationTimeUs} us (samplePos=$exactSamplePos, packetCount=$audioPacketCount)")
                            }

                            val aacPacket = FramePacket(
                                BtConstants.MAGIC_HEADER_AUDIO_AAC,
                                bufferInfo.flags,
                                bufferInfo.presentationTimeUs,
                                aacBytes
                            )
                            while (!sendQueue.offer(aacPacket)) {
                                val stale = sendQueue.poll()
                                if (stale == null || stale.magic == BtConstants.MAGIC_HEADER_AUDIO_AAC) {
                                    if (stale != null) sendQueue.offer(stale)
                                    break
                                }
                            }
                        }
                        aacEnc.releaseOutputBuffer(outIdx, false)
                        outIdx = aacEnc.dequeueOutputBuffer(bufferInfo, 0)
                    }

                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = aacEnc.outputFormat
                        Log.i(TAG, "AAC encoder format changed: $newFormat")
                        val csd = newFormat.getByteBuffer("csd-0")
                        if (csd != null) {
                            val csdBytes = ByteArray(csd.remaining())
                            csd.get(csdBytes)
                            Log.i(TAG, "Forwarding csd-0 from format change (size=${csdBytes.size} bytes)")
                            val aacConfigPacket = FramePacket(
                                BtConstants.MAGIC_HEADER_AUDIO_AAC,
                                MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                                0L,
                                csdBytes
                            )
                            while (!sendQueue.offer(aacConfigPacket)) {
                                val stale = sendQueue.poll()
                                if (stale == null || stale.magic == BtConstants.MAGIC_HEADER_AUDIO_AAC) {
                                    if (stale != null) sendQueue.offer(stale)
                                    break
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Audio capture loop exception: ${e.message}")
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioEncoder?.stop()
                    audioEncoder?.release()
                } catch (_: Exception) {}
                audioRecord = null
                audioEncoder = null
            }
        }.apply {
            name = "NvcAudioCaptureThread"
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    private fun createNotification(status: String): Notification {
        return NotificationCompat.Builder(this, "screen_stream_channel")
            .setContentTitle("NVC-BT Screen Stream")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(101, createNotification(status))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "screen_stream_channel",
                "Screen Stream Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun showToast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isStreaming = false
        try {
            virtualDisplay?.release()
            encoder?.stop()
            encoder?.release()
            mediaProjection?.stop()
            tcpSocket?.close()
            btSocket?.close()
        } catch (_: Exception) {}
        Log.d(TAG, "MediaProjectionService destroyed")
    }
}
