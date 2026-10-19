import 'package:flutter/services.dart';

/// Connection status strings shared with the native side.
class SoftetherConnectionStatus {
  static const String connected = 'Connected';
  static const String connecting = 'Connecting';
  static const String disconnecting = 'Disconnecting';
  static const String disconnected = 'Disconnected';
}

/// Configuration for a SoftEther (SSL-VPN) connection.
class SoftetherServer {
  /// Server host name or IP.
  final String host;

  /// TCP port of the SoftEther SSL-VPN endpoint (typically 443).
  final int port;

  final String username;
  final String password;

  /// Virtual hub to join. VPN Gate public servers use "VPNGATE".
  final String hub;

  /// Optional client MAC address ("AA:BB:CC:DD:EE:FF"). When empty the native
  /// side derives a stable address from the server identity.
  final String clientMac;

  /// Session name shown in the notification.
  final String sessionName;

  /// DNS server pushed to the tunnel.
  final String dns;

  final int mtu;

  /// Route all traffic through the tunnel.
  final bool addDefaultRoute;

  /// Also route the private IPv4 ranges (10/8, 172.16/12, 192.168/16) through
  /// the tunnel, mirroring the SSTP profile option.
  final bool routePrivateAddresses;

  /// Rebuild the tunnel automatically after a drop. When false a dropped
  /// tunnel is reported as an error instead of being reconnected.
  final bool reconnectionEnabled;

  /// Maximum number of rebuild attempts after a drop.
  final int reconnectionCount;

  /// Delay between rebuild attempts, in seconds.
  final int reconnectionInterval;

  /// Package names excluded from the tunnel.
  final List<String> excludedApps;

  /// Package names allowed to use the tunnel (split tunnelling allow-list).
  /// When non-empty it takes precedence over [excludedApps].
  final List<String> allowedApps;

  final int connectTimeoutMs;

  /// Number of TCP connections (1-8). Mirrors the Windows client's
  /// "Number of TCP Connections".
  final int maxConnections;

  /// Delay between additional-connection attempts, in milliseconds. Mirrors the
  /// Windows client's "Additional Connection Establishing Interval".
  final int establishIntervalMs;

  /// Request SSL encryption of the session data ("Encrypt VPN Session with SSL").
  final bool useEncrypt;

  /// Request data compression ("Use Data Compression").
  final bool useCompress;

  /// Disable UDP acceleration ("Disable UDP Acceleration"). When false the
  /// native side may try the direct R-UDP stage if a [udpPort] is known.
  final bool disableUdpAcceleration;

  /// Server UDP port (seUdpPort) for UDP acceleration, 0 when unknown.
  final int udpPort;

  /// Keep VoIP / QoS functions ("Disable VoIP / QoS Functions" unchecked).
  final bool qos;

  /// Energy saving. When true the native receive loop waits longer between idle
  /// wakeups and the per-second housekeeping cycles are stretched, so an idle
  /// tunnel drains the battery less. Data still arrives immediately.
  final bool powerSaving;

  const SoftetherServer({
    required this.host,
    this.port = 443,
    required this.username,
    required this.password,
    this.hub = 'VPNGATE',
    this.clientMac = '',
    this.sessionName = 'SoftEther VPN',
    this.dns = '8.8.8.8',
    this.mtu = 1500,
    this.addDefaultRoute = true,
    this.routePrivateAddresses = false,
    this.reconnectionEnabled = false,
    this.reconnectionCount = 3,
    this.reconnectionInterval = 10,
    this.excludedApps = const [],
    this.allowedApps = const [],
    this.connectTimeoutMs = 30000,
    this.maxConnections = 4,
    this.establishIntervalMs = 1000,
    this.useEncrypt = true,
    this.useCompress = false,
    this.disableUdpAcceleration = true,
    this.udpPort = 0,
    this.qos = true,
    this.powerSaving = false,
  });

  Map<String, dynamic> toMap() => {
        'host': host,
        'port': port,
        'username': username,
        'password': password,
        'hub': hub,
        'clientMac': clientMac,
        'sessionName': sessionName,
        'dns': dns,
        'mtu': mtu,
        'addDefaultRoute': addDefaultRoute,
        'routePrivateAddresses': routePrivateAddresses,
        'reconnectionEnabled': reconnectionEnabled,
        'reconnectionCount': reconnectionCount,
        'reconnectionInterval': reconnectionInterval,
        'excludedApps': excludedApps,
        'allowedApps': allowedApps,
        'connectTimeoutMs': connectTimeoutMs,
        'maxConnections': maxConnections,
        'establishIntervalMs': establishIntervalMs,
        'useEncrypt': useEncrypt,
        'useCompress': useCompress,
        'disableUdpAcceleration': disableUdpAcceleration,
        'udpPort': udpPort,
        'qos': qos,
        'powerSaving': powerSaving,
      };
}

