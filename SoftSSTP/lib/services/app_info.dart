import 'package:flutter/services.dart';

/// Application metadata (name, package id, version) read from the native
/// Android package manager via a MethodChannel. Kept dependency-free so the
/// project does not need an extra plugin.
class AppInfo {
  static const MethodChannel _channel = MethodChannel('softsstp/app_info');

  final String appName;
  final String packageName;
  final String versionName;
  final int versionCode;

  const AppInfo({
    required this.appName,
    required this.packageName,
    required this.versionName,
    required this.versionCode,
  });

  /// Human-readable version, e.g. "1.0.0 (1)".
  String get displayVersion => '$versionName ($versionCode)';

  static Future<AppInfo> load() async {
    try {
      final result =
          await _channel.invokeMapMethod<String, dynamic>('getAppInfo');
      if (result != null) {
        return AppInfo(
          appName: (result['appName'] as String?) ?? 'SoftSSTP',
          packageName: (result['packageName'] as String?) ?? '',
          versionName: (result['versionName'] as String?) ?? 'unknown',
          versionCode: (result['versionCode'] as num?)?.toInt() ?? 0,
        );
      }
    } catch (_) {
      // Fall through to a safe placeholder below.
    }
    return const AppInfo(
      appName: 'SoftSSTP',
      packageName: '',
      versionName: 'unknown',
      versionCode: 0,
    );
  }
}
