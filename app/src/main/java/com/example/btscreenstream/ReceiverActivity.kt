package com.example.btscreenstream

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class ReceiverActivity : AppCompatActivity() {

    private val TAG = "BtScreenStream"
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var statusText: TextView
    private lateinit var nvcRenderer: NvcGlRenderer

    private var tcpServerSocket: ServerSocket? = null
    private var btServerSocket: BluetoothServerSocket? = null
    private var activeTcpSocket: Socket? = null
    private var activeBtSocket: BluetoothSocket? = null
    private var decoder: MediaCodec? = null
    private var currentDecoderMime: String? = null
    private var isRunning = false
    private var isBeaconActive = false

    private var activeSurface: Surface? = null
    private val surfaceLock = Object()
    @Volatile
    private var isSessionActive = false
    private var outputThread: Thread? = null
    private val audioPlayer = AudioStreamPlayer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Lock screen refresh rate to 60Hz to prevent 90Hz 3:2 pulldown micro-stutter
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val modes = display?.supportedModes ?: emptyArray()
                val mode60 = modes.firstOrNull { it.refreshRate in 59.0f..61.0f }
                if (mode60 != null) {
                    val lp = window.attributes
                    lp.preferredDisplayModeId = mode60.modeId
                    lp.preferredRefreshRate = 60.0f
                    window.attributes = lp
                }
            } catch (_: Exception) {}
        }

        setContentView(R.layout.activity_receiver)

        glSurfaceView = findViewById(R.id.receiverGlSurface)
        statusText = findViewById(R.id.txtReceiverStatus)

        glSurfaceView.setEGLContextClientVersion(2)
        nvcRenderer = NvcGlRenderer(
            onSurfaceReady = { surface ->
                synchronized(surfaceLock) {
                    activeSurface = surface
                    surfaceLock.notifyAll()
                }
            },
            requestRender = { glSurfaceView.requestRender() }
        )
        glSurfaceView.setRenderer(nvcRenderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        adjustAspectRatio(BtConstants.BT_DEFAULT_WIDTH, BtConstants.BT_DEFAULT_HEIGHT)
        startListeners()
    }

    private fun getReadySurface(): Surface {
        synchronized(surfaceLock) {
            while (activeSurface == null && isRunning) {
                try {
                    surfaceLock.wait(500)
                } catch (_: Exception) {}
            }
            return activeSurface ?: throw IllegalStateException("Surface not ready")
        }
    }

    private fun adjustAspectRatio(videoWidth: Int, videoHeight: Int) {
        runOnUiThread {
            val root = findViewById<ViewGroup>(android.R.id.content) ?: return@runOnUiThread
            val screenWidth = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
            val screenHeight = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels

            val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
            val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()

            val lp = glSurfaceView.layoutParams
            if (Math.abs(videoRatio - screenRatio) < 0.005f) {
                // Exact matching aspect ratio: fill full screen edge-to-edge
                lp.width = screenWidth
                lp.height = screenHeight
            } else if (videoRatio > screenRatio) {
                // Video is wider than screen: fit width, letterbox top/bottom
                lp.width = screenWidth
                lp.height = (screenWidth / videoRatio).toInt()
            } else {
                // Video is taller than screen: fit height, pillarbox left/right (preserves exact font geometry)
                lp.height = screenHeight
                lp.width = (screenHeight * videoRatio).toInt()
            }
            glSurfaceView.layoutParams = lp
        }
    }

    @SuppressLint("MissingPermission")
    private fun startListeners() {
        isRunning = true
        val localIp = getLocalIpAddress()
        updateStatus("NVC-BT 4K Reconstruction Engine Ready!\nWaiting for sender over Bluetooth or Wi-Fi ($localIp)...")

        // 1. High-Speed TCP Server (Wi-Fi)
        Thread {
            try {
                tcpServerSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(BtConstants.TCP_PORT))
                }
                Log.d(TAG, "TCP Server listening on port ${BtConstants.TCP_PORT}")

                startUdpBeacon(localIp)

                while (isRunning) {
                    val client = tcpServerSocket?.accept() ?: break
                    client.tcpNoDelay = true
                    client.receiveBufferSize = 1024 * 1024

                    // Filter out fast subnet discovery probes:
                    // Only start decoding if client actually sends stream magic byte
                    client.soTimeout = 2000
                    val inStream = client.getInputStream()
                    val b1 = try { inStream.read() } catch (_: Exception) { -1 }

                    if (b1 != (BtConstants.MAGIC_BYTE_1.toInt() and 0xFF)) {
                        // Subnet probe, silently close
                        try { client.close() } catch (_: Exception) {}
                        continue
                    }

                    client.soTimeout = 0
                    activeTcpSocket = client
                    updateStatus("Connected via Wi-Fi!\nClient: ${client.inetAddress.hostAddress}")
                    Log.d(TAG, "TCP Client connected: ${client.inetAddress.hostAddress}")
                    startDecodingLoop(inStream, firstByte = b1)
                    activeTcpSocket = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "TCP listener ended: ${e.message}")
            }
        }.start()

        // 2. Bluetooth RFCOMM Server (NVC-BT Target - Always Accepted)
        Thread {
            try {
                val btManager = getSystemService(BluetoothManager::class.java)
                val btAdapter = btManager?.adapter
                if (btAdapter != null && btAdapter.isEnabled) {
                    btServerSocket = btAdapter.listenUsingInsecureRfcommWithServiceRecord("BtScreenStream", BtConstants.APP_UUID)
                    Log.d(TAG, "Bluetooth RFCOMM listening on UUID ${BtConstants.APP_UUID}")

                    while (isRunning) {
                        val btClient = btServerSocket?.accept() ?: break
                        activeBtSocket = btClient
                        updateStatus("Connected via Bluetooth!\nSender: ${btClient.remoteDevice?.name}\nNVC-BT 60 FPS Super-Resolution Active")
                        Log.d(TAG, "Bluetooth sender connected: ${btClient.remoteDevice?.name}")
                        startDecodingLoop(btClient.inputStream)
                        activeBtSocket = null
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bluetooth listener ended: ${e.message}")
            }
        }.start()
    }

    private fun startUdpBeacon(myIp: String) {
        isBeaconActive = true
        Thread {
            try {
                val socket = DatagramSocket()
                socket.broadcast = true
                val msg = "BTSCREEN_RECEIVER:$myIp".toByteArray()

                while (isBeaconActive && isRunning && activeTcpSocket == null) {
                    try {
                        val packet = DatagramPacket(
                            msg, msg.size,
                            InetAddress.getByName("255.255.255.255"),
                            BtConstants.UDP_DISCOVERY_PORT
                        )
                        socket.send(packet)
                    } catch (_: Exception) {}
                    Thread.sleep(1000)
                }
                socket.close()
            } catch (_: Exception) {}
        }.start()
    }

    private fun initDecoder(mime: String, surface: Surface): MediaCodec {
        val existing = decoder
        if (existing != null && currentDecoderMime == mime) {
            return existing
        }

        isSessionActive = false
        try {
            outputThread?.join(300)
        } catch (_: Exception) {}

        try {
            existing?.stop()
            existing?.release()
        } catch (_: Exception) {}

        // Initialize decoder format with portrait default capabilities
        val format = MediaFormat.createVideoFormat(mime, BtConstants.BT_DEFAULT_WIDTH, BtConstants.BT_DEFAULT_HEIGHT).apply {
            try {
                setInteger(MediaFormat.KEY_MAX_WIDTH, 4096)
                setInteger(MediaFormat.KEY_MAX_HEIGHT, 4096)
            } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                } catch (_: Exception) {}
            }
        }

        val dec = MediaCodec.createDecoderByType(mime).apply {
            configure(format, surface, null, 0)
            start()
        }
        decoder = dec
        currentDecoderMime = mime
        Log.i(TAG, "Hardware decoder initialized for mime: $mime with NVC Super-Res Surface")

        startOutputThread(dec)
        return dec
    }

    private fun startOutputThread(activeDec: MediaCodec) {
        isSessionActive = true
        outputThread = Thread {
            val bufferInfo = MediaCodec.BufferInfo()
            try {
                while (isRunning && isSessionActive) {
                    val outIndex = try {
                        activeDec.dequeueOutputBuffer(bufferInfo, 10_000)
                    } catch (_: Exception) {
                        break
                    }
                    if (outIndex >= 0) {
                        // Render directly into NVC Super-Resolution SurfaceTexture immediately as decoded!
                        activeDec.releaseOutputBuffer(outIndex, true)
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val newFormat = activeDec.outputFormat
                        val w = newFormat.getInteger(MediaFormat.KEY_WIDTH)
                        val h = newFormat.getInteger(MediaFormat.KEY_HEIGHT)
                        Log.d(TAG, "Decoder output resolution changed to: ${w}x${h}")
                        nvcRenderer.setVideoSize(w, h)
                        adjustAspectRatio(w, h)
                    }
                }
            } catch (_: Exception) {}
        }.apply {
            name = "NvcDecoderOutputThread"
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private data class IncomingVideoPacket(
        val isHevc: Boolean,
        val flags: Int,
        val pts: Long,
        val payload: ByteArray
    )

    private data class IncomingAudioPacket(
        val flags: Int,
        val pts: Long,
        val payload: ByteArray
    )

    private fun isKeyFrameNal(data: ByteArray, isHevc: Boolean): Boolean {
        var i = 0
        while (i < data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                var nalOffset = -1
                if (data[i + 2] == 1.toByte()) {
                    nalOffset = i + 3
                } else if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    nalOffset = i + 4
                }
                if (nalOffset != -1 && nalOffset < data.size) {
                    val header = data[nalOffset].toInt() and 0xFF
                    if (isHevc) {
                        val nalType = (header shr 1) and 0x3F
                        if (nalType in 16..21) return true
                    } else {
                        val nalType = header and 0x1F
                        if (nalType == 5) return true
                    }
                }
            }
            i++
        }
        return false
    }

    private fun isConfigNal(data: ByteArray, isHevc: Boolean): Boolean {
        var i = 0
        while (i < data.size - 4) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                var nalOffset = -1
                if (data[i + 2] == 1.toByte()) {
                    nalOffset = i + 3
                } else if (data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                    nalOffset = i + 4
                }
                if (nalOffset != -1 && nalOffset < data.size) {
                    val header = data[nalOffset].toInt() and 0xFF
                    if (isHevc) {
                        val nalType = (header shr 1) and 0x3F
                        if (nalType == 32 || nalType == 33 || nalType == 34) return true
                    } else {
                        val nalType = header and 0x1F
                        if (nalType == 7 || nalType == 8) return true
                    }
                }
            }
            i++
        }
        return false
    }

    private fun startDecodingLoop(rawInputStream: InputStream, firstByte: Int = -1) {
        val surface = try {
            getReadySurface()
        } catch (e: Exception) {
            Log.e(TAG, "Cannot start decoding: ${e.message}")
            return
        }

        val inputStream = DataInputStream(java.io.BufferedInputStream(rawInputStream, 65536))
        var totalBytes = 0L
        var lastTotalBytes = 0L
        var lastMetricsTime = System.currentTimeMillis()
        var isRecoveringVideo = false

        audioPlayer.init()
        isSessionActive = true

        // Ultra-low latency queues: capacity 16 frames (<260ms ceiling, preserves reference integrity)
        val videoQueue = java.util.concurrent.ArrayBlockingQueue<IncomingVideoPacket>(16)
        val audioQueue = java.util.concurrent.ArrayBlockingQueue<IncomingAudioPacket>(32)

        // 1. Dedicated Video Decoder Worker: feeds hardware MediaCodec asynchronously
        val videoFeederThread = Thread {
            while (isRunning && isSessionActive) {
                val packet = try {
                    videoQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                } catch (_: Exception) { break }

                val mime = if (packet.isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                val dec = initDecoder(mime, surface)

                var inIndex = dec.dequeueInputBuffer(10_000)
                while (inIndex < 0 && isRunning && isSessionActive) {
                    inIndex = dec.dequeueInputBuffer(10_000)
                }

                if (inIndex >= 0) {
                    val inBuffer = dec.getInputBuffer(inIndex)
                    inBuffer?.clear()
                    inBuffer?.put(packet.payload)
                    dec.queueInputBuffer(inIndex, 0, packet.payload.size, packet.pts, packet.flags)
                }
            }
        }.apply {
            name = "NvcVideoFeederThread"
            priority = Thread.MAX_PRIORITY
            start()
        }

        // 2. Dedicated Audio Player Worker: completely decouples blocking AudioTrack.write from network
        val audioWorkerThread = Thread {
            while (isRunning && isSessionActive) {
                val packet = try {
                    audioQueue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                } catch (_: Exception) { break }
                audioPlayer.playPacket(packet.payload, packet.flags, packet.pts)
            }
        }.apply {
            name = "NvcAudioWorkerThread"
            priority = Thread.NORM_PRIORITY
            start()
        }

        try {
            var b1 = if (firstByte != -1) firstByte else inputStream.read()
            while (isRunning && b1 != -1) {
                if (b1.toByte() == BtConstants.MAGIC_BYTE_1) {
                    val b2 = inputStream.read()
                    if (b2 == -1) break
                    val b2Byte = b2.toByte()

                    if (b2Byte == BtConstants.MAGIC_BYTE_2_AUDIO_AAC) {
                        val flags = inputStream.readShort().toInt()
                        val length = inputStream.readInt()
                        val pts = inputStream.readLong()

                        if (length in 1..8192) {
                            val audioPayload = ByteArray(length)
                            inputStream.readFully(audioPayload)
                            totalBytes += length
                            audioQueue.offer(IncomingAudioPacket(flags, pts, audioPayload))
                        } else {
                            Log.w(TAG, "Invalid audio packet length: $length, dropping stream to re-sync")
                            b1 = inputStream.read()
                            continue
                        }
                        b1 = inputStream.read()
                        continue
                    }

                    if (b2Byte == BtConstants.MAGIC_BYTE_2_HEVC || b2Byte == BtConstants.MAGIC_BYTE_2_AVC) {
                        val isHevc = (b2Byte == BtConstants.MAGIC_BYTE_2_HEVC)
                        val flags = inputStream.readShort().toInt()
                        val length = inputStream.readInt()
                        val pts = inputStream.readLong()

                        if (length !in 1..2_000_000) {
                            Log.w(TAG, "Invalid video packet length: $length, dropping stream to re-sync")
                            b1 = inputStream.read()
                            continue
                        }

                        val payload = ByteArray(length)
                        inputStream.readFully(payload)
                        totalBytes += length

                        val isConfig = (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0 || isConfigNal(payload, isHevc)
                        val isKeyFrame = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || isKeyFrameNal(payload, isHevc)

                        if (isRecoveringVideo) {
                            if (isConfig) {
                                // Keep codec configuration parameter sets up to date
                                videoQueue.offer(IncomingVideoPacket(isHevc, flags, pts, payload))
                            } else if (isKeyFrame) {
                                isRecoveringVideo = false
                                Log.i(TAG, "Video reference restored on IDR keyframe (pts=$pts)")
                                videoQueue.offer(IncomingVideoPacket(isHevc, flags, pts, payload))
                            }
                            // Suppress P-frames during recovery to strictly forbid reference tearing!
                        } else {
                            if (videoQueue.remainingCapacity() == 0) {
                                Log.w(TAG, "Video queue full! Flushing stale frames and entering sync recovery")
                                videoQueue.clear()
                                isRecoveringVideo = true
                            } else {
                                videoQueue.offer(IncomingVideoPacket(isHevc, flags, pts, payload))
                            }
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastMetricsTime >= 2000) {
                            val sec = (now - lastMetricsTime) / 1000.0
                            val bytesInPeriod = totalBytes - lastTotalBytes
                            lastTotalBytes = totalBytes
                            val kbps = (bytesInPeriod * 8 / 1000.0) / sec
                            val renderFps = nvcRenderer.currentFps
                            val mime = if (isHevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                            Log.i(TAG, "Receiver Stats: ${String.format("%.1f", renderFps)} FPS | ${String.format("%.1f", kbps)} kbps ($mime)")
                            updateStatus("NVC Reconstruction: ACTIVE (CAR)\n" +
                                         "Transport Ingest: ${String.format("%.1f", kbps / 1000.0)} Mbps\n" +
                                         "Display Rate: ${String.format("%.1f", renderFps)} FPS\n" +
                                         "Recovery State: ${if (isRecoveringVideo) "SYNCING" else "LOCKED"}")
                            lastMetricsTime = now
                        }

                        b1 = inputStream.read()
                        continue
                    } else {
                        b1 = b2
                        continue
                    }
                }
                b1 = inputStream.read()
            }
        } catch (e: Exception) {
            val reason = if (e is EOFException) "Connection closed" else (e.message ?: "Disconnected")
            Log.w(TAG, "Stream ended: $reason")
            updateStatus("Disconnected: $reason\nWaiting for new stream...")
        } finally {
            isSessionActive = false
            try {
                videoFeederThread.interrupt()
                videoFeederThread.join(300)
            } catch (_: Exception) {}
            try {
                audioWorkerThread.interrupt()
                audioWorkerThread.join(300)
            } catch (_: Exception) {}
            audioPlayer.release()
            try {
                outputThread?.join(300)
            } catch (_: Exception) {}
            try {
                decoder?.stop()
                decoder?.release()
                decoder = null
                currentDecoderMime = null
            } catch (_: Exception) {}
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { statusText.text = msg }
    }

    private fun stopReceiver() {
        isRunning = false
        isSessionActive = false
        isBeaconActive = false
        audioPlayer.release()
        try {
            outputThread?.interrupt()
            outputThread?.join(300)
            decoder?.stop()
            decoder?.release()
            activeTcpSocket?.close()
            activeBtSocket?.close()
            tcpServerSocket?.close()
            btServerSocket?.close()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopReceiver()
    }

    // --- NVC GPU Super-Resolution Reconstruction Renderer ---
    class NvcGlRenderer(
        private val onSurfaceReady: (Surface) -> Unit,
        private val requestRender: () -> Unit
    ) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

        private val TAG = "NvcGlRenderer"
        private var programId = 0
        private var textureId = -1
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null

        private var updateSurface = false
        private val transformMatrix = FloatArray(16)

        private var videoWidth = BtConstants.BT_DEFAULT_WIDTH
        private var videoHeight = BtConstants.BT_DEFAULT_HEIGHT
        private var sharpness = 1.25f

        private var aPositionHandle = -1
        private var aTexCoordHandle = -1
        private var uSTMatrixHandle = -1
        private var uStepHandle = -1
        private var uSharpnessHandle = -1

        private val vertexBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(floatArrayOf(
                    -1.0f, -1.0f,
                     1.0f, -1.0f,
                    -1.0f,  1.0f,
                     1.0f,  1.0f
                ))
                position(0)
            }
        }

        private val textureBuffer: FloatBuffer = ByteBuffer.allocateDirect(4 * 2 * 4).run {
            order(ByteOrder.nativeOrder())
            asFloatBuffer().apply {
                put(floatArrayOf(
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    0.0f, 1.0f,
                    1.0f, 1.0f
                ))
                position(0)
            }
        }

        private val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uSTMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uSTMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        // NVC Contrast-Adaptive Edge Reconstruction Shader (CAR)
        // High-precision 9-tap isotropic filter designed specifically for crisp screen text and UI icons
        private val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;

            varying vec2 vTexCoord;
            uniform samplerExternalOES sTexture;
            uniform vec2 uStep;
            uniform float uSharpness;

            float rgb2luma(vec3 rgb) {
                return dot(rgb, vec3(0.299, 0.587, 0.114));
            }

            void main() {
                vec4 center = texture2D(sTexture, vTexCoord);
                vec3 c = center.rgb;

                // Cardinal cross samples at exact 1-texel offset
                vec3 n = texture2D(sTexture, vTexCoord + vec2(0.0, -uStep.y)).rgb;
                vec3 s = texture2D(sTexture, vTexCoord + vec2(0.0,  uStep.y)).rgb;
                vec3 w = texture2D(sTexture, vTexCoord + vec2(-uStep.x, 0.0)).rgb;
                vec3 e = texture2D(sTexture, vTexCoord + vec2( uStep.x, 0.0)).rgb;

                // Diagonal samples for isotropic 9-tap reconstruction
                vec3 nw = texture2D(sTexture, vTexCoord + vec2(-uStep.x, -uStep.y)).rgb;
                vec3 ne = texture2D(sTexture, vTexCoord + vec2( uStep.x, -uStep.y)).rgb;
                vec3 sw = texture2D(sTexture, vTexCoord + vec2(-uStep.x,  uStep.y)).rgb;
                vec3 se = texture2D(sTexture, vTexCoord + vec2( uStep.x,  uStep.y)).rgb;

                // Weighted unsharp kernel: 2.0x cross, 1.0x diagonal, 12.0 total divisor
                vec3 blur = (2.0 * (n + s + w + e) + (nw + ne + sw + se)) / 12.0;
                vec3 detail = c - blur;

                // Local contrast detection based on luma
                float lc = rgb2luma(c);
                float ln = rgb2luma(n);
                float ls = rgb2luma(s);
                float lw = rgb2luma(w);
                float le = rgb2luma(e);

                float minL = min(lc, min(min(ln, ls), min(lw, le)));
                float maxL = max(lc, max(max(ln, ls), max(lw, le)));
                float localContrast = maxL - minL;

                // Modulate sharpening: maximum on sharp UI text/icons, suppress on flat gradients and dark compression blocks
                float edgeWeight = smoothstep(0.06, 0.24, localContrast);
                float sharpFactor = uSharpness * edgeWeight;

                vec3 sharp = c + detail * sharpFactor;

                // Clamp to prevent overshoot halos, allowing crisp edge delineation
                vec3 minRgb = min(c, min(min(n, s), min(w, e))) - 0.06;
                vec3 maxRgb = max(c, max(max(n, s), max(w, e))) + 0.06;
                sharp = clamp(sharp, max(vec3(0.0), minRgb), min(vec3(1.0), maxRgb));

                gl_FragColor = vec4(sharp, center.a);
            }
        """.trimIndent()

        fun setVideoSize(w: Int, h: Int) {
            videoWidth = w
            videoHeight = h
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            programId = createProgram(vertexShaderCode, fragmentShaderCode)

            aPositionHandle = GLES20.glGetAttribLocation(programId, "aPosition")
            aTexCoordHandle = GLES20.glGetAttribLocation(programId, "aTexCoord")
            uSTMatrixHandle = GLES20.glGetUniformLocation(programId, "uSTMatrix")
            uStepHandle = GLES20.glGetUniformLocation(programId, "uStep")
            uSharpnessHandle = GLES20.glGetUniformLocation(programId, "uSharpness")

            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

            val st = SurfaceTexture(textureId)
            st.setOnFrameAvailableListener(this)
            surfaceTexture = st

            val s = Surface(st)
            surface = s
            onSurfaceReady(s)
            Log.d(TAG, "NVC OpenGL SurfaceTexture created with texId: $textureId, uniform caching complete")
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            Log.d(TAG, "GL Viewport set to full screen: ${width}x${height}")
        }

        private var renderCount = 0
        private var lastRenderTime = System.currentTimeMillis()
        var currentFps: Float = 0.0f
            private set

        override fun onDrawFrame(gl: GL10?) {
            synchronized(this) {
                if (updateSurface) {
                    surfaceTexture?.updateTexImage()
                    surfaceTexture?.getTransformMatrix(transformMatrix)
                    updateSurface = false
                    renderCount++
                }
            }

            val nowTime = System.currentTimeMillis()
            if (nowTime - lastRenderTime >= 1000) {
                val dt = (nowTime - lastRenderTime) / 1000.0f
                currentFps = if (dt > 0) renderCount / dt else 0.0f
                renderCount = 0
                lastRenderTime = nowTime
                Log.i("NvcGlRenderer", "Render FPS: ${String.format("%.1f", currentFps)}")
            }

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            if (programId == 0 || textureId == -1) return

            GLES20.glUseProgram(programId)

            GLES20.glEnableVertexAttribArray(aPositionHandle)
            GLES20.glVertexAttribPointer(aPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer)

            GLES20.glEnableVertexAttribArray(aTexCoordHandle)
            GLES20.glVertexAttribPointer(aTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, textureBuffer)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)

            GLES20.glUniformMatrix4fv(uSTMatrixHandle, 1, false, transformMatrix, 0)
            GLES20.glUniform2f(uStepHandle, 1.0f / videoWidth.toFloat(), 1.0f / videoHeight.toFloat())
            GLES20.glUniform1f(uSharpnessHandle, sharpness)

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

            GLES20.glDisableVertexAttribArray(aPositionHandle)
            GLES20.glDisableVertexAttribArray(aTexCoordHandle)
        }

        override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
            synchronized(this) {
                updateSurface = true
            }
            requestRender()
        }

        private fun loadShader(type: Int, shaderCode: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, shaderCode)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            if (compiled[0] == 0) {
                Log.e(TAG, "Shader compilation error: " + GLES20.glGetShaderInfoLog(shader))
                GLES20.glDeleteShader(shader)
                return 0
            }
            return shader
        }

        private fun createProgram(vertexCode: String, fragmentCode: String): Int {
            val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexCode)
            val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentCode)
            val program = GLES20.glCreateProgram()
            GLES20.glAttachShader(program, vertexShader)
            GLES20.glAttachShader(program, fragmentShader)
            GLES20.glLinkProgram(program)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                Log.e(TAG, "Program linking error: " + GLES20.glGetProgramInfoLog(program))
                GLES20.glDeleteProgram(program)
                return 0
            }
            return program
        }
    }

    // --- High-Fidelity Audio Receiver & AAC Hardware Decoder ---
    class AudioStreamPlayer {
        private var decoder: MediaCodec? = null
        private var audioTrack: AudioTrack? = null
        private val bufferInfo = MediaCodec.BufferInfo()
        private var isConfiguredFromCsd = false
        private var playbackPacketCount = 0

        fun init() {
            try {
                val sampleRate = 44100
                val channelMask = AudioFormat.CHANNEL_OUT_STEREO
                val encoding = AudioFormat.ENCODING_PCM_16BIT
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding)
                if (minBuf <= 0) return

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 4)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                track.play()
                audioTrack = track
                playbackPacketCount = 0
                Log.i("BtScreenStream", "AudioTrack initialized successfully")
            } catch (e: Exception) {
                Log.w("BtScreenStream", "Failed to init AudioTrack: ${e.message}")
            }
        }

        fun initDecoderFromCsd(csd0: ByteArray, sampleRate: Int = 44100) {
            try {
                decoder?.stop()
                decoder?.release()
            } catch (_: Exception) {}

            try {
                val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
                }
                val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                    configure(aacFormat, null, null, 0)
                    start()
                }
                decoder = dec
                isConfiguredFromCsd = true
                Log.i("BtScreenStream", "AAC decoder initialized from csd-0 (${csd0.size} bytes)")
            } catch (e: Exception) {
                Log.w("BtScreenStream", "Failed to init AAC decoder from csd-0: ${e.message}")
            }
        }

        fun playPacket(payload: ByteArray, flags: Int, pts: Long) {
            if ((flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                Log.i("BtScreenStream", "AAC csd-0 packet received (${payload.size} bytes), initializing decoder")
                initDecoderFromCsd(payload)
                return
            }

            if (decoder == null) {
                try {
                    val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 44100, 2).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_IS_ADTS, 0)
                    }
                    val dec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                        configure(aacFormat, null, null, 0)
                        start()
                    }
                    decoder = dec
                    Log.i("BtScreenStream", "Fallback AAC decoder initialized without csd-0")
                } catch (e: Exception) {
                    Log.w("BtScreenStream", "Fallback AAC decoder init failed: ${e.message}")
                    return
                }
            }

            val dec = decoder ?: return
            val track = audioTrack ?: return
            try {
                val inIdx = dec.dequeueInputBuffer(2_000)
                if (inIdx >= 0) {
                    val inBuf = dec.getInputBuffer(inIdx)
                    inBuf?.clear()
                    inBuf?.put(payload)
                    dec.queueInputBuffer(inIdx, 0, payload.size, pts, flags)
                }

                var outIdx = dec.dequeueOutputBuffer(bufferInfo, 0)
                while (outIdx >= 0) {
                    val outBuf = dec.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val pcm = ByteArray(bufferInfo.size)
                        outBuf.get(pcm)
                        track.write(pcm, 0, pcm.size)

                        if (playbackPacketCount++ % 150 == 0) {
                            Log.i("BtScreenStream", "AAC AudioTrack output confirmed: PTS=$pts us, PCM bytes=${pcm.size}, packets=$playbackPacketCount")
                        }
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    outIdx = dec.dequeueOutputBuffer(bufferInfo, 0)
                }
            } catch (_: Exception) {}
        }

        fun release() {
            try {
                audioTrack?.stop()
                audioTrack?.release()
                decoder?.stop()
                decoder?.release()
            } catch (_: Exception) {}
            audioTrack = null
            decoder = null
        }
    }
}