/// Callbacks invoked while a SoftEther tunnel changes state.
typedef SoftetherOnConnected = void Function(
  int downloadBitsPerSecond,
  int uploadBitsPerSecond,
  int totalDownloadBits,
  int totalUploadBits,
);
typedef SoftetherOnConnecting = void Function();
typedef SoftetherOnDisconnected = void Function();
typedef SoftetherOnReconnecting = void Function(String message);
typedef SoftetherOnConnectionError = void Function(String code, String message);
typedef SoftetherOnAssignedIp = void Function(String ip);

/// Flutter bridge to the bundled SoftEther client.
class SoftetherFlutter {
  MethodChannel _command = const MethodChannel('softether_flutter');
  MethodChannel _events = const MethodChannel('softether_receiver');

  // Last figures reported by the native side. Native is the single source of
  // the traffic figures: every "trafficUpdate" event is a fresh measurement, so
  // the UI never shows an interpolated zero between two ticks.
  int _downSpeed = 0;
  int _upSpeed = 0;
  int _totalDown = 0;
  int _totalUp = 0;
  String _assignedIp = '';
  bool _connected = false;

  SoftetherOnConnected? onConnected;
  SoftetherOnConnecting? onConnecting;
  SoftetherOnDisconnected? onDisconnected;
  SoftetherOnReconnecting? onReconnecting;
  SoftetherOnConnectionError? onConnectionError;
  SoftetherOnAssignedIp? onAssignedIp;

  /// The IPv4 address assigned by the server for the current connection.
  String get assignedIp => _assignedIp;

  /// Registers the callbacks and starts listening for native events.
  void onResult({
    SoftetherOnConnected? onConnected,
    SoftetherOnConnecting? onConnecting,
    SoftetherOnDisconnected? onDisconnected,
    SoftetherOnReconnecting? onReconnecting,
    SoftetherOnConnectionError? onConnectionError,
    SoftetherOnAssignedIp? onAssignedIp,
  }) {
    this.onConnected = onConnected;
    this.onConnecting = onConnecting;
    this.onDisconnected = onDisconnected;
    this.onReconnecting = onReconnecting;
    this.onConnectionError = onConnectionError;
    this.onAssignedIp = onAssignedIp;

    _events.setMethodCallHandler((call) async {
      final arg = call.arguments;
      switch (call.method) {
        case 'connectResponse':
          _handleConnectResponse(arg is Map ? arg : const {});
          break;
        case 'trafficUpdate':
          if (arg is List && arg.length >= 4) {
            _downSpeed = (arg[0] as num).toInt();
            _upSpeed = (arg[1] as num).toInt();
            _totalDown = (arg[2] as num).toInt();
            _totalUp = (arg[3] as num).toInt();
            // Traffic figures only exist for a live session. The teardown
            // publishes a final empty snapshot AFTER the "disconnected" event;
            // forwarding it as an onConnected update flipped the home screen
            // back to "connected", so the disconnect switch looked like it
            // did nothing. The figures are still stored above, so the
            // "connected" event (which may race the first snapshot) reports
            // the current values.
            if (_connected) {
              onConnected?.call(_downSpeed, _upSpeed, _totalDown, _totalUp);
            }
          }
          break;
      }
    });
  }

  void _handleConnectResponse(Map<dynamic, dynamic> arg) {
    final status = arg['status'] as String?;
    switch (status) {
      case SoftetherConnectionStatus.connected:
        final ip = (arg['ip'] as String?) ?? '';
        if (ip.isNotEmpty) {
          _assignedIp = ip;
          onAssignedIp?.call(ip);
        }
        _connected = true;
        // Report the transition right away so the UI leaves "Connecting"
        // without waiting for the first native measurement.
        onConnected?.call(_downSpeed, _upSpeed, _totalDown, _totalUp);
        break;
      case SoftetherConnectionStatus.connecting:
        _connected = false;
        _resetSpeeds();
        onConnecting?.call();
        break;
      case SoftetherConnectionStatus.disconnected:
        _connected = false;
        _resetSpeeds();
        _assignedIp = '';
        onDisconnected?.call();
        if (arg['error'] == true) {
          onConnectionError?.call(
            (arg['errorCode'] as String?) ?? '',
            (arg['errorMessage'] as String?) ?? '',
          );
        }
        break;
      case SoftetherConnectionStatus.disconnecting:
        // Transient; the follow-up "Disconnected" event drives the UI.
        break;
    }
  }

