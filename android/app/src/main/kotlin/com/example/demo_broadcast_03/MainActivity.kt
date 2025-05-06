package com.example.demo_broadcast_03

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity(), EventChannel.StreamHandler {
    companion object {
        private const val METHOD_CHANNEL = "background_service"
        private const val PERIPHERALS_EVENT_CHANNEL = "peripherals_stream"
        private const val MAC_RESULT_EVENT_CHANNEL = "mac_process_result_stream"

        private const val ACTION_PROCESS_MAC = "processMac"
        private const val ACTION_CLEAR_SCANNING = "ACTION_CLEAR_SCANNING"
        private const val EXTRA_MAC = "mac"
        private const val EXTRA_PERIPHERALS = "peripherals"
        private const val EXTRA_SUCCESS = "success"

        private const val PACKAGE_NAME = "com.example.demo_broadcast_03"
        private val ACTION_MAC_PROCESS_RESULT = "$PACKAGE_NAME.MAC_PROCESS_RESULT"
        private val ACTION_PERIPHERALS_JSON = "$PACKAGE_NAME.PERIPHERALS_JSON"
        private const val LOG_TAG = "AndroidLogMainActivity"
    }

    private var peripheralsEventSink: EventChannel.EventSink? = null
    private var macResultEventSink: EventChannel.EventSink? = null

    private var peripheralsReceiver: BroadcastReceiver? = null
    private var macResultReceiver: BroadcastReceiver? = null

    // Configura el canal de métodos para recibir las llamadas desde Flutter
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startService" -> {
                    startService(Intent(this, ScanerService::class.java))
                    result.success(true)
                }
                "stopService" -> {
                    stopService(Intent(this, ScanerService::class.java))
                    result.success(true)
                }
                "isServiceRunning" -> {
                    result.success(isServiceRunning(ScanerService::class.java))
                }
                "processMac" -> {
                    if (isServiceRunning(ScanerService::class.java)) {
                        val macAddress = call.argument<String>(EXTRA_MAC)
                        Intent(this, ScanerService::class.java).apply {
                            action = ACTION_PROCESS_MAC
                            putExtra(EXTRA_MAC, macAddress)
                            startService(this)
                        }
                        result.success(true)
                    } else {
                        result.error("SERVICE_NOT_RUNNING", "The service is not running", null)
                    }
                }
                "clearScanning" -> {
                    if (isServiceRunning(ScanerService::class.java)) {
                        Intent(this, ScanerService::class.java).apply {
                            action = ACTION_CLEAR_SCANNING
                            startService(this)
                        }
                        result.success(true)
                    } else {
                        result.error("SERVICE_NOT_RUNNING", "The service is not running", null)
                    }
                }
                else -> result.notImplemented()
            }
        }

        // Configura el canal de eventos para recibir actualizaciones de los periféricos
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, PERIPHERALS_EVENT_CHANNEL).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                peripheralsEventSink = events
                registerPeripheralsReceiver()
            }

            override fun onCancel(arguments: Any?) {
                unregisterPeripheralsReceiver()
                peripheralsEventSink = null
            }
        })

        // Configura el canal de eventos para recibir el resultado del procesamiento de MAC
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, MAC_RESULT_EVENT_CHANNEL).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                macResultEventSink = events
                registerMacResultReceiver()
            }

            override fun onCancel(arguments: Any?) {
                unregisterMacResultReceiver()
                macResultEventSink = null
            }
        })
    }

    // Registra el receptor de periféricos
    private fun registerPeripheralsReceiver() {
        peripheralsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                intent.getStringExtra(EXTRA_PERIPHERALS)?.let { peripheralsEventSink?.success(it) }
            }
        }

        val filter = IntentFilter(ACTION_PERIPHERALS_JSON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(peripheralsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(peripheralsReceiver, filter)
        }
    }

    // Elimina el receptor de periféricos
    private fun unregisterPeripheralsReceiver() {
        peripheralsReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.e(LOG_TAG, "Receiver not registered", e)
            }
            peripheralsReceiver = null
        }
    }

    // Registra el receptor para el resultado del procesamiento de la MAC
    private fun registerMacResultReceiver() {
        macResultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val success = intent.getBooleanExtra(EXTRA_SUCCESS, false)
                macResultEventSink?.success(success)
            }
        }

        val filter = IntentFilter(ACTION_MAC_PROCESS_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(macResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(macResultReceiver, filter)
        }
    }

    // Elimina el receptor para el resultado del procesamiento de la MAC
    private fun unregisterMacResultReceiver() {
        macResultReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.e(LOG_TAG, "Receiver not registered", e)
            }
            macResultReceiver = null
        }
    }

    // Limpiar cuando la actividad sea destruida
    override fun onDestroy() {
        unregisterPeripheralsReceiver()
        unregisterMacResultReceiver()
        super.onDestroy()
    }

    // Verifica si el servicio está en ejecución
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { it.service.className == serviceClass.name }
    }

    // Métodos requeridos por el EventChannel.StreamHandler pero no utilizados directamente
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {}
    override fun onCancel(arguments: Any?) {}
}
