import 'dart:convert';
import 'dart:io';
import 'package:android_intent_plus/android_intent.dart';
import 'package:android_intent_plus/flag.dart';
import 'package:demo_broadcast_03/native_bridge.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'dart:async';
import 'package:permission_handler/permission_handler.dart';
import 'package:device_info_plus/device_info_plus.dart';

const _eventChannel = EventChannel('peripherals_stream');
const _macProcessResultStream = EventChannel('mac_process_result_stream');

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  if (Platform.isAndroid) {
    try {
      NativeBridge.init();
    } catch (e) {
      print('--- Error al iniciar el servicio background');
    }
  }

  runApp(const MainApp());
}

class MainApp extends StatefulWidget {
  const MainApp({super.key});

  @override
  State<MainApp> createState() => _MainAppState();
}

class _MainAppState extends State<MainApp> {
  List<Map<String, String>> peripherals = [];
  String processingResult = "";
  String processingMac = "";
  bool isProcessing = false;
  bool isServiceRunning = false;
  bool hasBluetoothPermissions = false;

  StreamSubscription? broadCastSuscription;
  StreamSubscription? macResultSubscription;

  @override
  void initState() {
    init();
    super.initState();
  }

  init() async {
    await checkBluetoohPermissions();
    await requestIgnoreBatteryOptimizationsIntent();
    await requestLocationAlways();
  }

  checkBluetoohPermissions() async {
    if (await Permission.bluetoothScan.isGranted &&
        await Permission.bluetoothConnect.isGranted) {
      hasBluetoothPermissions = true;
      setState(() {});
    }
  }

  Future<bool> isIgnoringBatteryOptimizations() async {
    if (!Platform.isAndroid) return true;

    const platform = MethodChannel('battery_optimizations');

    try {
      return await platform.invokeMethod('isIgnoringBatteryOptimizations');
    } on PlatformException catch (e) {
      print('Error checking battery optimizations: $e');
      return true;
    }
  }

  requestIgnoreBatteryOptimizationsIntent() async {
    if (!Platform.isAndroid) return;

    final isIgnoring = await isIgnoringBatteryOptimizations();
    if (!isIgnoring) {
      final info = await DeviceInfoPlugin().androidInfo;
      final sdkInt = info.version.sdkInt;
      const packageName = 'com.example.demo_broadcast_03';

      if (sdkInt >= 23) {
        const intent = AndroidIntent(
          action: 'android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS',
          data: 'package:$packageName',
          flags: <int>[Flag.FLAG_ACTIVITY_NEW_TASK],
        );
        await intent.launch();
      }
    }
  }

  requestLocationAlways() async {
    if (await Permission.locationWhenInUse.request().isGranted) {
      final statusAlways = await Permission.locationAlways.request();
      return statusAlways.isGranted;
    }
    return false;
  }

  startListening() {
    // Escuchar la lista de periféricos
    broadCastSuscription =
        _eventChannel.receiveBroadcastStream().listen((data) {
      final List<dynamic> decoded = json.decode(data as String);
      peripherals =
          decoded.map((e) => Map<String, String>.from(e as Map)).toList();
      setState(() {});
    });

    // Escuchar los resultados del procesamiento MAC
    macResultSubscription =
        _macProcessResultStream.receiveBroadcastStream().listen((data) {
      final bool success = data as bool;
      setState(() {
        processingResult = success
            ? "¡Procesamiento exitoso para $processingMac!"
            : "Procesamiento fallido para $processingMac";
        isProcessing = false;
      });
      //Reset service
      //resetService();
    });
  }

  resetService() async {
    if (await NativeBridge.isScanerServiceRunning()) {
      await NativeBridge.stopScanerService();
      await NativeBridge.startScanerService();
    }
  }

  stopListening() {
    broadCastSuscription?.cancel();
    broadCastSuscription = null;

    macResultSubscription?.cancel();
    macResultSubscription = null;
  }

  Future<void> processMac(String mac) async {
    setState(() {
      processingMac = mac;
      processingResult = "Procesando $mac...";
      isProcessing = true;
    });
    await NativeBridge.processMac(mac);
  }

  solicitarPermisosBluetooth() async {
    if (await Permission.location.request().isGranted) {
      await [
        Permission.bluetoothScan,
        Permission.bluetoothConnect,
      ].request();
    }

    await checkBluetoohPermissions();
  }

  @override
  void dispose() {
    stopListening();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      home: Scaffold(
        appBar: AppBar(title: const Text('Lista desde Android')),
        body: Column(
          children: [
            if (processingResult.isNotEmpty)
              Padding(
                padding: const EdgeInsets.all(8.0),
                child: Card(
                  color: processingResult.contains("exitoso")
                      ? Colors.green[100]
                      : (processingResult.contains("fallido")
                          ? Colors.red[100]
                          : Colors.blue[100]),
                  child: Padding(
                    padding: const EdgeInsets.all(16.0),
                    child: Row(
                      children: [
                        if (isProcessing)
                          const SizedBox(
                            width: 20,
                            height: 20,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          ),
                        const SizedBox(width: 10),
                        Expanded(child: Text(processingResult)),
                      ],
                    ),
                  ),
                ),
              ),
            Expanded(
              child: ListView.builder(
                itemCount: peripherals.length,
                itemBuilder: (context, index) {
                  final device = peripherals[index];
                  final String name =
                      device['name'] ?? 'Dispositivo sin nombre';
                  final String mac = device['mac'] ?? 'No MAC';
                  final String url = device['url'] ?? 'No URL';

                  return Card(
                    margin:
                        const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                    child: InkWell(
                      onTap: () => processMac(mac),
                      child: Padding(
                        padding: const EdgeInsets.all(12.0),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              name,
                              style: const TextStyle(
                                fontWeight: FontWeight.bold,
                                fontSize: 16,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text('MAC: $mac'),
                            if (url != 'No URL') Text('URL: $url'),
                            const SizedBox(height: 8),
                            Align(
                              alignment: Alignment.centerRight,
                              child: ElevatedButton(
                                onPressed: () => processMac(mac),
                                child: const Text('Procesar'),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                ElevatedButton(
                  onPressed: () async {
                    if (!hasBluetoothPermissions) return;
                    if (isServiceRunning) {
                      stopListening();
                      await NativeBridge.stopScanerService();
                    } else {
                      await NativeBridge.startScanerService();
                      startListening();
                    }
                    isServiceRunning = await NativeBridge.isScanerServiceRunning();
                    setState(() {});
                  },
                  child: Text(
                      isServiceRunning ? 'Detener Escaner' : 'Iniciar Escaner'),
                ),
                ElevatedButton(
                  onPressed: () async {
                    if (!hasBluetoothPermissions) return;
                    if (isServiceRunning) {
                      await NativeBridge.clearScanning();
                    }
                  },
                  child: const Text('Limpiar'),
                ),
                if (!hasBluetoothPermissions)
                  ElevatedButton(
                    onPressed: solicitarPermisosBluetooth,
                    child: const Text('Bluetooh Permission'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
