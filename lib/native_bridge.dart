import 'package:flutter/services.dart';
import 'package:flutter/material.dart';

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

  static Future<bool> processMac(String mac) async {
    return await _channel.invokeMethod('processMac', {'mac': mac});
  }
}
