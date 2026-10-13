import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:sstp_flutter/src/core/Utils/utils.dart';
import 'package:sstp_flutter/traffic.dart';
import 'package:sstp_flutter/app_info.dart';
import 'package:sstp_flutter/proxy.dart';
import 'package:sstp_flutter/server.dart';
import 'package:sstp_flutter/src/data/provider/sstp_flutter_method_channel.dart';

typedef OnConnected = Function(ConnectionTraffic traffic, Duration duration);
typedef OnConnecting = void Function();
typedef OnDisconnected = void Function();
typedef OnError = Function();
typedef OnAssignedIp = void Function(String ip);

/// Invoked while the client is automatically retrying after a dropped tunnel.
/// [message] is a human readable label, e.g. "Reconnecting... (attempt 2)".
typedef OnReconnecting = void Function(String message);

/// Invoked when a connection attempt fails for a reason the user can act on.
/// [code] is a stable identifier (e.g. "VPN_ESTABLISH_FAILED") and [message]
/// is a human readable explanation suitable for display.
typedef OnConnectionError = void Function(String code, String message);

class SstpFlutter {
  Timer? _timer;
  int _downloadSpeed = 0;
  int _uploadSpeed = 0;
  int _totalDownload = 0;
  int _totalUpload = 0;
  String _assignedIp = "";
  MethodChannelSstpFlutter channelHandler = MethodChannelSstpFlutter();
  OnConnected? onConnectedResult;
  OnAssignedIp? onAssignedIpResult;
  OnConnectionError? onConnectionErrorResult;
  MethodChannel channel = const MethodChannel("responseReceiver");

  /// The IPv4 address assigned by the server for the current connection.
  String get assignedIp => _assignedIp;

  /// Gains result of current connection status
  /// [OnConnected] get invoked when [ConnectionTraffic] get updates
  ///
  /// NOTE: download and upload speed is under development on iOS
  Future onResult({
    OnConnected? onConnectedResult,
    OnConnecting? onConnectingResult,
    OnDisconnected? onDisconnectedResult,
    OnError? onError,
    OnAssignedIp? onAssignedIpResult,
    OnReconnecting? onReconnectingResult,
    OnConnectionError? onConnectionErrorResult,
  }) async {
    this.onConnectedResult = onConnectedResult;
    this.onAssignedIpResult = onAssignedIpResult;
    this.onConnectionErrorResult = onConnectionErrorResult;

    Future methodCallReceiver(MethodCall call) async {
      var arg = call.arguments;

      if (call.method == 'connectResponse') {
        if (arg["status"] == SSTPConnectionStatusKeys.CONNECTED) {
          _startConnectedTimer();
          onConnectedResult!(ConnectionTraffic(), Duration.zero);
        } else if (arg["status"] == SSTPConnectionStatusKeys.CONNECTING) {
          _stopConnectedTimer();
          _assignedIp = "";
          onConnectingResult!();
        } else if (arg["status"] == SSTPConnectionStatusKeys.RECONNECTING) {
          _stopConnectedTimer();
          _assignedIp = "";
          onReconnectingResult
              ?.call((arg["message"] as String?) ?? "Reconnecting...");
        } else if (arg["status"] == SSTPConnectionStatusKeys.DISCONNECTED) {
          _stopConnectedTimer();
          _assignedIp = "";
          onDisconnectedResult!();
          bool? error = arg["error"];
          if (error != null && error) {
            onError!();
            final code = (arg["errorCode"] as String?) ?? "";
            final message = (arg["errorMessage"] as String?) ?? "";
            if (message.isNotEmpty) {
              onConnectionErrorResult?.call(code, message);
            }
          }
        }
      } else if (call.method == 'assignedIp') {
        _assignedIp = (arg["ip"] as String?) ?? "";
        onAssignedIpResult?.call(_assignedIp);
      } else if (call.method == 'downloadSpeed') {
        _downloadSpeed += call.arguments[0] as int;
        _totalDownload = call.arguments[1] as int;
      } else if (call.method == 'uploadSpeed') {
        _uploadSpeed += call.arguments[0] as int;
        _totalUpload = call.arguments[1] as int;
      }
    }

    channel.setMethodCallHandler(methodCallReceiver);
  }

  void _stopConnectedTimer() {
    _timer?.cancel();
    _timer = null;
    _downloadSpeed = 0;
    _uploadSpeed = 0;
    _totalDownload = 0;
    _totalUpload = 0;
  }

