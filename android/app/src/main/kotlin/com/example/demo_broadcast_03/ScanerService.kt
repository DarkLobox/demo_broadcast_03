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
        private const val TAG = "AndroidLogScanerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "smartmuni_beacon_service"
        private const val CHANNEL_NAME = "Smart Muni Beacon Service"
        private const val ACTION_PROCESS_MAC = "processMac"
        private const val ACTION_CLEAR_SCANNING = "ACTION_CLEAR_SCANNING"
        private const val PACKAGE_NAME = "com.example.demo_broadcast_03"
        private const val FLUTTER_METHOD_CHANNEL = "background_service"
        private const val BROADCAST_PERIPHERALS = "$PACKAGE_NAME.PERIPHERALS_JSON"
        private const val BROADCAST_MAC_RESULT = "$PACKAGE_NAME.MAC_PROCESS_RESULT"
        private const val BEACON_PASSWORD = "minew123"
        private const val TRIGGER_SLOT = 2
        private const val TRIGGER_CONDITION_MS = 5000
        private const val CLEAR_SCANNING_DELAY_MS = 5000L
    }

    private lateinit var flutterEngine: FlutterEngine
    private lateinit var methodChannel: MethodChannel
    private lateinit var centralManager: MTCentralManager
    private val discoveredPeripherals = mutableListOf<MTPeripheral>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var clearScanningRunnable: Runnable? = null
    private var isClearScanningScheduled = false
    
    override fun onCreate() {
        super.onCreate()
        initializeFlutterEngine()
        createNotificationChannel()
        startForegroundService()
        initializeBluetoothManager()
    }

    // Inicializa el motor de Flutter
    private fun initializeFlutterEngine() {
        flutterEngine = FlutterEngine(this)
        flutterEngine.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint.createDefault()
        )
        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, FLUTTER_METHOD_CHANNEL)
    }

    // Crea el canal de notificación para el servicio en segundo plano
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

    // Inicia el servicio en primer plano
    private fun startForegroundService() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Emergency Button Scanner")
            .setContentText("Scanning for nearby emergency beacons")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    // Inicializa el administrador de Bluetooth
    private fun initializeBluetoothManager() {
        centralManager = MTCentralManager.getInstance(this)
        centralManager.startService()
        startScanning()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_PROCESS_MAC -> {
                    val macAddress = it.getStringExtra("mac")
                    if (!macAddress.isNullOrBlank()) {
                        processMacAddress(macAddress)
                    }
                }
                ACTION_CLEAR_SCANNING -> {
                    clearScanning()
                }
            }
        }
        return START_STICKY
    }

    // Inicia el escaneo de dispositivos Bluetooth
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

    // Maneja los periféricos descubiertos y los convierte en un JSON
    private fun handleDiscoveredPeripherals(peripherals: List<MTPeripheral>) {
        discoveredPeripherals.clear()
        discoveredPeripherals.addAll(peripherals)

        val peripheralsJson = convertPeripheralsToJson()
        broadcastPeripherals(peripheralsJson)
    }

    // Convierte los periféricos descubiertos en un objeto JSON
    private fun convertPeripheralsToJson(): JSONArray {
        val jsonArray = JSONArray()

        for (peripheral in discoveredPeripherals) {
            val handler = peripheral.mMTFrameHandler
            val deviceJson = JSONObject().apply {
                put("mac", handler.getMac())
                put("name", handler.getName() ?: JSONObject.NULL)
            }

            val advFrames: ArrayList<MinewFrame> = handler.getAdvFrames()
            for (frame in advFrames) {
                if (frame.getFrameType() == FrameType.FrameURL) {
                    val urlFrame = frame as UrlFrame
                    deviceJson.put("url", urlFrame.getUrlString())
                    onScanerDoubleTapDetected(handler.getMac())
                    scheduleClearScanning()
                }
            }

            jsonArray.put(deviceJson)
            Log.d(TAG, "Device found: $deviceJson")
        }
        return jsonArray
    }

    // Programa la llamada a clearScanning después de 5 segundos, solo si no está ya programada
    private fun scheduleClearScanning() {
        if (!isClearScanningScheduled) {
            isClearScanningScheduled = true
            Log.d(TAG, "Scheduling clearScanning in 5 seconds")
            
            // Crear un nuevo Runnable que ejecutará clearScanning
            clearScanningRunnable = Runnable {
                Log.d(TAG, "Executing scheduled clearScanning")
                clearScanning()
                isClearScanningScheduled = false
            }
            
            // Programar el Runnable para ejecutarse después de 5 segundos
            mainHandler.postDelayed(clearScanningRunnable!!, CLEAR_SCANNING_DELAY_MS)
        }
    }

    // Envía los periféricos descubiertos como un broadcast
    private fun broadcastPeripherals(peripheralsJson: JSONArray) {
        val broadcastIntent = Intent(BROADCAST_PERIPHERALS).apply {
            putExtra("peripherals", peripheralsJson.toString())
            setPackage(packageName)
        }
        sendBroadcast(broadcastIntent)
    }

    // Detiene el escaneo de Bluetooth
    private fun stopScanning() {
        if (centralManager.isScanning()) {
            Log.d(TAG, "Stopping BLE scan")
            centralManager.stopScan()
        }
    }

    // Procesa una dirección MAC específica
    private fun processMacAddress(macAddress: String) {
        Log.d(TAG, "Processing MAC: $macAddress")
        
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

    // Conecta al periférico con la MAC proporcionada
    private fun connectToPeripheral(peripheral: MTPeripheral) {
        Log.d(TAG, "Connecting to peripheral: ${peripheral.mMTFrameHandler.getMac()}")
        val mainHandler = Handler(Looper.getMainLooper())
        
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

    // Maneja el estado de la conexión con el periférico
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

    // Configura el trigger de doble toque en el periférico
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

    // Reinicia el escaneo y transmite el resultado
    private fun resumeScanningAndBroadcastResult(success: Boolean) {
        centralManager.startScan()
        broadcastProcessResult(success)
    }

    // Envía el resultado del procesamiento de la MAC
    private fun broadcastProcessResult(success: Boolean) {
        val resultIntent = Intent(BROADCAST_MAC_RESULT).apply {
            putExtra("success", success)
            setPackage(packageName)
        }
        sendBroadcast(resultIntent)
    }

    // Limpia la cache del escaner
    private fun clearScanning() {
        Log.d(TAG, "Clear BLE scan")
        if (centralManager.isScanning()) {
            centralManager.stopScan()
        }
        centralManager.clear()
        if (!centralManager.isScanning()) {
            centralManager.startScan()
        }
    }

    // LLamada a evento
    private fun onScanerDoubleTapDetected(macAddress: String) {
        methodChannel.invokeMethod("onScanerDoubleTapDetected", macAddress, object : MethodChannel.Result {
            override fun success(result: Any?) {
                Log.d(TAG, "Método Flutter ejecutado correctamente: $result")
            }
    
            override fun error(errorCode: String, errorMessage: String?, errorDetails: Any?) {
                Log.e(TAG, "Error al ejecutar método Flutter: $errorMessage")
            }
    
            override fun notImplemented() {
                Log.e(TAG, "Método Flutter no implementado")
            }
        })
    }

    override fun onDestroy() {
        stopScanning()
        centralManager.stopService()
        centralManager.clear()
        flutterEngine.destroy()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
