import 'package:demo_broadcast_03/storage_util.dart';
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
    const flagKey = 'beacon_flag';
    const dateKey = 'beacon_date';

    final flag = await StorageUtil.getFlag(flagKey);
    if (flag) return;

    await StorageUtil.saveFlag(flagKey, true);

    final lastDate = await StorageUtil.getDate(dateKey);
    final now = DateTime.now();

    final shouldVibrate =
        lastDate == null || now.difference(lastDate).inSeconds >= 6;
    if (shouldVibrate) {
      if (await Vibration.hasVibrator()) {
        try {
          await Vibration.vibrate();
        } catch (e) {
          debugPrint("Error al vibrar: $e");
        }
      }

      print('--- Alerta enviada desde $macAddress');
      await StorageUtil.saveDate(dateKey, now);
    }

    await StorageUtil.saveFlag(flagKey, false);
  }
}