  void _startConnectedTimer({int baseSeconds = 0}) {
    // Never keep two timers alive: onResult and checkLastConnectionStatus may
    // both fire when the app is resumed while connected.
    _timer?.cancel();
    _timer = Timer.periodic(
      const Duration(seconds: 1),
      (timer) {
        onConnectedResult?.call(
          ConnectionTraffic(
            totalDownloadTraffic: _totalDownload,
            totalUploadTraffic: _totalUpload,
            downloadTraffic: _downloadSpeed,
            uploadTraffic: _uploadSpeed,
          ),
          Duration(seconds: baseSeconds + timer.tick),
        );
        _uploadSpeed = 0;
        _downloadSpeed = 0;
      },
    );
  }

  /// Tries to take vpn permission.
  ///
  /// Claims the VPN slot for this app, dethroning any other active VPN. Returns
  /// true when the app is (or has just become) the prepared owner, false when
  /// the user still has to grant consent.
  ///
  /// If the user declines the permission, it will appear again on the next call
  /// until the permission is granted.
  Future<bool> takePermission() async {
    try {
      return await channelHandler.vpnPermission();
    } catch (e) {
      debugPrint(e.toString());
      rethrow;
    }
  }

  /// Starts connection between client and provided server config in [saveServerData]
  /// Before try to connect , make sure you have saved your server config using [saveServerData]
  Future connectVpn() async {
    try {
      var caller = await channelHandler.connectVpn();
      return caller;
    } on PlatformException catch (e) {
      debugPrint(e.toString());
      rethrow;
    }
  }

  /// Disconnects current running sstp connection
  Future disconnect() async {
    try {
      await channelHandler.disconnect();
    } catch (e) {
      rethrow;
    }
  }

  /// Returns all installed apps on user's device
  /// WARNING : it's available only on Android
  Future<List<InstalledAppInfo>> getInstalledApps() async {
    List<InstalledAppInfo> apps = await channelHandler.getInstalledApps();
    return apps;
  }

  /// Returns all allowed apps package name
  ///
  /// These packages are allowed to get tunneled , except that they're not getting tunneled
  ///
  /// fixed bug reported in : [https://github.com/NavidShokoufeh/sstp_flutter/issues/8]
  ///
  /// WARNING : it's available only on Android
  Future<List<String>> getAllowedApps() async {
    List<String> packages = await channelHandler.getAllowedApps();
    return packages;
  }

  /// Adds provided List of package names to allowed apps to get tunneled
  /// you can simply get installed apps package names using [getInstalledApps]
  ///
  /// WARNING : it's available only on Android
  Future addToAllowedApps({required List<String> packages}) async {
    try {
      await channelHandler.addToAllowedApps(packages);
    } catch (e) {
      rethrow;
    }
  }

  /// Stores the app-based routing state. [enabled] and [packages] are persisted
  /// independently, so the selection survives turning the rule off and on.
  ///
  /// WARNING : it's available only on Android
  Future<void> setAppBasedRule({
    required bool enabled,
    required List<String> packages,
  }) async {
    await channelHandler.setAppBasedRule(enabled: enabled, packages: packages);
  }

  /// Whether app-based routing is currently enabled.
  ///
  /// WARNING : it's available only on Android
  Future<bool> getAppBasedRuleEnabled() async {
    return channelHandler.getAppBasedRuleEnabled();
  }

  /// Enables dns for provided connection
  /// Note : the provided dns will be use for next connection, not current one
  Future enableDns({required String dns}) async {
    try {
      await channelHandler.enableDNS(customDNS: dns);
    } catch (e) {
      rethrow;
    }
  }

  /// Disables dns for provided connection
  /// Note : dns will be disabled for next connection, not current one
  ///
  /// WARNING : it's available only on Android
  Future disableDNS() async {
    try {
      await channelHandler.disableDNS();
    } catch (e) {
      rethrow;
    }
  }

  /// Enables proxy for provided connection
  /// Note : the provided proxy will be use for next connection, not current one
  ///
  /// WARNING : it's available only on Android
  Future enableProxy({required SSTPProxy proxy}) async {
    try {
      await channelHandler.enableProxy(proxy: proxy);
    } catch (e) {
      rethrow;
    }
  }

  /// Disables proxy for provided connection
  /// Note : proxy will be disabled for next connection, not current one
  ///
  /// WARNING : it's available only on Android
  Future disableProxy() async {
    try {
      await channelHandler.disableProxy();
    } catch (e) {
      rethrow;
    }
  }

  /// Saves provided [SSTPServer] configuration.
  Future saveServerData({required SSTPServer server}) async {
    try {
      await channelHandler.saveServerData(server: server);
    } catch (e) {
      rethrow;
    }
  }

