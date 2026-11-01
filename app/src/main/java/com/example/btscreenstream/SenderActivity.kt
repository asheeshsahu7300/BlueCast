package com.example.btscreenstream

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors

class SenderActivity : AppCompatActivity() {

    private val TAG = "BtScreenStream"
    private var selectedDevice: BluetoothDevice? = null
    private var selectedIp: String? = null
    private lateinit var pairedListView: ListView
    private lateinit var btnStartProjectionBt: Button
    private lateinit var btnConnectWifi: Button
    private lateinit var editReceiverIp: EditText
    private lateinit var txtDiscoveryStatus: TextView
    private lateinit var rgNvcProfile: android.widget.RadioGroup
    private lateinit var rb4k: android.widget.RadioButton
    private lateinit var rbBalanced: android.widget.RadioButton
    private lateinit var rbUltra: android.widget.RadioButton
    private lateinit var rbCompact: android.widget.RadioButton
    private var isListeningForBeacons = true
    private var multicastLock: WifiManager.MulticastLock? = null

    private data class NvcProfile(val width: Int, val height: Int, val dpi: Int, val bitrate: Int)

    private val captureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, MediaProjectionService::class.java).apply {
                putExtra("RESULT_CODE", result.resultCode)
                putExtra("DATA", result.data)
                if (selectedIp != null) {
                    putExtra("TARGET_IP", selectedIp)
                } else if (selectedDevice != null) {
                    putExtra("DEVICE", selectedDevice)

                    val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    val w4k = if (isLandscape) BtConstants.BT_4K_WIDTH else BtConstants.BT_4K_PORTRAIT_WIDTH
                    val h4k = if (isLandscape) BtConstants.BT_4K_HEIGHT else BtConstants.BT_4K_PORTRAIT_HEIGHT

                    val profile = when {
                        rb4k.isChecked -> NvcProfile(w4k, h4k, BtConstants.BT_4K_DPI, BtConstants.BT_4K_BITRATE)
                        rbUltra.isChecked -> NvcProfile(BtConstants.BT_ULTRA_WIDTH, BtConstants.BT_ULTRA_HEIGHT, BtConstants.BT_ULTRA_DPI, BtConstants.BT_ULTRA_BITRATE)
                        rbCompact.isChecked -> NvcProfile(BtConstants.BT_COMPACT_WIDTH, BtConstants.BT_COMPACT_HEIGHT, BtConstants.BT_COMPACT_DPI, BtConstants.BT_COMPACT_BITRATE)
                        else -> NvcProfile(BtConstants.BT_BALANCED_WIDTH, BtConstants.BT_BALANCED_HEIGHT, BtConstants.BT_BALANCED_DPI, BtConstants.BT_BALANCED_BITRATE)
                    }
                    putExtra("VIDEO_WIDTH", profile.width)
                    putExtra("VIDEO_HEIGHT", profile.height)
                    putExtra("VIDEO_DPI", profile.dpi)
                    putExtra("VIDEO_BITRATE", profile.bitrate)
                }
            }
            startForegroundService(serviceIntent)
            Toast.makeText(this, "Screen capture service started!", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "Screen capture permission was denied", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sender)

        editReceiverIp = findViewById(R.id.editReceiverIp)
        txtDiscoveryStatus = findViewById(R.id.txtDiscoveryStatus)
        btnConnectWifi = findViewById(R.id.btnConnectWifi)
        pairedListView = findViewById(R.id.listPairedDevices)
        btnStartProjectionBt = findViewById(R.id.btnStartCapture)
        rgNvcProfile = findViewById(R.id.rgNvcProfile)
        rb4k = findViewById(R.id.rb4k)
        rbBalanced = findViewById(R.id.rbBalanced)
        rbUltra = findViewById(R.id.rbUltra)
        rbCompact = findViewById(R.id.rbCompact)

        // Acquire MulticastLock to allow UDP discovery through OEM battery savers
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            multicastLock = wm.createMulticastLock("bt_stream_mcast").apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (_: Exception) {}

        // 1. Dual Discovery: UDP Listener + Fast Subnet TCP Port Sweep
        startBeaconListener()
        scanSubnetForReceiver()

        btnConnectWifi.setOnClickListener {
            val ip = editReceiverIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "Please enter or wait for Receiver IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectedIp = ip
            selectedDevice = null
            requestScreenCapture()
        }

        // 2. Bluetooth Fallback Setup
        val btManager = getSystemService(BluetoothManager::class.java)
        val btAdapter = btManager?.adapter

        if (btAdapter != null && btAdapter.isEnabled) {
            val pairedDevices = btAdapter.bondedDevices.toList()
            val deviceNames = pairedDevices.map { "${it.name ?: "Unknown"} (${it.address})" }
            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, deviceNames)
            pairedListView.adapter = adapter

            pairedListView.setOnItemClickListener { _, _, position, _ ->
                selectedDevice = pairedDevices[position]
                btnStartProjectionBt.isEnabled = true
            }

            btnStartProjectionBt.setOnClickListener {
                if (selectedDevice != null) {
                    selectedIp = null
                    requestScreenCapture()
                }
            }
        }
    }

    private fun startBeaconListener() {
        Thread {
            try {
                val socket = DatagramSocket(BtConstants.UDP_DISCOVERY_PORT)
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isListeningForBeacons) {
                    socket.receive(packet)
                    val msg = String(packet.data, 0, packet.length)
                    if (msg.startsWith("BTSCREEN_RECEIVER:")) {
                        val discoveredIp = msg.substringAfter("BTSCREEN_RECEIVER:")
                        runOnUiThread {
                            editReceiverIp.setText(discoveredIp)
                            txtDiscoveryStatus.text = "✓ Found Receiver on Wi-Fi: $discoveredIp"
                            btnConnectWifi.text = "Start 1080p @ 60fps Stream to $discoveredIp"
                        }
                    }
                }
                socket.close()
            } catch (_: Exception) {}
        }.start()
    }

    private fun scanSubnetForReceiver() {
        Thread {
            try {
                val myIp = getLocalIpAddress()
                if (myIp == "127.0.0.1") return@Thread

                val prefix = myIp.substringBeforeLast(".") + "."
                val executor = Executors.newFixedThreadPool(48)

                for (i in 1..254) {
                    val target = "$prefix$i"
                    if (target == myIp) continue
                    executor.execute {
                        try {
                            val s = Socket()
                            s.connect(InetSocketAddress(target, BtConstants.TCP_PORT), 350)
                            s.close()
                            runOnUiThread {
                                editReceiverIp.setText(target)
                                txtDiscoveryStatus.text = "✓ Found Receiver on Wi-Fi: $target"
                                btnConnectWifi.text = "Start 1080p @ 60fps Stream to $target"
                            }
                        } catch (_: Exception) {}
                    }
                }
                executor.shutdown()
            } catch (_: Exception) {}
        }.start()
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

    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestScreenCaptureActual()
    }

    private fun requestScreenCapture() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            audioPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else {
            requestScreenCaptureActual()
        }
    }

    private fun requestScreenCaptureActual() {
        val mpManager = getSystemService(MediaProjectionManager::class.java)
        captureLauncher.launch(mpManager.createScreenCaptureIntent())
    }

    override fun onDestroy() {
        super.onDestroy()
        isListeningForBeacons = false
        try {
            multicastLock?.release()
        } catch (_: Exception) {}
    }
}
