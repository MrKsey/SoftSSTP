import 'package:flutter/services.dart';

/// Bridges the native default-network callback (`ConnectivityManager`) to Dart.
///
/// Used to refresh the direct public IP when the device switches between Wi-Fi
/// and mobile data while the tunnel is down.
class NetworkMonitor {
  static const MethodChannel _channel = MethodChannel('softsstp/network');

  static bool _listening = false;

  static void listen(void Function() onChanged) {
    if (_listening) return;
    _listening = true;
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'onNetworkChanged') {
        onChanged();
      }
      return null;
    });
  }
}