  /// Drops the last reported speeds. Used when the session ends or restarts,
  /// so nothing from the previous session leaks into the new one.
  void _resetSpeeds() {
    _downSpeed = 0;
    _upSpeed = 0;
  }

  /// Claims the VPN slot, showing the system consent dialog when required.
  Future<bool> takePermission() async {
    final granted = await _command.invokeMethod<bool>('takePermission');
    return granted ?? false;
  }

  /// Starts the tunnel with [server].
  Future<void> connect({required SoftetherServer server}) async {
    await _command.invokeMethod('connect', server.toMap());
  }

  /// Tears the current tunnel down.
  Future<void> disconnect() async {
    await _command.invokeMethod('disconnect');
  }

  /// Returns the last known status ("Connected", "Connecting", "Disconnected").
  Future<String> checkLastConnectionStatus() async {
    final status =
        await _command.invokeMethod<String>('checkLastConnectionStatus');
    final value = status ?? SoftetherConnectionStatus.disconnected;
    _connected = value == SoftetherConnectionStatus.connected;
    if (!_connected) {
      _resetSpeeds();
    }
    return value;
  }

  /// Returns the IPv4 address assigned for the current session.
  Future<String> getAssignedIp() async {
    final ip = await _command.invokeMethod<String>('getAssignedIp');
    _assignedIp = ip ?? '';
    return _assignedIp;
  }

  /// Tunnel uptime in milliseconds as measured by the native service, or 0 when
  /// no tunnel is up. The service outlives the Flutter Activity, so this is what
  /// lets the UI rebuild its uptime timer after the app is backgrounded and
  /// brought back (an anchor kept in Dart memory would restart from zero).
  Future<Duration> getConnectionUptime() async {
    final ms = await _command.invokeMethod<num>('getConnectionUptime');
    return Duration(milliseconds: (ms ?? 0).toInt());
  }

  /// Returns the cumulative traffic counters as `[downloadBits, uploadBits]`.
  Future<List<int>> getTrafficTotals() async {
    final result =
        await _command.invokeMethod<List<dynamic>>('getTrafficTotals');
    if (result == null || result.length < 2) return const [0, 0];
    return [(result[0] as num).toInt(), (result[1] as num).toInt()];
  }

  /// Enables or disables collection of the native connection log.
  Future<void> setConnectionLogging({required bool enabled}) async {
    await _command
        .invokeMethod<bool>('setConnectionLogging', {'enabled': enabled});
  }

  /// Enables or disables the energy-saving mode. While it is on, the periodic
  /// traffic/statistics cycle runs less often, so an idle tunnel wakes the CPU
  /// less. The data path is unaffected. Applied live, without a reconnect.
  Future<void> setPowerSaving({required bool enabled}) async {
    await _command.invokeMethod<bool>('setPowerSaving', {'enabled': enabled});
  }

  /// True while the SoftEther VPN service instance exists. Its reported state
  /// turns to "disconnected" as soon as a teardown starts, while the teardown
  /// and the service stop still run in the background, so this is what tells a
  /// caller the previous instance is really gone and a connect will land on a
  /// fresh one.
  Future<bool> isServiceRunning() async {
    final running = await _command.invokeMethod<bool>('isServiceRunning');
    return running ?? false;
  }

  /// Enables or disables the periodic (every 5 s) per-link traffic split in the
  /// native log. Off by default; the split at the end of a session is always
  /// written either way.
  Future<void> setPerLinkStats({required bool enabled}) async {
    await _command.invokeMethod<bool>('setPerLinkStats', {'enabled': enabled});
  }

  /// Returns the collected native connection log (empty when disabled).
  Future<String> getConnectionLog() async {
    final log = await _command.invokeMethod<String>('getConnectionLog');
    return log ?? '';
  }

  /// Absolute path of the on-disk connection log file.
  Future<String> getConnectionLogPath() async {
    final path = await _command.invokeMethod<String>('getConnectionLogPath');
    return path ?? '';
  }

  /// Clears the in-memory buffer and the on-disk log file.
  Future<void> clearConnectionLog() async {
    await _command.invokeMethod<bool>('clearConnectionLog');
  }

  static final SoftetherFlutter _instance = SoftetherFlutter._internal();
  factory SoftetherFlutter() => _instance;
  SoftetherFlutter._internal();
}
