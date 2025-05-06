import 'package:shared_preferences/shared_preferences.dart';

class StorageUtil {
  static Future<void> saveFlag(String key, bool value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(key, value);
  }

  static Future<bool> getFlag(String key) async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getBool(key) ?? false;
  }

  static Future<void> saveDate(String key, DateTime value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(key, value.toIso8601String());
  }

  static Future<DateTime> getDate(String key) async {
    final prefs = await SharedPreferences.getInstance();
    final dateString = prefs.getString(key);
    return dateString != null
        ? DateTime.tryParse(dateString) ?? DateTime.now()
        : DateTime.now();
  }
}
