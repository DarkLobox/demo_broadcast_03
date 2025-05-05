package com.example.demo_broadcast_03

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.NonNull
import android.os.Handler
import android.os.Looper

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

import com.minew.beaconplus.sdk.MTCentralManager
import com.minew.beaconplus.sdk.interfaces.MTCentralManagerListener
import com.minew.beaconplus.sdk.MTPeripheral
import com.minew.beaconplus.sdk.frames.MinewFrame
import com.minew.beaconplus.sdk.enums.FrameType
import com.minew.beaconplus.sdk.frames.UrlFrame
import com.minew.beaconplus.sdk.interfaces.ConnectionStatueListener
import com.minew.beaconplus.sdk.enums.ConnectionStatus
import com.minew.beaconplus.sdk.interfaces.GetPasswordListener
import com.minew.beaconplus.sdk.exception.MTException
import com.minew.beaconplus.sdk.model.Trigger
import com.minew.beaconplus.sdk.interfaces.SetTriggerListener
import com.minew.beaconplus.sdk.enums.TriggerType;

import org.json.JSONArray
import org.json.JSONObject

class MainActivity : FlutterActivity(), EventChannel.StreamHandler {
    private val METHOD_CHANNEL = "background_service"
    private val PERIPHERALS_EVENT_CHANNEL = "peripherals_stream"
    private val MAC_RESULT_EVENT_CHANNEL = "mac_process_result_stream"
    
    private lateinit var mtCentralManager: MTCentralManager
    private val peripherals = mutableListOf<MTPeripheral>()
    private var isScanning = false
    
    private var peripheralsEventSink: EventChannel.EventSink? = null
    private var macResultEventSink: EventChannel.EventSink? = null
    
    private var peripheralsReceiver: BroadcastReceiver? = null
    private var macResultReceiver: BroadcastReceiver? = null

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        
        // Initialize MTCentralManager
        mtCentralManager = MTCentralManager.getInstance(this)
        mtCentralManager.startService()