  /// Returns last connection status
  Future<String> checkLastConnectionStatus() async {
    String status = await channelHandler.checkLastConnectionStatus();
    if (status.toString() == SSTPConnectionStatusKeys.CONNECTED) {
      // Preserve the elapsed time across app restarts/resumes by using the
      // native connection start timestamp as the timer base.
      int baseSeconds = 0;
      try {
        final startMillis = await channelHandler.getConnectionStartTime();
        if (startMillis > 0) {
          final elapsed =
              (DateTime.now().millisecondsSinceEpoch - startMillis) ~/ 1000;
          if (elapsed > 0) baseSeconds = elapsed;
        }
      } catch (_) {}

      try {
        _assignedIp = await channelHandler.getAssignedIp();
      } catch (_) {}

      _startConnectedTimer(baseSeconds: baseSeconds);
    } else {
      _stopConnectedTimer();
    }
    return status;
  }

  /// Returns the IPv4 address assigned by the server for the current session.
  Future<String> getAssignedIp() async {
    try {
      _assignedIp = await channelHandler.getAssignedIp();
      return _assignedIp;
    } catch (_) {
      return _assignedIp;
    }
  }

  /// Returns the cumulative traffic counters as `[download, upload]`.
  ///
  /// WARNING : it's available only on Android
  Future<List<int>> getTrafficTotals() async {
    try {
      return await channelHandler.getTrafficTotals();
    } catch (_) {
      return const [0, 0];
    }
  }

  /// Opens files and then returns selected directory path
  ///
  /// WARNING : it's available only on Android
  Future<String> addCertificate() async {
    String caller = await channelHandler.addCertificate();
    return caller;
  }

  /// Enables or disables writing the connection log to a file.
  ///
  /// WARNING : it's available only on Android
  Future<void> setConnectionLogging({required bool enabled}) async {
    try {
      await channelHandler.setConnectionLogging(enabled: enabled);
    } catch (e) {
      rethrow;
    }
  }

  /// Enables or disables the energy-saving mode. While it is on, the periodic
  /// traffic / notification cycle runs less often, so the app wakes the CPU less
  /// while a tunnel is up. The data path is unaffected.
  ///
  /// WARNING : it's available only on Android
  Future<void> setPowerSaving({required bool enabled}) async {
    try {
      await channelHandler.setPowerSaving(enabled: enabled);
    } catch (e) {
      rethrow;
    }
  }

  /// Writes a lifecycle marker (protocol switch, teardown, …) into the shared
  /// connection log. No-op when logging is disabled or on unsupported platforms.
  ///
  /// WARNING : it's available only on Android
  Future<void> logMessage({String tag = 'APP', required String message}) async {
    try {
      await channelHandler.logMessage(tag: tag, message: message);
    } catch (_) {
      // Logging must never break the connection flow.
    }
  }

  /// Returns the connection log collected so far.
  ///
  /// WARNING : it's available only on Android
  Future<String> getConnectionLog() async {
    try {
      return await channelHandler.getConnectionLog();
    } catch (e) {
      return "";
    }
  }

  /// Clears the connection log.
  ///
  /// WARNING : it's available only on Android
  Future<void> clearConnectionLog() async {
    try {
      await channelHandler.clearConnectionLog();
    } catch (e) {
      rethrow;
    }
  }

  /// Returns the absolute path of the on-disk connection log file.
  ///
  /// WARNING : it's available only on Android
  Future<String> getConnectionLogPath() async {
    try {
      return await channelHandler.getConnectionLogPath();
    } catch (e) {
      return "";
    }
  }

  /// True when the system has exempted the app from battery optimization.
  ///
  /// WARNING : it's available only on Android
  Future<bool> isIgnoringBatteryOptimizations() async {
    try {
      return await channelHandler.isIgnoringBatteryOptimizations();
    } catch (_) {
      return false;
    }
  }

  /// Asks the system to exempt the app from battery optimization, so Android
  /// does not stop the tunnel while the app is in the background.
  ///
  /// WARNING : it's available only on Android
  Future<bool> requestIgnoreBatteryOptimizations() async {
    try {
      return await channelHandler.requestIgnoreBatteryOptimizations();
    } catch (_) {
      return false;
    }
  }

  /// Opens the system list of battery-optimization exemptions, where the user
  /// can remove the exemption for SoftSSTP by hand.
  ///
  /// WARNING : it's available only on Android
  Future<bool> openBatteryOptimizationList() async {
    try {
      return await channelHandler.openBatteryOptimizationList();
    } catch (_) {
      return false;
    }
  }

  static final SstpFlutter _instance = SstpFlutter.internal();
  factory SstpFlutter() => _instance;
  SstpFlutter.internal();
}
