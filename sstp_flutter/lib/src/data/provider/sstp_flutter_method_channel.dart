import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:sstp_flutter/app_info.dart';
import 'package:sstp_flutter/proxy.dart';
import 'package:sstp_flutter/server.dart';

class MethodChannelSstpFlutter {
  final methodChannelCaller = const MethodChannel('sstp_flutter');

  Future<String?> getPlatformVersion() async {
    final version =
        await methodChannelCaller.invokeMethod<String>('getPlatformVersion');
    return version;
  }

  Future connectVpn() async {
    try {
      await methodChannelCaller.invokeMethod("connect");
    } on PlatformException catch (e) {
      debugPrint(e.toString());
      rethrow;
    }
  }

  Future disconnect() async {
    try {
      await methodChannelCaller.invokeMethod("disconnect");
    } on PlatformException catch (e) {
      debugPrint(e.toString());
      rethrow;
    }
  }

  Future saveCnnectionStatus(bool value) async {
    SharedPreferences prefs = await SharedPreferences.getInstance();
    prefs.setBool("connected", value);
  }

  /// Asks the system to prepare the VPN. Returns true when the app is
  /// (or has just become) the prepared VPN owner, false when the user must
  /// still grant consent.
  Future<bool> vpnPermission() async {
    try {
      final granted = await methodChannelCaller.invokeMethod("takePermission");
      return granted == true;
    } on PlatformException catch (e) {
      debugPrint(e.toString());
      rethrow;
    }
  }

  Future<List<InstalledAppInfo>> getInstalledApps() async {
    List<InstalledAppInfo> appInfoList = [];
    List<dynamic> apps = await methodChannelCaller.invokeMethod("getApps");
    appInfoList = apps.map((app) => InstalledAppInfo.create(app)).toList();

    appInfoList.sort((a, b) => a.name!.compareTo(b.name!));
    return appInfoList;
  }

  Future<List<String>> getAllowedApps() async {
    List<Object?> receiver = await methodChannelCaller
        .invokeMethod("getAllowedApps"); //returns list of package names
    List<String> caller = [];

    for (var element in receiver) {
      caller.add(element.toString());
    }
    return caller;
  }

  Future addToAllowedApps(List<String> pkgName) async {
    var caller = await methodChannelCaller
        .invokeMethod("addAllowedApps", {"packageName": pkgName});
    return caller;
  }

  /// Stores the app-based routing state. [enabled] and [packages] are persisted
  /// independently, so the selection is kept when the rule is turned off.
  Future<bool> setAppBasedRule({
    required bool enabled,
    required List<String> packages,
  }) async {
    final result = await methodChannelCaller.invokeMethod("setAppBasedRule", {
      "enabled": enabled,
      "packageName": packages,
    });
    return result == true;
  }

  Future<bool> getAppBasedRuleEnabled() async {
    final result =
        await methodChannelCaller.invokeMethod("getAppBasedRuleEnabled");
    return result == true;
  }

  Future enableDNS({required String customDNS}) async {
    await methodChannelCaller
        .invokeMethod("enableDns", {"customDns": customDNS});
  }

  Future disableDNS() async {
    await methodChannelCaller.invokeMethod("disableDns");
  }

  Future enableProxy({required SSTPProxy proxy}) async {
    await methodChannelCaller.invokeMethod("enableProxy", {
      "proxyIp": proxy.ip,
      "proxyPort": proxy.port,
      "proxyUserName": proxy.userName ?? "",
      "proxyPassword": proxy.password ?? "",
    });
  }

  Future disableProxy() async {
    await methodChannelCaller.invokeMethod("disableProxy");
  }

