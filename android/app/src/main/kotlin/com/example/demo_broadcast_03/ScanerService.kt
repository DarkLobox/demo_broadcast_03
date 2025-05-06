package com.example.demo_broadcast_03

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.minew.beaconplus.sdk.MTCentralManager
import com.minew.beaconplus.sdk.MTPeripheral
import com.minew.beaconplus.sdk.enums.ConnectionStatus
import com.minew.beaconplus.sdk.enums.FrameType
import com.minew.beaconplus.sdk.enums.TriggerType
import com.minew.beaconplus.sdk.exception.MTException
import com.minew.beaconplus.sdk.frames.MinewFrame
import com.minew.beaconplus.sdk.frames.UrlFrame
import com.minew.beaconplus.sdk.interfaces.ConnectionStatueListener
import com.minew.beaconplus.sdk.interfaces.GetPasswordListener
import com.minew.beaconplus.sdk.interfaces.MTCentralManagerListener
import com.minew.beaconplus.sdk.interfaces.SetTriggerListener
import com.minew.beaconplus.sdk.model.Trigger
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject


class ScanerService : Service() {

    companion object {
        private const val TAG = "ScanerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "smartmuni_beacon_service"
        private const val CHANNEL_NAME = "Smart Muni Beacon Service"
        
        private const val ACTION_PROCESS_MAC = "processMac"
        private const val PACKAGE_NAME = "com.example.demo_broadcast_03"
        private const val FLUTTER_METHOD_CHANNEL = "$PACKAGE_NAME.background_service"
        private const val BROADCAST_PERIPHERALS = "$PACKAGE_NAME.PERIPHERALS_JSON"
        private const val BROADCAST_MAC_RESULT = "$PACKAGE_NAME.MAC_PROCESS_RESULT"
        
        // Default values for triggers
        private const val BEACON_PASSWORD = "minew123"
        private const val TRIGGER_SLOT = 2
        private const val TRIGGER_CONDITION_MS = 5000
    }

    // Flutter integration
    private lateinit var flutterEngine: FlutterEngine
    private lateinit var methodChannel: MethodChannel
    
    // Bluetooth management
    private lateinit var centralManager: MTCentralManager
    private val discoveredPeripherals = mutableListOf<MTPeripheral>()

    override fun onCreate() {
        super.onCreate()
        initializeFlutterEngine()
        createNotificationChannel()
        startForegroundService()
        initializeBluetoothManager()
    }

