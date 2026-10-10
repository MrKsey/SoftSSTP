import 'package:flutter/material.dart';

/// Ten preset colours a profile icon can use.
const List<Color> profileColors = [
  Color(0xFF7C7CFF), // indigo (app primary)
  Color(0xFF4CAF50), // green
  Color(0xFF2196F3), // blue
  Color(0xFF00BCD4), // cyan
  Color(0xFF9C27B0), // purple
  Color(0xFFE91E63), // pink
  Color(0xFFF44336), // red
  Color(0xFFFF9800), // orange
  Color(0xFFFFC107), // amber
  Color(0xFF8BC34A), // light green
];

/// A named set of VPN settings.
///
/// Everything that used to live in the single Settings screen (except the
/// Diagnostics and About blocks) belongs to a profile, so a user can keep
/// several servers side by side and switch between them from the home screen.
class VpnProfile {
  final String id;
  String name;
  int colorIndex;

  /// True when the profile was created from the public VPN Gate list. Used to
  /// mark such profiles in the profile lists.
  bool isPublic;

  // Server
  String host;
  int port;
  String username;
  String password;

  // DNS
  String dns;
  bool dnsEnabled;
  bool requestDns;

  // Proxy
  String proxyHost;
  int proxyPort;
  bool proxyEnabled;

  // PPP
  int pppMtu;
  int pppMru;
  String authProtocol;
  int authTimeout;
  bool ipv4Enabled;
  bool ipv6Enabled;
  bool requestStaticIp;
  String staticIp;

  // Routing
  bool addDefaultRoute;
  bool routePrivateAddresses;

  // App routing
  bool appBasedEnabled;
  List<String> allowedApps;

  // Reconnection
  bool reconnectionEnabled;
  int reconnectionCount;
  int reconnectionInterval;

  // Advanced
  bool verifyHostName;
  bool verifySSLCert;
  String sslVersion;
  bool useCustomSni;
  String customSni;

  VpnProfile({
    required this.id,
    this.name = '',
    this.colorIndex = 0,
    this.isPublic = false,
    this.host = '',
    this.port = 443,
    this.username = '',
    this.password = '',
    this.dns = '8.8.8.8',
    this.dnsEnabled = false,
    this.requestDns = true,
    this.proxyHost = '',
    this.proxyPort = 8080,
    this.proxyEnabled = false,
    this.pppMtu = 1500,
    this.pppMru = 1500,
    this.authProtocol = 'AUTO',
    this.authTimeout = 5,
    this.ipv4Enabled = true,
    this.ipv6Enabled = false,
    this.requestStaticIp = false,
    this.staticIp = '',
    this.addDefaultRoute = true,
    this.routePrivateAddresses = false,
    this.appBasedEnabled = false,
    List<String>? allowedApps,
    this.reconnectionEnabled = false,
    this.reconnectionCount = 3,
    this.reconnectionInterval = 10,
    this.verifyHostName = false,
    this.verifySSLCert = false,
    this.sslVersion = 'AUTO',
    this.useCustomSni = false,
    this.customSni = '',
  }) : allowedApps = List<String>.from(allowedApps ?? const []);

  /// Name shown in the UI. Falls back to the server host (or its IP) when the
  /// user did not give the profile a name.
  String get displayName {
    final trimmed = name.trim();
    if (trimmed.isNotEmpty) return trimmed;
    final hostName = host.trim();
    if (hostName.isNotEmpty) return hostName;
    return 'New profile';
  }

  bool get isConfigured =>
      host.isNotEmpty && username.isNotEmpty && password.isNotEmpty;

  Color get color =>
      profileColors[colorIndex.clamp(0, profileColors.length - 1)];