  Future saveServerData({required SSTPServer server}) async {
    try {
      var res = await methodChannelCaller.invokeMethod("saveServer", {
        "hostName": server.host,
        "sslPort": server.port,
        "userName": server.username,
        "password": server.password,
        "verifyHostName": server.androidConfiguration.verifyHostName,
        "verifySSLCert": server.androidConfiguration.verifySSLCert,
        "useTrustedCert": server.androidConfiguration.useTrustedCert,
        "sslVersion": server.androidConfiguration.sslVersion,
        "useCustomSni": server.androidConfiguration.useCustomSni,
        "customSni": server.androidConfiguration.customSni,
        "pppMru": server.androidConfiguration.pppMru,
        "pppMtu": server.androidConfiguration.pppMtu,
        "pppAuthProtocol": server.androidConfiguration.pppAuthProtocol,
        "pppAuthTimeout": server.androidConfiguration.pppAuthTimeout,
        "pppIpv4Enabled": server.androidConfiguration.pppIpv4Enabled,
        "pppIpv6Enabled": server.androidConfiguration.pppIpv6Enabled,
        "requestStaticIp": server.androidConfiguration.requestStaticIpv4,
        "staticIp": server.androidConfiguration.staticIpv4Address,
        "addDefaultRoute": server.androidConfiguration.addDefaultRoute,
        "routePrivateAddresses":
            server.androidConfiguration.routePrivateAddresses,
        "requestDns": server.androidConfiguration.requestDns,
        "reconnectionEnabled": server.androidConfiguration.reconnectionEnabled,
        "reconnectionCount": server.androidConfiguration.reconnectionCount,
        "reconnectionInterval":
            server.androidConfiguration.reconnectionInterval,

        "showDisconnectOnNotification":
            server.androidConfiguration.showDisconnectOnNotification,
        "notificationText": server.androidConfiguration.notificationText,
        "enableCHAP": server.iosConfiguration.enableCHAP,
        "enablePAP": server.iosConfiguration.enablePAP,
        "enableTLS": server.iosConfiguration.enableTLS,
        "enableMSCHAP2": server.iosConfiguration.enableMSCHAP2,
      });
      debugPrint(res.toString());
    } catch (e) {
      debugPrint(e.toString());
    }
  }

  Future<String> checkLastConnectionStatus() async {
    String status =
        await methodChannelCaller.invokeMethod("checkLastConnectionStatus");
    return status;
  }

  /// Returns the epoch-millis timestamp when the current connection was
  /// established, or 0 when not connected.
  Future<int> getConnectionStartTime() async {
    final start =
        await methodChannelCaller.invokeMethod<int>("getConnectionStartTime");
    return start ?? 0;
  }

  /// Returns the IPv4 address assigned by the server for the current session.
  Future<String> getAssignedIp() async {
    final ip = await methodChannelCaller.invokeMethod<String>("getAssignedIp");
    return ip ?? "";
  }

  /// Returns the cumulative traffic counters as `[download, upload]`, in the
  /// same unit used by the speed callbacks. Read on resume so the total is not
  /// lost if the periodic pushes were dropped while the app was backgrounded.
  Future<List<int>> getTrafficTotals() async {
    final result = await methodChannelCaller.invokeMethod("getTrafficTotals");
    if (result is List && result.length >= 2) {
      return [
        (result[0] as num?)?.toInt() ?? 0,
        (result[1] as num?)?.toInt() ?? 0,
      ];
    }
    return const [0, 0];
  }


  Future<String> addCertificate() async {
    String caller =
        await methodChannelCaller.invokeMethod("addTrustedCertificate");
    return caller;
  }

  /// Enables or disables writing the connection log to a file.
  Future<void> setConnectionLogging({required bool enabled}) async {
    await methodChannelCaller
        .invokeMethod("setConnectionLogging", {"enabled": enabled});
  }

  /// Returns the in-memory connection log collected so far.
  Future<String> getConnectionLog() async {
    final log = await methodChannelCaller.invokeMethod<String>("getConnectionLog");
    return log ?? "";
  }

  /// Clears the in-memory and on-disk connection log.
  Future<void> clearConnectionLog() async {
    await methodChannelCaller.invokeMethod("clearConnectionLog");
  }

  /// Returns the absolute path of the on-disk connection log file.
  Future<String> getConnectionLogPath() async {
    final path =
        await methodChannelCaller.invokeMethod<String>("getConnectionLogPath");
    return path ?? "";
  }

  /// True when the system has exempted the app from battery optimization.
  Future<bool> isIgnoringBatteryOptimizations() async {
    final result =
        await methodChannelCaller.invokeMethod("isIgnoringBatteryOptimizations");
    return result == true;
  }

  /// Asks the system to exempt the app from battery optimization, so the tunnel
  /// is not stopped while the app is in the background.
  Future<bool> requestIgnoreBatteryOptimizations() async {
    final result = await methodChannelCaller
        .invokeMethod("requestIgnoreBatteryOptimizations");
    return result == true;
  }

  static final MethodChannelSstpFlutter _instance =
      MethodChannelSstpFlutter.internal();
  factory MethodChannelSstpFlutter() => _instance;
  MethodChannelSstpFlutter.internal();
}