    private fun initializeFlutterEngine() {
        flutterEngine = FlutterEngine(this)
        flutterEngine.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint.createDefault()
        )
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, FLUTTER_METHOD_CHANNEL)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundService() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Emergency Button Scanner")
            .setContentText("Scanning for nearby emergency beacons")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun initializeBluetoothManager() {
        centralManager = MTCentralManager.getInstance(this)
        centralManager.startService()
        startScanning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.action == ACTION_PROCESS_MAC) {
                val macAddress = it.getStringExtra("mac")
                if (!macAddress.isNullOrBlank()) {
                    processMacAddress(macAddress)
                }
            }
        }
        
        return START_STICKY
    }

    private fun startScanning() {
        if (!centralManager.isScanning()) {
            Log.d(TAG, "Starting BLE scan")
            centralManager.startScan()
            
            centralManager.setMTCentralManagerListener(object : MTCentralManagerListener {
                override fun onScanedPeripheral(peripherals: List<MTPeripheral>) {
                    handleDiscoveredPeripherals(peripherals)
                }
            })
        } else {
            Log.d(TAG, "Scan already in progress")
        }
    }

    private fun handleDiscoveredPeripherals(peripherals: List<MTPeripheral>) {
        discoveredPeripherals.clear()
        discoveredPeripherals.addAll(peripherals)

        val peripheralsJson = convertPeripheralsToJson()
        broadcastPeripherals(peripheralsJson)
    }

    private fun convertPeripheralsToJson(): JSONArray {
        val jsonArray = JSONArray()

        for (peripheral in discoveredPeripherals) {
            val handler = peripheral.mMTFrameHandler
            val deviceJson = JSONObject().apply {
                put("mac", handler.getMac())
                put("name", handler.getName() ?: JSONObject.NULL)
            }

            // Extract URL from frames if available
            val advFrames: ArrayList<MinewFrame> = handler.getAdvFrames()
            for (frame in advFrames) {
                if (frame.getFrameType() == FrameType.FrameURL) {
                    val urlFrame = frame as UrlFrame
                    deviceJson.put("url", urlFrame.getUrlString())
                }
            }

            jsonArray.put(deviceJson)
            Log.d(TAG, "Device found: $deviceJson")
        }
        
        return jsonArray
    }

    private fun broadcastPeripherals(peripheralsJson: JSONArray) {
        val broadcastIntent = Intent(BROADCAST_PERIPHERALS).apply {
            putExtra("peripherals", peripheralsJson.toString())
            setPackage(packageName)
        }
        sendBroadcast(broadcastIntent)
    }

    private fun stopScanning() {
        if (centralManager.isScanning()) {
            Log.d(TAG, "Stopping BLE scan")
            centralManager.stopScan()
        }
    }

    private fun processMacAddress(macAddress: String) {
        Log.d(TAG, "Processing MAC: $macAddress")
        
        // Find the peripheral with the specific MAC
        val targetPeripheral = discoveredPeripherals.find { peripheral ->
            peripheral.mMTFrameHandler.getMac() == macAddress
        }
        
        if (targetPeripheral != null) {
            connectToPeripheral(targetPeripheral)
        } else {
            Log.d(TAG, "No peripheral found with MAC: $macAddress")
            broadcastProcessResult(false)
        }
    }

    private fun connectToPeripheral(peripheral: MTPeripheral) {
        Log.d(TAG, "Connecting to peripheral: ${peripheral.mMTFrameHandler.getMac()}")
        val mainHandler = Handler(Looper.getMainLooper())
        
        // Stop scanning while connecting
        stopScanning()
        
        centralManager.connect(peripheral, object : ConnectionStatueListener {
            override fun onUpdateConnectionStatus(connectionStatus: ConnectionStatus, passwordListener: GetPasswordListener?) {
                mainHandler.post {
                    handleConnectionStatus(connectionStatus, passwordListener, peripheral)
                }
            }
                
            override fun onError(exception: MTException) {
                Log.e(TAG, "Connection error: ${exception.message}")
                resumeScanningAndBroadcastResult(false)
            }
        })
    }

    private fun handleConnectionStatus(
        status: ConnectionStatus, 
        passwordListener: GetPasswordListener?,
        peripheral: MTPeripheral
    ) {
        when (status) {
            ConnectionStatus.CONNECTING -> {
                Log.d(TAG, "Status: CONNECTING")
            }
            ConnectionStatus.PASSWORDVALIDATING -> {
                Log.d(TAG, "Status: PASSWORDVALIDATING")
                passwordListener?.getPassword(BEACON_PASSWORD)
            }
            ConnectionStatus.COMPLETED -> {
                Log.d(TAG, "Status: COMPLETED")
                configureDoubleTapTrigger(peripheral)
                centralManager.disconnect(peripheral)
                resumeScanningAndBroadcastResult(true)
            }
            else -> {
                Log.d(TAG, "Other connection status: $status")
            }
        }
    }

    private fun configureDoubleTapTrigger(peripheral: MTPeripheral) {
        try {
            val connectionHandler = peripheral.mMTConnectionHandler
            
            val trigger = Trigger().apply {
                setCurSlot(TRIGGER_SLOT)
                setTriggerType(TriggerType.BTN_DTAP_EVT)
                setCondition(TRIGGER_CONDITION_MS)
                setAlwaysAdvertising(false)
            }
            
            connectionHandler.setTriggerCondition(trigger, object : SetTriggerListener {
                override fun onSetTrigger(success: Boolean, exception: MTException?) {
                    if (success) {
                        Log.d(TAG, "Successfully configured double-tap trigger for slot $TRIGGER_SLOT")
                    } else {
                        Log.e(TAG, "Failed to configure trigger: ${exception?.message}")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error configuring trigger: ${e.message}")
        }
    }

    private fun resumeScanningAndBroadcastResult(success: Boolean) {
        // Restart scanning
        centralManager.startScan()
        
        // Broadcast the result
        broadcastProcessResult(success)
    }

    private fun broadcastProcessResult(success: Boolean) {
        val resultIntent = Intent(BROADCAST_MAC_RESULT).apply {
            putExtra("success", success)
            setPackage(packageName)
        }
        sendBroadcast(resultIntent)
    }

    override fun onDestroy() {
        stopScanning()
        centralManager.stopService()
        flutterEngine.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}