  VpnProfile copy() => VpnProfile(
        id: id,
        name: name,
        colorIndex: colorIndex,
        isPublic: isPublic,
        host: host,
        port: port,
        username: username,
        password: password,
        dns: dns,
        dnsEnabled: dnsEnabled,
        requestDns: requestDns,
        proxyHost: proxyHost,
        proxyPort: proxyPort,
        proxyEnabled: proxyEnabled,
        pppMtu: pppMtu,
        pppMru: pppMru,
        authProtocol: authProtocol,
        authTimeout: authTimeout,
        ipv4Enabled: ipv4Enabled,
        ipv6Enabled: ipv6Enabled,
        requestStaticIp: requestStaticIp,
        staticIp: staticIp,
        addDefaultRoute: addDefaultRoute,
        routePrivateAddresses: routePrivateAddresses,
        appBasedEnabled: appBasedEnabled,
        allowedApps: allowedApps,
        reconnectionEnabled: reconnectionEnabled,
        reconnectionCount: reconnectionCount,
        reconnectionInterval: reconnectionInterval,
        verifyHostName: verifyHostName,
        verifySSLCert: verifySSLCert,
        sslVersion: sslVersion,
        useCustomSni: useCustomSni,
        customSni: customSni,
      );

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'colorIndex': colorIndex,
        'isPublic': isPublic,
        'host': host,
        'port': port,
        'username': username,
        'password': password,
        'dns': dns,
        'dnsEnabled': dnsEnabled,
        'requestDns': requestDns,
        'proxyHost': proxyHost,
        'proxyPort': proxyPort,
        'proxyEnabled': proxyEnabled,
        'pppMtu': pppMtu,
        'pppMru': pppMru,
        'authProtocol': authProtocol,
        'authTimeout': authTimeout,
        'ipv4Enabled': ipv4Enabled,
        'ipv6Enabled': ipv6Enabled,
        'requestStaticIp': requestStaticIp,
        'staticIp': staticIp,
        'addDefaultRoute': addDefaultRoute,
        'routePrivateAddresses': routePrivateAddresses,
        'appBasedEnabled': appBasedEnabled,
        'allowedApps': allowedApps,
        'reconnectionEnabled': reconnectionEnabled,
        'reconnectionCount': reconnectionCount,
        'reconnectionInterval': reconnectionInterval,
        'verifyHostName': verifyHostName,
        'verifySSLCert': verifySSLCert,
        'sslVersion': sslVersion,
        'useCustomSni': useCustomSni,
        'customSni': customSni,
      };

  factory VpnProfile.fromJson(Map<String, dynamic> json) {
    return VpnProfile(
      id: (json['id'] ?? '') as String,
      name: (json['name'] ?? '') as String,
      colorIndex: (json['colorIndex'] as num?)?.toInt() ?? 0,
      isPublic: (json['isPublic'] as bool?) ?? false,
      host: (json['host'] ?? '') as String,
      port: (json['port'] as num?)?.toInt() ?? 443,
      username: (json['username'] ?? '') as String,
      password: (json['password'] ?? '') as String,
      dns: (json['dns'] ?? '8.8.8.8') as String,
      dnsEnabled: (json['dnsEnabled'] as bool?) ?? false,
      requestDns: (json['requestDns'] as bool?) ?? true,
      proxyHost: (json['proxyHost'] ?? '') as String,
      proxyPort: (json['proxyPort'] as num?)?.toInt() ?? 8080,
      proxyEnabled: (json['proxyEnabled'] as bool?) ?? false,
      pppMtu: (json['pppMtu'] as num?)?.toInt() ?? 1500,
      pppMru: (json['pppMru'] as num?)?.toInt() ?? 1500,
      authProtocol: (json['authProtocol'] ?? 'AUTO') as String,
      authTimeout: (json['authTimeout'] as num?)?.toInt() ?? 5,
      ipv4Enabled: (json['ipv4Enabled'] as bool?) ?? true,
      ipv6Enabled: (json['ipv6Enabled'] as bool?) ?? false,
      requestStaticIp: (json['requestStaticIp'] as bool?) ?? false,
      staticIp: (json['staticIp'] ?? '') as String,
      addDefaultRoute: (json['addDefaultRoute'] as bool?) ?? true,
      routePrivateAddresses: (json['routePrivateAddresses'] as bool?) ?? false,
      appBasedEnabled: (json['appBasedEnabled'] as bool?) ?? false,
      allowedApps: ((json['allowedApps'] as List?) ?? const [])
          .map((e) => e.toString())
          .toList(),
      reconnectionEnabled: (json['reconnectionEnabled'] as bool?) ?? false,
      reconnectionCount: (json['reconnectionCount'] as num?)?.toInt() ?? 3,
      reconnectionInterval:
          (json['reconnectionInterval'] as num?)?.toInt() ?? 10,
      verifyHostName: (json['verifyHostName'] as bool?) ?? false,
      verifySSLCert: (json['verifySSLCert'] as bool?) ?? false,
      sslVersion: (json['sslVersion'] ?? 'AUTO') as String,
      useCustomSni: (json['useCustomSni'] as bool?) ?? false,
      customSni: (json['customSni'] ?? '') as String,
    );
  }
}
