import 'package:flutter/services.dart';
import 'package:flutter/material.dart';
import 'package:vibration/vibration.dart';

class NativeBridge {
  static const MethodChannel _channel = MethodChannel('background_service');

  static Future<void> startBackgroundService() async {
    try {
      await _channel.invokeMethod('startService');
    } catch (e) {
      debugPrint("Error al iniciar el servicio: $e");
    }
  }

  static Future<void> stopBackgroundService() async {
    try {
      await _channel.invokeMethod('stopService');
    } catch (e) {
      debugPrint("Error al detener el servicio: $e");
    }
  }

  static Future<bool> isServiceRunning() async {
    try {
      final bool isRunning = await _channel.invokeMethod('isServiceRunning');
      debugPrint("Estado del servicio background: $isRunning");
      return isRunning;
    } catch (e) {
      debugPrint("Error al verificar el servicio: $e");
      return false;
    }
  }

  static Future<void> processMac(String mac) async {
    await _channel.invokeMethod('processMac', {'mac': mac});
  }

  static Future<void> clearScanning() async {
    await _channel.invokeMethod('clearScanning');
  }

  static void init() {
  _channel.setMethodCallHandler((call) async {
    if (call.method == "onScanerDoubleTapDetected") {
      final String mac = call.arguments as String;
      await onScanerDoubleTapDetected(mac);
    }
  });
}

  static Future<void> onScanerDoubleTapDetected(String macAddress) async {
    if (await Vibration.hasVibrator()) {
      try {
        await Vibration.vibrate();
      } catch (e) {
        debugPrint("Error al vibrar: $e");
      }
    }
    print('--- Alerta enviada desde $macAddress');

    /* Position currentPosition = await Geolocator.getCurrentPosition();
    var response = await IncidentService.createAlertFast(
      latitude: currentPosition.latitude,
      longitude: currentPosition.longitude,
    );
    if (response['status']) {
      print('--- Alerta enviada: $response');
    } else {
      print('--- Error al enviar alerta $response');
    } */
  }
}
