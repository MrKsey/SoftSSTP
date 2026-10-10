import 'package:flutter/services.dart';

/// Opens external links in the device browser through a small native channel.
///
/// Kept dependency-free (no `url_launcher` plugin) because the app only needs
/// to launch `ACTION_VIEW` intents for the repository links in the About
/// section.
class LinkLauncher {
  static const MethodChannel _channel = MethodChannel('softsstp/links');

  static Future<void> open(String url) async {
    try {
      await _channel.invokeMethod('openUrl', {'url': url});
    } catch (_) {
      // Opening a link must never crash the settings screen.
    }
  }
}