        // Set up MethodChannel
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "startService" -> {
                    startScanning()
                    result.success("Scanning started")
                }
                "stopService" -> {
                    stopScanning()
                    result.success("Scanning stopped")
                }
                "processMac" -> {
                    val mac = call.argument<String>("mac")
                    if (mac != null) {
                        processMacAddress(mac)
                        result.success("Processing MAC: $mac")
                    } else {
                        result.error("INVALID_ARGUMENT", "MAC address is required", null)
                    }
                }
                "isServiceRunning" -> {
                    result.success(isScanning)
                }
                else -> {
                    result.notImplemented()
                }
            }
        }
        
        // Set up EventChannels
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, PERIPHERALS_EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    peripheralsEventSink = events
                    registerPeripheralsReceiver()
                }

                override fun onCancel(arguments: Any?) {
                    unregisterPeripheralsReceiver()
                    peripheralsEventSink = null
                }
            }
        )
        
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, MAC_RESULT_EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    macResultEventSink = events
                    registerMacResultReceiver()
                }

                override fun onCancel(arguments: Any?) {
                    unregisterMacResultReceiver()
                    macResultEventSink = null
                }
            }
        )
    }

    private fun registerPeripheralsReceiver() {
        peripheralsReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val peripheralsJson = intent.getStringExtra("peripherals")
                if (peripheralsJson != null && peripheralsEventSink != null) {
                    peripheralsEventSink?.success(peripheralsJson)
                }
            }
        }
        
        val filter = IntentFilter("com.example.demo_broadcast_03.PERIPHERALS_JSON")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(peripheralsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(peripheralsReceiver, filter)
        }
    }

    private fun unregisterPeripheralsReceiver() {
        peripheralsReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.e("MainActivity", "Receiver not registered", e)
            }
            peripheralsReceiver = null
        }
    }

    private fun registerMacResultReceiver() {
        macResultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val success = intent.getBooleanExtra("success", false)
                if (macResultEventSink != null) {
                    macResultEventSink?.success(success)
                }
            }
        }
        
        val filter = IntentFilter("com.example.demo_broadcast_03.MAC_PROCESS_RESULT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(macResultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(macResultReceiver, filter)
        }
    }

    private fun unregisterMacResultReceiver() {
        macResultReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                Log.e("MainActivity", "Receiver not registered", e)
            }
            macResultReceiver = null
        }
    }

    private fun startScanning() {
        if (!isScanning) {
            Log.d("MainActivity", "Starting BLE scan")
            mtCentralManager.startScan()
            isScanning = true
            
            mtCentralManager.setMTCentralManagerListener(object : MTCentralManagerListener {
                override fun onScanedPeripheral(list: List<MTPeripheral>) {
                    peripherals.clear()
                    peripherals.addAll(list)

                    val jsonArray = JSONArray()

                    for (mt in peripherals) {
                        val handler = mt.mMTFrameHandler
                        val obj = JSONObject().apply {
                            put("mac", handler.getMac())
                            put("name", handler.getName() ?: JSONObject.NULL)
                        }

                        val advFrames: ArrayList<MinewFrame> = handler.getAdvFrames()
                        for (minewFrame in advFrames) {
                            when (minewFrame.getFrameType()) {
                                FrameType.FrameURL -> {
                                    val urlFrame = minewFrame as UrlFrame
                                    obj.put("url", urlFrame.getUrlString())
                                }
                                else -> { /* Other frames not used */ }
                            }
                        }

                        jsonArray.put(obj)
                        Log.d("MainActivity", "Device found: $obj")
                    }

                    val broadcastIntent = Intent("com.example.demo_broadcast_03.PERIPHERALS_JSON")
                        .apply {
                            putExtra("peripherals", jsonArray.toString())
                            setPackage(packageName)
                        }

                    sendBroadcast(broadcastIntent)
                }
            })
        } else {
            Log.d("MainActivity", "Scan already in progress")
        }
    }

    private fun stopScanning() {
        if (isScanning) {
            Log.d("MainActivity", "Stopping BLE scan")
            mtCentralManager.stopScan()
            isScanning = false
        }
    }

    private fun configureTriggerForSlot2(peripheral: MTPeripheral) {
        try {
            val connectionHandler = peripheral.mMTConnectionHandler
            
            val trigger = Trigger()
            trigger.setCurSlot(2)
            trigger.setTriggerType(TriggerType.BTN_DTAP_EVT)
            trigger.setCondition(5000)
            trigger.setAlwaysAdvertising(false)
            
            connectionHandler.setTriggerCondition(trigger, object : SetTriggerListener {
                override fun onSetTrigger(success: Boolean, mtException: MTException?) {
                    if (success) {
                        Log.d("MainActivity", "Successfully configured trigger for slot 3")
                    } else {
                        Log.e("MainActivity", "Failed to configure trigger for slot 3: ${mtException?.message}")
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("MainActivity", "Error configuring trigger: ${e.message}")
        }
    }

    private fun processMacAddress(mac: String) {
        Log.d("MainActivity", "Processing MAC: $mac")
        
        // Find the peripheral with the specific MAC
        val foundPeripheral = peripherals.find { peripheral ->
            peripheral.mMTFrameHandler.getMac() == mac
        }

        var success = false

        if (foundPeripheral != null) {
            Log.d("MainActivity", "Peripheral found with MAC: $mac")
            
            mtCentralManager.stopScan()
    
            // Connect to the peripheral
            mtCentralManager.connect(foundPeripheral, object : ConnectionStatueListener {
                    override fun onUpdateConnectionStatus(connectionStatus: ConnectionStatus, getPasswordListener: GetPasswordListener?) {
                        runOnUiThread {
                            when (connectionStatus) {
                                ConnectionStatus.CONNECTING -> {
                                    Log.e("MainActivity", ": CONNECTING")
                                }
                                ConnectionStatus.PASSWORDVALIDATING -> {
                                    Log.d("MainActivity", "Connection PASSWORDVALIDATING")
                                    val password = "minew123"
                                    getPasswordListener?.getPassword(password)
                                }
                                ConnectionStatus.COMPLETED -> {
                                    Log.d("MainActivity", "Connection COMPLETED")

                                    // Configure trigger params for slot 2
                                    configureTriggerForSlot2(foundPeripheral)

                                    success = true
                                    
                                    // Disconnect from the device after successful connection
                                    mtCentralManager.disconnect(foundPeripheral)
                                    
                                    mtCentralManager.startScan()

                                    // Send the result via broadcast
                                    val resultIntent = Intent("com.example.demo_broadcast_03.MAC_PROCESS_RESULT").apply {
                                        putExtra("success", success)
                                        setPackage(packageName)
                                    }
                                    sendBroadcast(resultIntent)
                                }
                                else -> {
                                    Log.d("MainActivity", "Other connection status: $connectionStatus")
                                }
                            }
                        }
                    }
                    
                    override fun onError(mtException: MTException) {
                        Log.e("MainActivity", "Connection error: ${mtException.message}")
                        
                        mtCentralManager.startScan()

                        // Send failure result via broadcast
                        val resultIntent = Intent("com.example.demo_broadcast_03.MAC_PROCESS_RESULT").apply {
                            putExtra("success", false)
                            setPackage(packageName)
                        }
                        sendBroadcast(resultIntent)
                    }
                })
            
            
        } else {
            Log.d("MainActivity", "No peripheral found with MAC: $mac")
        }
    }

    override fun onDestroy() {
        stopScanning()
        unregisterPeripheralsReceiver()
        unregisterMacResultReceiver()
        mtCentralManager.clear()
        super.onDestroy()
    }

    // Required by EventChannel.StreamHandler but not used directly since we're implementing custom receivers
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {}
    override fun onCancel(arguments: Any?) {}
}