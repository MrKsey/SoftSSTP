import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:sstp_flutter/sstp_flutter.dart';
import 'package:sstp_flutter/app_info.dart';
import 'package:sstp_flutter/server.dart';
import 'package:sstp_flutter/android_configuration_sstp.dart';
import 'package:sstp_flutter/ios_configuration_sstp.dart';
import 'package:sstp_flutter/traffic.dart';
import 'package:sstp_flutter/ssl_versions.dart';
import 'package:sstp_flutter/proxy.dart';
import 'package:softether_flutter/softether_flutter.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/app_list_preset.dart';
import '../models/vpn_profile.dart';
import '../services/network_monitor.dart';

enum VpnStatus { disconnected, connecting, reconnecting, connected }

/// Public IP together with its geolocation, as resolved from an external
/// lookup service.
class IpGeoInfo {
  final String ip;
  final String country;
  final String city;

  const IpGeoInfo({required this.ip, this.country = '', this.city = ''});

  /// "City, Country" (or whichever part is available).
  String get location {
    final parts = [city, country].where((p) => p.trim().isNotEmpty);
    return parts.join(', ');
  }
}

class VpnProvider extends ChangeNotifier {
  final SstpFlutter _sstpFlutter = SstpFlutter();
  final SoftetherFlutter _softether = SoftetherFlutter();

  // Profiles. The scalar settings below always mirror the active profile.
  List<VpnProfile> _profiles = [];
  String _activeProfileId = '';
  String _activeName = '';
  int _activeColorIndex = 0;
  bool _activeIsPublic = false;

  // Protocol mirrored from the active profile. [VpnProtocol.sstp] routes to the
  // sstp_flutter plugin, [VpnProtocol.softether] to softether_flutter.
  String _protocol = VpnProtocol.sstp;
  // Protocol of the plugin that owns the tunnel currently in use. Kept separate
  // from [_protocol]: when the active profile's protocol is switched while a
  // tunnel is up, [_protocol] already points at the new plugin, but the running
  // tunnel still belongs to the old one. Routing teardown/status/traffic calls
  // through the owner protocol is what stops the old tunnel from being orphaned
  // (and a second one from being created next to it).
  String? _tunnelProtocol;
  String _hubName = 'DEFAULT';
  String _clientMac = '';

  VpnStatus _status = VpnStatus.disconnected;
  String _statusMessage = '';
  String _errorMessage = '';
  String _errorCode = '';
  String _host = '';
  int _port = 443;
  String _username = '';
  String _password = '';
  Duration _connectionDuration = Duration.zero;
  int _downloadSpeed = 0;
  int _uploadSpeed = 0;
  int _totalDownload = 0;
  int _totalUpload = 0;
  String _dns = '';
  bool _dnsEnabled = false;
  String _proxyHost = '';
  int _proxyPort = 0;
  bool _proxyEnabled = false;
  bool _verifyHostName = false;
  bool _verifySSLCert = false;
  String _sslVersion = 'AUTO';
  bool _useCustomSni = false;
  String _customSni = '';
  // SoftEther session options mirrored from the active profile (ignored for SSTP).
  int _seTcpConnections = 4;
  int _seEstablishIntervalMs = 1000;
  String _seDuplexMode = 'auto';
  bool _seEncrypt = true;
  bool _seCompress = false;
  bool _seDisableUdpAcceleration = true;
  int _seUdpPort = 0;
  bool _seDisableQos = false;
  bool _connectionLogEnabled = true;
  String _externalIpBefore = '';
  String _externalIpAfter = '';
  String _beforeLocation = '';
  String _afterLocation = '';
  // Guards the public-IP lookup. A generation counter invalidates results that
  // arrive after the connection state changed, so a lookup started while the
  // tunnel was down can never populate the "Via VPN" row (and vice versa).
  bool _ipLookupInFlight = false;
  bool _ipLookupPending = false;
  int _ipGeneration = 0;
  // Bounds the extra attempts made when the exit-address lookup keeps escaping
  // the tunnel right after connecting. Reset on every new connection.
  int _exitIpRetryRound = 0;
  String _internalIp = '';

  // PPP
  int _pppMtu = 1500;
  int _pppMru = 1500;
  String _authProtocol = 'AUTO';
  int _authTimeout = 5;
  bool _ipv4Enabled = true;
  bool _ipv6Enabled = false;
  bool _requestStaticIp = false;
  String _staticIp = '';

  // Routing
  bool _addDefaultRoute = true;
  bool _routePrivateAddresses = false;
  bool _requestDns = true;

  // Reconnection
  bool _reconnectionEnabled = false;
  int _reconnectionCount = 3;
  int _reconnectionInterval = 10;

  // App-based routing
  bool _appBasedEnabled = false;
  List<String> _allowedApps = [];

  // Named, reusable app selections (shared between all profiles).
  List<AppListPreset> _appPresets = [];

  VpnStatus get status => _status;

  /// Human readable explanation of the last failed connection attempt, or an
  /// empty string when there was no actionable error.
  String get errorMessage => _errorMessage;

  /// Stable identifier of the last failure, e.g. "VPN_ESTABLISH_FAILED".
  String get errorCode => _errorCode;

  /// Human readable status label matching the foreground notification, e.g.
  /// "Connecting...", "Connected", "Reconnecting... (attempt 2)".
  String get statusMessage {
    switch (_status) {
      case VpnStatus.connected:
        return 'Connected';
      case VpnStatus.connecting:
        return 'Connecting...';
      case VpnStatus.reconnecting:
        return _statusMessage.isEmpty ? 'Reconnecting...' : _statusMessage;
      case VpnStatus.disconnected:
        return 'Disconnected';
    }
  }

  String get host => _host;
  int get port => _port;
  String get username => _username;
  String get password => _password;

  /// Active protocol: [VpnProtocol.sstp] or [VpnProtocol.softether].
  String get protocol => _protocol;

  /// SoftEther virtual hub (ignored for SSTP).
  String get hubName => _hubName;

  /// Optional SoftEther client MAC (ignored for SSTP).
  String get clientMac => _clientMac;

  /// True when the active profile uses SoftEther.
  bool get isSoftether => _protocol == VpnProtocol.softether;

  Duration get connectionDuration => _connectionDuration;
  int get downloadSpeed => _downloadSpeed;
  int get uploadSpeed => _uploadSpeed;
  int get totalDownload => _totalDownload;
  int get totalUpload => _totalUpload;
  String get dns => _dns;
  bool get dnsEnabled => _dnsEnabled;
  String get proxyHost => _proxyHost;
  int get proxyPort => _proxyPort;
  bool get proxyEnabled => _proxyEnabled;
  bool get verifyHostName => _verifyHostName;
  bool get verifySSLCert => _verifySSLCert;
  String get sslVersion => _sslVersion;

  /// TLS versions tried in order when the profile uses [Auto], from the most
  /// secure to the oldest.
  static const List<String> _autoTlsOrder = [
    'TLSv1_3',
    'TLSv1_2',
    'TLSv1_1',
    'TLSv1',
  ];

  /// Authentication protocols tried in order when the profile uses [Auto].
  static const List<String> _autoAuthOrder = ['MSCHAPv2', 'PAP'];

  /// True when the TLS version is negotiated automatically.
  bool get tlsAuto => _sslVersion == 'AUTO';

  /// True when the PPP authentication protocol is negotiated automatically.
  bool get authAuto => _authProtocol == 'AUTO';

  // Current position in the Auto candidate lists. Reset on every fresh connect
  // and advanced each time the server rejects a candidate.
  int _autoTlsIndex = 0;
  int _autoAuthIndex = 0;

  /// True while an Auto profile is still trying candidates. Disables the native
  /// retry loop so the app can drive the downgrade itself.
  bool _negotiating = false;
  bool _autoRetryScheduled = false;

  /// Guards against acting twice on the same failure. The native side can report
  /// one failed attempt more than once (e.g. the exception handler and the phase
  /// check both fire), and each extra report used to advance the Auto list,
  /// which is why TLS 1.3 was followed by 1.1 instead of 1.2.
  bool _failureHandled = false;

  /// How many times a whole connection is retried when the server rejects the
  /// IP address negotiation (a transient answer from some public servers).
  static const int _maxIpcpRetries = 2;
  int _ipcpRetryCount = 0;

  /// The TLS version actually used for the current (or last) attempt.
  String get effectiveSslVersion =>
      tlsAuto ? _autoTlsOrder[_autoTlsIndex] : _sslVersion;

  /// The authentication protocol actually used for the current attempt.
  String get effectiveAuthProtocol =>
      authAuto ? _autoAuthOrder[_autoAuthIndex] : _authProtocol;

  /// Human readable TLS version of the effective selection, e.g. "TLS 1.2".
  String get sslVersionLabel => _tlsLabel(effectiveSslVersion);

  static String _tlsLabel(String version) {
    switch (version) {
      case 'TLSv1':
        return 'TLS 1.0';
      case 'TLSv1_1':
        return 'TLS 1.1';
      case 'TLSv1_2':
        return 'TLS 1.2';
      case 'TLSv1_3':
        return 'TLS 1.3';
      default:
        return 'TLS default';
    }
  }

  bool get useCustomSni => _useCustomSni;
  String get customSni => _customSni;

  // SoftEther session options (Windows-client equivalents).
  int get seTcpConnections => _seTcpConnections;
  int get seEstablishIntervalMs => _seEstablishIntervalMs;
  String get seDuplexMode => _seDuplexMode;
  bool get seEncrypt => _seEncrypt;
  bool get seCompress => _seCompress;
  bool get seDisableUdpAcceleration => _seDisableUdpAcceleration;
  int get seUdpPort => _seUdpPort;
  bool get seDisableQos => _seDisableQos;
  bool get connectionLogEnabled => _connectionLogEnabled;
  String get externalIpBefore => _externalIpBefore;
  String get externalIpAfter => _externalIpAfter;
  String get beforeLocation => _beforeLocation;
  String get afterLocation => _afterLocation;
  String get internalIp => _internalIp;
  int get pppMtu => _pppMtu;
  int get pppMru => _pppMru;
  String get authProtocol => _authProtocol;
  int get authTimeout => _authTimeout;
  bool get ipv4Enabled => _ipv4Enabled;
  bool get ipv6Enabled => _ipv6Enabled;
  bool get requestStaticIp => _requestStaticIp;
  String get staticIp => _staticIp;
  bool get addDefaultRoute => _addDefaultRoute;
  bool get routePrivateAddresses => _routePrivateAddresses;
  bool get requestDns => _requestDns;
  bool get reconnectionEnabled => _reconnectionEnabled;
  int get reconnectionCount => _reconnectionCount;
  int get reconnectionInterval => _reconnectionInterval;
  List<String> get allowedApps => List.unmodifiable(_allowedApps);
  bool get appBasedEnabled => _appBasedEnabled;

  /// Saved app selections that can be applied to any profile.
  List<AppListPreset> get appPresets => List.unmodifiable(_appPresets);
  bool get isConfigured =>
      _host.isNotEmpty && _username.isNotEmpty && _password.isNotEmpty;

  // --- Profiles ----------------------------------------------------------

  List<VpnProfile> get profiles => List.unmodifiable(_profiles);
  String get activeProfileId => _activeProfileId;
  String get activeProfileName => _activeName;
  int get activeProfileColorIndex => _activeColorIndex;
  Color get activeProfileColor =>
      profileColors[_activeColorIndex.clamp(0, profileColors.length - 1)];

  /// The currently selected profile (falls back to a snapshot of the working
  /// settings when the list is momentarily empty).
  VpnProfile get activeProfile {
    final idx = _profiles.indexWhere((p) => p.id == _activeProfileId);
    return idx >= 0 ? _profiles[idx] : _captureActiveProfile();
  }

  VpnProvider() {
    _initializeListener();
    // Refresh the direct IP when the underlying network changes (Wi-Fi <-> data).
    NetworkMonitor.listen(_onNetworkChanged);
    _loadSettings();
  }

  DateTime _lastNetworkRefresh = DateTime.fromMillisecondsSinceEpoch(0);

  /// Called by the native network callback. Refreshes the "before connection"
  /// address when no tunnel is up so a network switch is reflected right away,
  /// without the user having to restart the app.
  void _onNetworkChanged() {
    final now = DateTime.now();
    if (now.difference(_lastNetworkRefresh) < const Duration(seconds: 3)) {
      return;
    }
    _lastNetworkRefresh = now;

    if (_status == VpnStatus.disconnected) {
      refreshExternalIp();
    }
  }

  void _initializeListener() {
    _sstpFlutter.onResult(
      onConnectedResult: (ConnectionTraffic traffic, Duration duration) {
        _applyConnectedState(
          downloadSpeed: traffic.downloadTraffic ?? 0,
          uploadSpeed: traffic.uploadTraffic ?? 0,
          totalDownload: traffic.totalDownloadTraffic,
          totalUpload: traffic.totalUploadTraffic,
          duration: duration,
        );
      },
      onConnectingResult: _applyConnectingState,
      onReconnectingResult: _applyReconnectingState,
      onDisconnectedResult: _applyDisconnectedState,
      onError: _applyErrorState,
      onConnectionErrorResult: _applyConnectionErrorState,
      onAssignedIpResult: _applyAssignedIpState,
    );
    // SoftEther reports traffic in bits per second and without a duration, so
    // the tunnel uptime is tracked here.
    _softether.onResult(
      onConnected: (down, up, totalDown, totalUp) {
        _applyConnectedState(
          downloadSpeed: down,
          uploadSpeed: up,
          totalDownload: totalDown,
          totalUpload: totalUp,
          duration: _softetherDuration,
        );
      },
      onConnecting: _applyConnectingState,
      onReconnecting: _applyReconnectingState,
      onDisconnected: _applyDisconnectedState,
      onConnectionError: _applyConnectionErrorState,
      onAssignedIp: _applyAssignedIpState,
    );
  }

  /// When the current tunnel was established, used to compute the uptime for
  /// protocols that do not report one themselves.
  DateTime? _softetherConnectedAt;

  Duration get _softetherDuration {
    final at = _softetherConnectedAt;
    return at == null ? Duration.zero : DateTime.now().difference(at);
  }

  void _applyConnectedState({
    required int downloadSpeed,
    required int uploadSpeed,
    required int? totalDownload,
    required int? totalUpload,
    required Duration duration,
  }) {
    final wasConnected = _status == VpnStatus.connected;
    _status = VpnStatus.connected;
    // The tunnel is up, so any Auto negotiation is finished.
    _negotiating = false;
    _autoRetryScheduled = false;
    _statusMessage = '';
    _connectionDuration = duration;
    _downloadSpeed = downloadSpeed;
    _uploadSpeed = uploadSpeed;
    // Use the native cumulative counters instead of summing the per-second
    // deltas here. The native counters are authoritative, so nothing is
    // lost while the app is backgrounded (e.g. during a speed test).
    _totalDownload = totalDownload ?? _totalDownload;
    _totalUpload = totalUpload ?? _totalUpload;
    if (!wasConnected) _softetherConnectedAt = DateTime.now();
    notifyListeners();

    // Fetch the exit IP once per connection, after routes have settled.
    if (!wasConnected) {
      _ipGeneration++;
      _exitIpRetryRound = 0;
      Future.delayed(const Duration(seconds: 2), refreshExternalIp);
    }
  }

  void _applyConnectingState() {
    _status = VpnStatus.connecting;
    // Keep the "trying another TLS/auth option" notice visible while the
    // next candidate is being attempted.
    if (!_negotiating) _statusMessage = '';
    _errorMessage = '';
    _errorCode = '';
    // A new attempt invalidates any lookup still running for the previous
    // state and drops the stale exit address, so "Via VPN" never shows the
    // address of the tunnel we are switching away from.
    _ipGeneration++;
    _externalIpAfter = '';
    _afterLocation = '';
    _exitIpRetryRound = 0;
    notifyListeners();
  }

  void _applyReconnectingState(String message) {
    _status = VpnStatus.reconnecting;
    _statusMessage = message;
    _downloadSpeed = 0;
    _uploadSpeed = 0;
    notifyListeners();
  }

  void _applyDisconnectedState() {
    _status = VpnStatus.disconnected;
    _statusMessage = '';
    _downloadSpeed = 0;
    _uploadSpeed = 0;
    _connectionDuration = Duration.zero;
    _totalDownload = 0;
    _totalUpload = 0;
    _internalIp = '';
    _softetherConnectedAt = null;
    // The VPN exit address only exists while the tunnel is up.
    _externalIpAfter = '';
    _afterLocation = '';
    // Invalidate lookups that were started while the tunnel was up.
    _ipGeneration++;
    notifyListeners();
    _persistIpState();
    saveTrafficTotals();
    // Refresh the direct IP so the "before" value is up to date.
    refreshExternalIp();
  }

  void _applyErrorState() {
    _status = VpnStatus.disconnected;
    notifyListeners();
  }

  void _applyConnectionErrorState(String code, String message) {
    _status = VpnStatus.disconnected;
    _errorCode = code;
    _errorMessage = message;
    _internalIp = '';
    _softetherConnectedAt = null;
    _externalIpAfter = '';
    _afterLocation = '';
    _ipGeneration++;
    notifyListeners();
    _persistIpState();
    _handleConnectionFailure(code);
  }

  void _applyAssignedIpState(String ip) {
    _internalIp = ip;
    notifyListeners();
    _persistIpState();
    // The assigned address means IPCP has completed, i.e. the tunnel is
    // fully up and its routes are in place. That is the most reliable moment
    // to look up the exit address, so do not rely on a fixed delay alone.
    Future.delayed(const Duration(seconds: 1), refreshExternalIp);
  }

  // --- Profile helpers ---------------------------------------------------

  String _newProfileId() => DateTime.now().microsecondsSinceEpoch.toString();

  /// Like [_newProfileId] but guaranteed not to collide with an existing
  /// profile, so an import can never overwrite a local profile.
  String _freshProfileId() {
    var candidate = _newProfileId();
    var suffix = 0;
    while (_profiles.any((p) => p.id == candidate)) {
      suffix++;
      candidate = '${_newProfileId()}_$suffix';
    }
    return candidate;
  }

  /// Builds a profile snapshot from the current working settings.
  VpnProfile _captureActiveProfile() => VpnProfile(
    id: _activeProfileId,
    name: _activeName,
    colorIndex: _activeColorIndex,
    isPublic: _activeIsPublic,
    protocol: _protocol,
    hubName: _hubName,
    clientMac: _clientMac,
    host: _host,
    port: _port,
    username: _username,
    password: _password,
    dns: _dns,
    dnsEnabled: _dnsEnabled,
    requestDns: _requestDns,
    proxyHost: _proxyHost,
    proxyPort: _proxyPort,
    proxyEnabled: _proxyEnabled,
    pppMtu: _pppMtu,
    pppMru: _pppMru,
    authProtocol: _authProtocol,
    authTimeout: _authTimeout,
    ipv4Enabled: _ipv4Enabled,
    ipv6Enabled: _ipv6Enabled,
    requestStaticIp: _requestStaticIp,
    staticIp: _staticIp,
    addDefaultRoute: _addDefaultRoute,
    routePrivateAddresses: _routePrivateAddresses,
    appBasedEnabled: _appBasedEnabled,
    allowedApps: _allowedApps,
    reconnectionEnabled: _reconnectionEnabled,
    reconnectionCount: _reconnectionCount,
    reconnectionInterval: _reconnectionInterval,
    verifyHostName: _verifyHostName,
    verifySSLCert: _verifySSLCert,
    sslVersion: _sslVersion,
    useCustomSni: _useCustomSni,
    customSni: _customSni,
    seTcpConnections: _seTcpConnections,
    seEstablishIntervalMs: _seEstablishIntervalMs,
    seDuplexMode: _seDuplexMode,
    seEncrypt: _seEncrypt,
    seCompress: _seCompress,
    seDisableUdpAcceleration: _seDisableUdpAcceleration,
    seUdpPort: _seUdpPort,
    seDisableQos: _seDisableQos,
  );

  /// Copies [profile] into the working settings.
  void _applyProfile(VpnProfile profile) {
    _activeProfileId = profile.id;
    _activeName = profile.name;
    _activeColorIndex = profile.colorIndex;
    _activeIsPublic = profile.isPublic;
    _protocol = profile.protocol;
    _hubName = profile.hubName;
    _clientMac = profile.clientMac;
    _host = profile.host;
    _port = profile.port;
    _username = profile.username;
    _password = profile.password;
    _dns = profile.dns;
    _dnsEnabled = profile.dnsEnabled;
    _requestDns = profile.requestDns;
    _proxyHost = profile.proxyHost;
    _proxyPort = profile.proxyPort;
    _proxyEnabled = profile.proxyEnabled;
    _pppMtu = profile.pppMtu;
    _pppMru = profile.pppMru;
    _authProtocol = profile.authProtocol;
    _authTimeout = profile.authTimeout;
    _ipv4Enabled = profile.ipv4Enabled;
    _ipv6Enabled = profile.ipv6Enabled;
    _requestStaticIp = profile.requestStaticIp;
    _staticIp = profile.staticIp;
    _addDefaultRoute = profile.addDefaultRoute;
    _routePrivateAddresses = profile.routePrivateAddresses;
    _appBasedEnabled = profile.appBasedEnabled;
    _allowedApps = List<String>.from(profile.allowedApps);
    _reconnectionEnabled = profile.reconnectionEnabled;
    _reconnectionCount = profile.reconnectionCount;
    _reconnectionInterval = profile.reconnectionInterval;
    _verifyHostName = profile.verifyHostName;
    _verifySSLCert = profile.verifySSLCert;
    _sslVersion = profile.sslVersion;
    _useCustomSni = profile.useCustomSni;
    _customSni = profile.customSni;
    _seTcpConnections = profile.seTcpConnections;
    _seEstablishIntervalMs = profile.seEstablishIntervalMs;
    _seDuplexMode = profile.seDuplexMode;
    _seEncrypt = profile.seEncrypt;
    _seCompress = profile.seCompress;
    _seDisableUdpAcceleration = profile.seDisableUdpAcceleration;
    _seUdpPort = profile.seUdpPort;
    _seDisableQos = profile.seDisableQos;
  }

  /// Persists the whole profile list, refreshing the active entry from the
  /// working settings first.
  Future<void> _persistProfiles() async {
    final prefs = await SharedPreferences.getInstance();
    await _persistProfilesWith(prefs);
  }

  Future<void> _persistProfilesWith(SharedPreferences prefs) async {
    final updated = _captureActiveProfile();
    final idx = _profiles.indexWhere((p) => p.id == _activeProfileId);
    if (idx >= 0) {
      _profiles[idx] = updated;
    } else {
      _profiles.add(updated);
    }
    await prefs.setString(
      'vpn_profiles',
      jsonEncode(_profiles.map((p) => p.toJson()).toList()),
    );
    await prefs.setString('vpn_active_profile_id', _activeProfileId);
  }

  /// Pushes the active profile's app-routing state to the native plugin, which
  /// keeps its own copy and only reads it when the tunnel is created.
  Future<void> _applyAppRoutingToNative() async {
    try {
      await _sstpFlutter.setAppBasedRule(
        enabled: _appBasedEnabled,
        packages: _allowedApps,
      );
    } catch (_) {}
  }

  // --- Profile CRUD ------------------------------------------------------

  /// Generates a fresh, unused profile id.
  String newProfileId() => _newProfileId();

  /// True when a profile for [host]:[port] using [protocol] already exists. Used
  /// to avoid adding the same public server to the list twice.
  bool hasProfileFor(
    String host,
    int port, {
    String protocol = VpnProtocol.sstp,
  }) {
    final normalized = host.trim().toLowerCase();
    return _profiles.any(
      (p) =>
          p.host.trim().toLowerCase() == normalized &&
          p.port == port &&
          p.protocol == protocol,
    );
  }

  /// Adds [profile] to the list. Used once a brand new profile is saved (the
  /// editor only creates it when Save is pressed) and when a public server is
  /// turned into a profile.
  Future<void> addProfile(VpnProfile profile) async {
    final idx = _profiles.indexWhere((p) => p.id == profile.id);
    if (idx >= 0) {
      _profiles[idx] = profile;
    } else {
      _profiles.add(profile);
    }
    await _persistProfiles();
    notifyListeners();
  }

  /// Replaces a profile (by id) with [updated].
  ///
  /// When the edited profile is the active one and any tunnel-affecting setting
  /// changed (server, TLS, PPP, routing, DNS, proxy, app routing, reconnection),
  /// a running tunnel is rebuilt so the new values actually take effect. Only
  /// purely cosmetic fields (name, colour, public flag) are ignored.
  Future<void> saveProfile(VpnProfile updated) async {
    final idx = _profiles.indexWhere((p) => p.id == updated.id);
    final isActive = updated.id == _activeProfileId;
    final previous = isActive ? _captureActiveProfile() : null;

    if (idx < 0) {
      _profiles.add(updated);
    } else {
      _profiles[idx] = updated;
    }

    var needsReconnect = false;
    if (isActive) {
      needsReconnect =
          previous != null && _tunnelSettingsChanged(previous, updated);
      _applyProfile(updated);
      await _applyAppRoutingToNative();
    }
    await _persistProfiles();
    notifyListeners();

    if (needsReconnect && _isTunnelActive) {
      unawaited(_reconnect());
    }
  }

  /// True while a tunnel exists in any non-idle state.
  bool get _isTunnelActive =>
      _status == VpnStatus.connected ||
      _status == VpnStatus.connecting ||
      _status == VpnStatus.reconnecting;

  /// True when a change between [a] and [b] requires rebuilding the tunnel.
  bool _tunnelSettingsChanged(VpnProfile a, VpnProfile b) {
    return a.protocol != b.protocol ||
        a.hubName != b.hubName ||
        a.clientMac != b.clientMac ||
        a.host != b.host ||
        a.port != b.port ||
        a.username != b.username ||
        a.password != b.password ||
        a.dns != b.dns ||
        a.dnsEnabled != b.dnsEnabled ||
        a.requestDns != b.requestDns ||
        a.proxyHost != b.proxyHost ||
        a.proxyPort != b.proxyPort ||
        a.proxyEnabled != b.proxyEnabled ||
        a.pppMtu != b.pppMtu ||
        a.pppMru != b.pppMru ||
        a.authProtocol != b.authProtocol ||
        a.authTimeout != b.authTimeout ||
        a.ipv4Enabled != b.ipv4Enabled ||
        a.ipv6Enabled != b.ipv6Enabled ||
        a.requestStaticIp != b.requestStaticIp ||
        a.staticIp != b.staticIp ||
        a.addDefaultRoute != b.addDefaultRoute ||
        a.routePrivateAddresses != b.routePrivateAddresses ||
        a.appBasedEnabled != b.appBasedEnabled ||
        !_samePackages(a.allowedApps, b.allowedApps) ||
        a.verifyHostName != b.verifyHostName ||
        a.verifySSLCert != b.verifySSLCert ||
        a.sslVersion != b.sslVersion ||
        a.useCustomSni != b.useCustomSni ||
        a.customSni != b.customSni ||
        a.reconnectionEnabled != b.reconnectionEnabled ||
        a.reconnectionCount != b.reconnectionCount ||
        a.reconnectionInterval != b.reconnectionInterval ||
        // SoftEther session options only take effect on a fresh login, so a
        // change while the tunnel is up has to rebuild it (otherwise editing
        // "Number of TCP connections" and saving would change nothing).
        a.seTcpConnections != b.seTcpConnections ||
        a.seEstablishIntervalMs != b.seEstablishIntervalMs ||
        a.seDuplexMode != b.seDuplexMode ||
        a.seEncrypt != b.seEncrypt ||
        a.seCompress != b.seCompress ||
        a.seDisableUdpAcceleration != b.seDisableUdpAcceleration ||
        a.seUdpPort != b.seUdpPort ||
        a.seDisableQos != b.seDisableQos;
  }

  Future<void> updateProfileMeta(
    String id, {
    required String name,
    required int colorIndex,
  }) async {
    final idx = _profiles.indexWhere((p) => p.id == id);
    if (idx < 0) return;
    _profiles[idx].name = name;
    _profiles[idx].colorIndex = colorIndex;
    if (id == _activeProfileId) {
      _activeName = name;
      _activeColorIndex = colorIndex;
    }
    await _persistProfiles();
    notifyListeners();
  }

  Future<void> deleteProfile(String id) async {
    _profiles.removeWhere((p) => p.id == id);
    if (id == _activeProfileId) {
      if (_profiles.isEmpty) {
        _profiles.add(VpnProfile(id: _newProfileId()));
      }
      _applyProfile(_profiles.first);
      await _applyAppRoutingToNative();
    }
    await _persistProfiles();
    notifyListeners();
  }

  /// Moves the profile at [oldIndex] to [newIndex] and persists the new order.
  ///
  /// [newIndex] is already the final position, as delivered by the
  /// ReorderableListView `onReorderItem` callback.
  Future<void> reorderProfiles(int oldIndex, int newIndex) async {
    if (oldIndex < 0 || oldIndex >= _profiles.length) return;
    if (newIndex < 0 || newIndex >= _profiles.length) return;
    if (newIndex == oldIndex) return;

    final profile = _profiles.removeAt(oldIndex);
    _profiles.insert(newIndex, profile);
    await _persistProfiles();
    notifyListeners();
  }

  /// Adds [incoming] as brand new profiles, leaving the existing list untouched.
  ///
  /// Every profile gets a fresh id, so an imported profile can never overwrite a
  /// local one. When the device reports which apps are installed, the imported
  /// app allowlist is reduced to the ones actually present. Returns how many
  /// profiles were added.
  Future<int> importProfiles(List<VpnProfile> incoming) async {
    if (incoming.isEmpty) return 0;

    Set<String>? installedPackages;
    final installedApps = await getInstalledApps();
    if (installedApps.isNotEmpty) {
      installedPackages = installedApps
          .map((app) => app.packageName)
          .whereType<String>()
          .toSet();
    }

    for (final profile in incoming) {
      final json = profile.toJson();
      json['id'] = _freshProfileId();
      final imported = VpnProfile.fromJson(json);
      if (installedPackages != null) {
        imported.allowedApps = imported.allowedApps
            .where(installedPackages.contains)
            .toList();
      }
      _profiles.add(imported);
    }

    await _persistProfiles();
    notifyListeners();
    return incoming.length;
  }

  /// Makes [id] the active profile. When a tunnel is already up, it is rebuilt
  /// so the connection actually switches to the newly selected server instead of
  /// silently keeping the old one.
  Future<void> selectProfile(String id) async {
    final idx = _profiles.indexWhere((p) => p.id == id);
    if (idx < 0) return;
    final changed = id != _activeProfileId;
    _applyProfile(_profiles[idx]);
    await _persistProfiles();
    await _applyAppRoutingToNative();
    notifyListeners();

    if (changed && _status != VpnStatus.disconnected) {
      // Fire-and-forget: the picker closes immediately while the tunnel is
      // torn down and brought back up with the new profile's settings.
      unawaited(_reconnect());
    }
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    _host = prefs.getString('vpn_host') ?? '';
    _port = prefs.getInt('vpn_port') ?? 443;
    _username = prefs.getString('vpn_username') ?? '';
    _password = prefs.getString('vpn_password') ?? '';
    _dns = prefs.getString('vpn_dns') ?? '8.8.8.8';
    _dnsEnabled = prefs.getBool('vpn_dns_enabled') ?? false;
    _proxyHost = prefs.getString('vpn_proxy_host') ?? '';
    _proxyPort = prefs.getInt('vpn_proxy_port') ?? 8080;
    _proxyEnabled = prefs.getBool('vpn_proxy_enabled') ?? false;
    _verifyHostName = prefs.getBool('vpn_verify_hostname') ?? false;
    _verifySSLCert = prefs.getBool('vpn_verify_ssl') ?? false;
    _sslVersion = prefs.getString('vpn_ssl_version') ?? 'AUTO';
    _useCustomSni = prefs.getBool('vpn_use_custom_sni') ?? false;
    _customSni = prefs.getString('vpn_custom_sni') ?? '';
    _connectionLogEnabled = prefs.getBool('vpn_connection_log') ?? true;
    _pppMtu = prefs.getInt('vpn_ppp_mtu') ?? 1500;
    _pppMru = prefs.getInt('vpn_ppp_mru') ?? 1500;
    _authProtocol = prefs.getString('vpn_auth_protocol') ?? 'AUTO';
    _authTimeout = prefs.getInt('vpn_auth_timeout') ?? 5;
    _ipv4Enabled = prefs.getBool('vpn_ipv4_enabled') ?? true;
    _ipv6Enabled = prefs.getBool('vpn_ipv6_enabled') ?? false;
    _requestStaticIp = prefs.getBool('vpn_request_static_ip') ?? false;
    _staticIp = prefs.getString('vpn_static_ip') ?? '';
    _addDefaultRoute = prefs.getBool('vpn_add_default_route') ?? true;
    _routePrivateAddresses = prefs.getBool('vpn_route_private') ?? false;
    _requestDns = prefs.getBool('vpn_request_dns') ?? true;
    _reconnectionEnabled = prefs.getBool('vpn_reconnection_enabled') ?? false;
    _reconnectionCount = prefs.getInt('vpn_reconnection_count') ?? 3;
    _reconnectionInterval = prefs.getInt('vpn_reconnection_interval') ?? 10;
    _appBasedEnabled = prefs.getBool('vpn_app_based_enabled') ?? false;
    _allowedApps = prefs.getStringList('vpn_allowed_apps') ?? [];

    // Propagate the persisted logging preference to both native plugins so logs
    // are collected even before this session's first connect.
    unawaited(_applyConnectionLogging());

    // Saved app selections shared by all profiles.
    final rawPresets = prefs.getString('vpn_app_presets');
    if (rawPresets != null && rawPresets.isNotEmpty) {
      try {
        final decoded = jsonDecode(rawPresets) as List;
        _appPresets = decoded
            .whereType<Map>()
            .map((e) => AppListPreset.fromJson(e.cast<String, dynamic>()))
            .toList();
      } catch (_) {
        _appPresets = [];
      }
    }

    // Load the stored profiles. When none exist yet (a fresh install or an
    // upgrade from the single-configuration version) migrate the legacy
    // settings above into the first profile.
    final rawProfiles = prefs.getString('vpn_profiles');
    if (rawProfiles != null && rawProfiles.isNotEmpty) {
      try {
        final decoded = jsonDecode(rawProfiles) as List;
        _profiles = decoded
            .whereType<Map>()
            .map((e) => VpnProfile.fromJson(e.cast<String, dynamic>()))
            .toList();
      } catch (_) {
        _profiles = [];
      }
    }

    if (_profiles.isEmpty) {
      final migrated = VpnProfile(
        id: _newProfileId(),
        host: _host,
        port: _port,
        username: _username,
        password: _password,
        dns: _dns,
        dnsEnabled: _dnsEnabled,
        requestDns: _requestDns,
        proxyHost: _proxyHost,
        proxyPort: _proxyPort,
        proxyEnabled: _proxyEnabled,
        pppMtu: _pppMtu,
        pppMru: _pppMru,
        authProtocol: _authProtocol,
        authTimeout: _authTimeout,
        ipv4Enabled: _ipv4Enabled,
        ipv6Enabled: _ipv6Enabled,
        requestStaticIp: _requestStaticIp,
        staticIp: _staticIp,
        addDefaultRoute: _addDefaultRoute,
        routePrivateAddresses: _routePrivateAddresses,
        appBasedEnabled: _appBasedEnabled,
        allowedApps: _allowedApps,
        reconnectionEnabled: _reconnectionEnabled,
        reconnectionCount: _reconnectionCount,
        reconnectionInterval: _reconnectionInterval,
        verifyHostName: _verifyHostName,
        verifySSLCert: _verifySSLCert,
        sslVersion: _sslVersion,
        useCustomSni: _useCustomSni,
        customSni: _customSni,
      );
      _profiles = [migrated];
      _applyProfile(migrated);
      await _persistProfilesWith(prefs);
    } else {
      final activeId = prefs.getString('vpn_active_profile_id');
      final active = _profiles.firstWhere(
        (p) => p.id == activeId,
        orElse: () => _profiles.first,
      );
      _applyProfile(active);
    }

    // Restore the last known addresses so the UI does not flash "not connected"
    // while the fresh values are being fetched after a cold start or resume.
    _externalIpBefore = prefs.getString('vpn_ip_before') ?? '';
    _beforeLocation = prefs.getString('vpn_ip_before_location') ?? '';
    _externalIpAfter = prefs.getString('vpn_ip_after') ?? '';
    _afterLocation = prefs.getString('vpn_ip_after_location') ?? '';
    _internalIp = prefs.getString('vpn_internal_ip') ?? '';

    // Restore the traffic counters so they survive the app being killed or
    // recreated while a tunnel was running (e.g. screen off for a while).
    _totalDownload = prefs.getInt('vpn_total_download') ?? 0;
    _totalUpload = prefs.getInt('vpn_total_upload') ?? 0;
    notifyListeners();

    // The plugin keeps its own copy of the app-routing state; make it match the
    // active profile instead of trusting whatever a previous run left behind.
    await _applyAppRoutingToNative();

    // Sync with the native side in case a tunnel is already running.
    await syncStatus();

    // Populate the "before" public IP for the disconnected state.
    refreshExternalIp();
  }

  Future<void> saveSettings({
    required String host,
    required int port,
    required String username,
    required String password,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    _host = host;
    _port = port;
    _username = username;
    _password = password;
    await prefs.setString('vpn_host', host);
    await prefs.setInt('vpn_port', port);
    await prefs.setString('vpn_username', username);
    await prefs.setString('vpn_password', password);
    await _persistProfiles();
    notifyListeners();
  }

  Future<void> saveDnsSettings(String dns, bool enabled) async {
    final prefs = await SharedPreferences.getInstance();
    _dns = dns;
    _dnsEnabled = enabled;
    await prefs.setString('vpn_dns', dns);
    await prefs.setBool('vpn_dns_enabled', enabled);
    await _persistProfiles();
    notifyListeners();
  }

  Future<void> saveProxySettings(String host, int port, bool enabled) async {
    final prefs = await SharedPreferences.getInstance();
    _proxyHost = host;
    _proxyPort = port;
    _proxyEnabled = enabled;
    await prefs.setString('vpn_proxy_host', host);
    await prefs.setInt('vpn_proxy_port', port);
    await prefs.setBool('vpn_proxy_enabled', enabled);
    await _persistProfiles();
    notifyListeners();
  }

  Future<void> saveAdvancedSettings({
    required bool verifyHostName,
    required bool verifySSLCert,
    required String sslVersion,
    required bool useCustomSni,
    required String customSni,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    _verifyHostName = verifyHostName;
    _verifySSLCert = verifySSLCert;
    _sslVersion = sslVersion;
    _useCustomSni = useCustomSni;
    _customSni = customSni;
    await prefs.setBool('vpn_verify_hostname', verifyHostName);
    await prefs.setBool('vpn_verify_ssl', verifySSLCert);
    await prefs.setString('vpn_ssl_version', sslVersion);
    await prefs.setBool('vpn_use_custom_sni', useCustomSni);
    await prefs.setString('vpn_custom_sni', customSni);
    await _persistProfiles();
    notifyListeners();
  }

  Future<void> saveTunnelSettings({
    required int pppMtu,
    required int pppMru,
    required String authProtocol,
    required int authTimeout,
    required bool ipv4Enabled,
    required bool ipv6Enabled,
    required bool requestStaticIp,
    required String staticIp,
    required bool addDefaultRoute,
    required bool routePrivateAddresses,
    required bool requestDns,
    required bool reconnectionEnabled,
    required int reconnectionCount,
    required int reconnectionInterval,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    _pppMtu = pppMtu;
    _pppMru = pppMru;
    _authProtocol = authProtocol;
    _authTimeout = authTimeout;
    _ipv4Enabled = ipv4Enabled;
    _ipv6Enabled = ipv6Enabled;
    _requestStaticIp = requestStaticIp;
    _staticIp = staticIp;
    _addDefaultRoute = addDefaultRoute;
    _routePrivateAddresses = routePrivateAddresses;
    _requestDns = requestDns;
    _reconnectionEnabled = reconnectionEnabled;
    _reconnectionCount = reconnectionCount;
    _reconnectionInterval = reconnectionInterval;

    await prefs.setInt('vpn_ppp_mtu', pppMtu);
    await prefs.setInt('vpn_ppp_mru', pppMru);
    await prefs.setString('vpn_auth_protocol', authProtocol);
    await prefs.setInt('vpn_auth_timeout', authTimeout);
    await prefs.setBool('vpn_ipv4_enabled', ipv4Enabled);
    await prefs.setBool('vpn_ipv6_enabled', ipv6Enabled);
    await prefs.setBool('vpn_request_static_ip', requestStaticIp);
    await prefs.setString('vpn_static_ip', staticIp);
    await prefs.setBool('vpn_add_default_route', addDefaultRoute);
    await prefs.setBool('vpn_route_private', routePrivateAddresses);
    await prefs.setBool('vpn_request_dns', requestDns);
    await prefs.setBool('vpn_reconnection_enabled', reconnectionEnabled);
    await prefs.setInt('vpn_reconnection_count', reconnectionCount);
    await prefs.setInt('vpn_reconnection_interval', reconnectionInterval);
    await _persistProfiles();
    notifyListeners();
  }

  /// Returns every installed app that has a launcher icon, for the app
  /// selection screen. Icons are delivered as PNG bytes.
  Future<List<InstalledAppInfo>> getInstalledApps() async {
    try {
      return await _sstpFlutter.getInstalledApps();
    } catch (_) {
      return [];
    }
  }

  /// Persists the app-routing state and re-establishes an active tunnel so the
  /// new routing takes effect immediately. The native side only reads the list
  /// when the VPN interface is created, hence the reconnect.
  ///
  /// [packages] is stored even when [enabled] is false, so the selection is not
  /// lost when the user turns app-based routing off.
  Future<void> saveAppRouting({
    required bool enabled,
    required List<String> packages,
  }) async {
    final changed =
        enabled != _appBasedEnabled || !_samePackages(_allowedApps, packages);

    try {
      await _sstpFlutter.setAppBasedRule(enabled: enabled, packages: packages);
    } catch (_) {
      return;
    }

    _appBasedEnabled = enabled;
    _allowedApps = List<String>.from(packages);
    notifyListeners();

    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('vpn_app_based_enabled', enabled);
    await prefs.setStringList('vpn_allowed_apps', _allowedApps);
    await _persistProfiles();

    if (changed &&
        (_status == VpnStatus.connected ||
            _status == VpnStatus.connecting ||
            _status == VpnStatus.reconnecting)) {
      // Fire-and-forget: the settings screen should close immediately while the
      // tunnel is rebuilt in the background.
      unawaited(_reconnect());
    }
  }

  bool _samePackages(List<String> a, List<String> b) {
    if (a.length != b.length) return false;
    final set = a.toSet();
    for (final pkg in b) {
      if (!set.contains(pkg)) return false;
    }
    return true;
  }

  // --- Saved app selections ---------------------------------------------

  Future<void> _persistAppPresets() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      'vpn_app_presets',
      jsonEncode(_appPresets.map((p) => p.toJson()).toList()),
    );
  }

  /// Saves [packages] under [name]. Overwrites an existing preset with the same
  /// name so the list does not fill up with duplicates.
  Future<void> saveAppPreset(String name, List<String> packages) async {
    final trimmed = name.trim();
    if (trimmed.isEmpty) return;

    final idx = _appPresets.indexWhere(
      (p) => p.name.toLowerCase() == trimmed.toLowerCase(),
    );
    if (idx >= 0) {
      _appPresets[idx].packages = List<String>.from(packages);
    } else {
      _appPresets.add(
        AppListPreset(id: _newProfileId(), name: trimmed, packages: packages),
      );
    }
    await _persistAppPresets();
    notifyListeners();
  }

  Future<void> deleteAppPreset(String id) async {
    _appPresets.removeWhere((p) => p.id == id);
    await _persistAppPresets();
    notifyListeners();
  }

  /// Tears the current tunnel down and brings it back up, preserving the
  /// configured settings. Used when a change only takes effect on a fresh
  /// connection (e.g. the app allowlist).
  Future<void> _reconnect() async {
    await disconnect();
    // Give the system a moment to release the previous VPN interface before
    // establishing a new one.
    await Future.delayed(const Duration(milliseconds: 1200));
    await connect();
  }

  String _getSSLVersion() {
    switch (effectiveSslVersion) {
      case 'TLSv1':
        return SSLVersions.TLSv1;
      case 'TLSv1_1':
        return SSLVersions.TLSv1_1;
      case 'TLSv1_2':
        return SSLVersions.TLsv1_2;
      case 'TLSv1_3':
        return SSLVersions.TLsv1_3;
      default:
        return SSLVersions.DEFAULT;
    }
  }

  Future<bool> requestPermission() async {
    try {
      return await (_isSoftetherActive
          ? _softether.takePermission()
          : _sstpFlutter.takePermission());
    } catch (e) {
      return false;
    }
  }

  /// True when the tunnel that is (or is about to be) active uses SoftEther.
  bool get _isSoftetherActive => _protocol == VpnProtocol.softether;

  /// True when the tunnel currently in use belongs to the SoftEther plugin.
  /// Falls back to the active profile's protocol before the first connection.
  bool get _isSoftetherTunnel =>
      (_tunnelProtocol ?? _protocol) == VpnProtocol.softether;

  /// True when the system has exempted SoftSSTP from battery optimization.
  Future<bool> isIgnoringBatteryOptimizations() =>
      _sstpFlutter.isIgnoringBatteryOptimizations();

  /// Asks the system to exempt SoftSSTP from battery optimization, so Android
  /// does not stop the tunnel while the app is in the background.
  Future<bool> requestIgnoreBatteryOptimizations() =>
      _sstpFlutter.requestIgnoreBatteryOptimizations();

  Future<void> connect() async {
    // A fresh user-initiated connect restarts the Auto negotiation from the top
    // (TLS 1.3 / MSCHAPv2 first).
    _autoTlsIndex = 0;
    _autoAuthIndex = 0;
    _autoRetryScheduled = false;
    _ipcpRetryCount = 0;
    _negotiating = tlsAuto || authAuto;
    await _startConnection();
  }

  /// Reacts to a connection failure reported by the native layer.
  ///
  /// When the profile negotiates automatically, the rejected candidate is
  /// replaced with the next one and the attempt is retried. When the setting is
  /// pinned, or the Auto list is exhausted, a clear message is shown instead.
  void _handleConnectionFailure(String code) {
    // Act on the first report only. The native layer may send the same failure
    // twice for one attempt; stepping the Auto list twice would skip a
    // candidate (TLS 1.3 -> 1.1 instead of TLS 1.3 -> 1.2).
    if (_failureHandled) return;
    _failureHandled = true;

    // 1) Step down to the next Auto candidate when one is still available.
    if (code == 'SSL_HANDSHAKE_FAILED' &&
        tlsAuto &&
        _autoTlsIndex < _autoTlsOrder.length - 1) {
      final rejected = _autoTlsOrder[_autoTlsIndex];
      _autoTlsIndex++;
      _scheduleNegotiationRetry(
        '$rejected is not supported — trying '
        '${_tlsLabel(_autoTlsOrder[_autoTlsIndex])}…',
      );
      return;
    }

    if (code == 'PPP_AUTH_FAILED' &&
        authAuto &&
        _autoAuthIndex < _autoAuthOrder.length - 1) {
      final rejected = _autoAuthOrder[_autoAuthIndex];
      _autoAuthIndex++;
      _scheduleNegotiationRetry(
        '$rejected is not supported — trying '
        '${_autoAuthOrder[_autoAuthIndex]}…',
      );
      return;
    }

    // 2) Rebuild the whole connection when the address negotiation was rejected.
    // Some public servers answer a Configure-Reject once and accept on a retry.
    if (code == 'IPCP_FAILED' && _ipcpRetryCount < _maxIpcpRetries) {
      _ipcpRetryCount++;
      // Keep driving the retries ourselves so the native retry loop stays off.
      _negotiating = true;
      _scheduleNegotiationRetry(
        'Address negotiation rejected — retrying '
        '($_ipcpRetryCount/$_maxIpcpRetries)…',
      );
      return;
    }

    // 3) Nothing left to try: replace the raw native error with an explanation.
    if (code == 'SSL_HANDSHAKE_FAILED') {
      _errorCode = code;
      _errorMessage = tlsAuto
          ? 'The server rejected every TLS version SoftSSTP tried (1.3 → 1.0).'
          : 'The server rejected $sslVersionLabel. This profile pins a single '
                'TLS version — set SSL Version to Auto so SoftSSTP can pick one the '
                'server supports.';
    } else if (code == 'PPP_AUTH_FAILED') {
      _errorCode = code;
      _errorMessage = authAuto
          ? 'The server rejected every authentication protocol SoftSSTP tried '
                '(MSCHAPv2, PAP).'
          : 'The server rejected the $_authProtocol authentication protocol. '
                'Set Authentication to Auto so SoftSSTP can try another protocol.';
    } else if (code == 'IPCP_FAILED') {
      _errorCode = code;
      _errorMessage = _requestStaticIp
          ? 'The server rejected the static IP address "$_staticIp". Disable the '
                'static address (request it automatically) or use another server.'
          : 'The server could not assign an IP address for this session. This is '
                'usually temporary — try again or pick another server.';
    } else if (code == 'NO_ADDRESS_FAMILY') {
      _errorCode = code;
      _errorMessage =
          'This profile has neither IPv4 nor IPv6 enabled. Turn on '
          'IPv4 (or IPv6) in the profile settings and try again.';
    }
    // Giving up: leave the negotiation state so a later connect() starts clean.
    _negotiating = false;
    notifyListeners();
  }

  /// Shows a short notice and retries the connection with the next Auto
  /// candidate once the previous attempt has fully torn down.
  void _scheduleNegotiationRetry(String notice) {
    if (_autoRetryScheduled) return;
    _autoRetryScheduled = true;

    _status = VpnStatus.connecting;
    _statusMessage = notice;
    _errorCode = '';
    _errorMessage = '';
    notifyListeners();

    Future.delayed(const Duration(milliseconds: 1800), () async {
      _autoRetryScheduled = false;
      if (!_negotiating) return;
      await _startConnection();
    });
  }

  Future<void> _startConnection() async {
    // Every new attempt starts with a clean failure slot.
    _failureHandled = false;
    if (!isConfigured) return;

    // Claim the VPN slot right before connecting, exactly like a fresh user
    // action. This dethrones another active VPN (e.g. AdGuard) so our native
    // establish() is not silently refused. When consent is still required the
    // system dialog is shown here.
    final granted = await requestPermission();
    if (!granted) {
      _status = VpnStatus.disconnected;
      _errorCode = 'VPN_PERMISSION_REQUIRED';
      _errorMessage =
          'VPN permission is required. Please allow SoftSSTP to set up a VPN '
          'connection, then try again.';
      notifyListeners();
      return;
    }

    try {
      _status = VpnStatus.connecting;
      // Keep the "trying another option" notice visible across the retry.
      if (!_negotiating) _statusMessage = '';
      _errorMessage = '';
      _errorCode = '';
      // A fresh session starts from zero traffic.
      _totalDownload = 0;
      _totalUpload = 0;
      // Drop the previous tunnel's exit address and invalidate lookups that may
      // still be in flight for it, so "Via VPN" cannot show the old server.
      _ipGeneration++;
      _externalIpAfter = '';
      _afterLocation = '';
      _exitIpRetryRound = 0;
      await saveTrafficTotals();
      notifyListeners();

      // From here on the tunnel belongs to the active profile's protocol.
      _tunnelProtocol = _protocol;

      if (_isSoftetherActive) {
        await _startSoftetherConnection();
        return;
      }

      SSTPServer server = SSTPServer(
        host: _host,
        port: _port,
        username: _username,
        password: _password,
        androidConfiguration: SSTPAndroidConfiguration(
          verifyHostName: _verifyHostName,
          useTrustedCert: false,
          verifySSLCert: _verifySSLCert,
          sslVersion: _getSSLVersion(),
          useCustomSni: _useCustomSni,
          customSni: _customSni,
          pppMru: _pppMru,
          pppMtu: _pppMtu,
          pppAuthProtocol: effectiveAuthProtocol,
          pppAuthTimeout: _authTimeout,
          pppIpv4Enabled: _ipv4Enabled,
          pppIpv6Enabled: _ipv6Enabled,
          requestStaticIpv4: _requestStaticIp,
          staticIpv4Address: _staticIp,
          addDefaultRoute: _addDefaultRoute,
          routePrivateAddresses: _routePrivateAddresses,
          requestDns: _requestDns,
          // While Auto is still negotiating, the native retry loop is disabled
          // so the app can downgrade the TLS/auth candidates itself.
          reconnectionEnabled: _reconnectionEnabled && !_negotiating,
          reconnectionCount: _reconnectionCount,
          reconnectionInterval: _reconnectionInterval,
          showDisconnectOnNotification: true,
          notificationText: "SoftSSTP VPN Connected",
        ),
        iosConfiguration: SSTPIOSConfiguration(
          enableMSCHAP2: true,
          enableCHAP: false,
          enablePAP: false,
          enableTLS: false,
        ),
      );

      await _sstpFlutter.saveServerData(server: server);

      // Make sure the native side knows whether to persist the log.
      await _sstpFlutter.setConnectionLogging(enabled: _connectionLogEnabled);

      if (_dnsEnabled && _dns.isNotEmpty) {
        await _sstpFlutter.enableDns(dns: _dns);
      }

      if (_proxyEnabled && _proxyHost.isNotEmpty) {
        await _sstpFlutter.enableProxy(
          proxy: SSTPProxy(ip: _proxyHost, port: _proxyPort.toString()),
        );
      }

      await _sstpFlutter.connectVpn();
    } catch (e) {
      _status = VpnStatus.disconnected;
      _errorCode = 'VPN_CONNECT_FAILED';
      _errorMessage = 'Could not start the VPN connection: $e';
      notifyListeners();
    }
  }

  /// Builds the SoftEther server descriptor and asks the plugin to connect.
  Future<void> _startSoftetherConnection() async {
    // The app's App Routing is an allow-list ("only these apps use the VPN"),
    // which the SoftEther plugin applies natively via addAllowedApplication.
    final allowedApps = _appBasedEnabled
        ? List<String>.from(_allowedApps)
        : const <String>[];

    final server = SoftetherServer(
      host: _host,
      port: _port,
      username: _username,
      password: _password,
      hub: _hubName.trim().isEmpty ? 'DEFAULT' : _hubName.trim(),
      clientMac: _clientMac.trim(),
      dns: (_dnsEnabled && _dns.isNotEmpty) ? _dns : '8.8.8.8',
      mtu: _pppMtu,
      addDefaultRoute: _addDefaultRoute,
      allowedApps: allowedApps,
      maxConnections: _seTcpConnections,
      establishIntervalMs: _seEstablishIntervalMs,
      duplexMode: _seDuplexMode,
      useEncrypt: _seEncrypt,
      useCompress: _seCompress,
      disableUdpAcceleration: _seDisableUdpAcceleration,
      udpPort: _seUdpPort,
      qos: !_seDisableQos,
    );
    // Make sure the native side knows whether to persist the log.
    await _softether.setConnectionLogging(enabled: _connectionLogEnabled);
    await _softether.connect(server: server);
  }

  Future<void> disconnect() async {
    // A manual disconnect stops any pending Auto negotiation retry.
    _negotiating = false;
    _autoRetryScheduled = false;
    try {
      if (_isSoftetherTunnel) {
        await _softether.disconnect();
      } else {
        await _sstpFlutter.disconnect();
        if (_dnsEnabled) {
          await _sstpFlutter.disableDNS();
        }
        if (_proxyEnabled) {
          await _sstpFlutter.disableProxy();
        }
      }
      _status = VpnStatus.disconnected;
      _statusMessage = '';
      _totalDownload = 0;
      _totalUpload = 0;
      _internalIp = '';
      _softetherConnectedAt = null;
      // Drop the tunnel exit address; it is no longer valid.
      _externalIpAfter = '';
      _afterLocation = '';
      // Invalidate any exit-address lookup that is still in flight.
      _ipGeneration++;
      notifyListeners();
      await _persistIpState();
      await saveTrafficTotals();
    } catch (e) {
      _status = VpnStatus.disconnected;
      _statusMessage = '';
      notifyListeners();
    }
  }

  Future<void> toggleConnection() async {
    if (_status == VpnStatus.connected ||
        _status == VpnStatus.connecting ||
        _status == VpnStatus.reconnecting) {
      await disconnect();
    } else {
      await connect();
    }
  }

  Future<String?> checkLastConnectionStatus() async {
    try {
      final status = await (_isSoftetherTunnel
          ? _softether.checkLastConnectionStatus()
          : _sstpFlutter.checkLastConnectionStatus());
      return status.toString();
    } catch (e) {
      return null;
    }
  }

  /// Re-reads the real connection state from the native side. Used when the app
  /// returns to the foreground, so the UI matches the actual tunnel/notification
  /// state instead of the (possibly stale) in-memory status.
  Future<void> syncStatus() async {
    final status = await checkLastConnectionStatus();
    switch (status) {
      case 'Connected':
        _status = VpnStatus.connected;
        break;
      case 'Connecting':
        _status = VpnStatus.connecting;
        break;
      case 'Reconnecting':
        _status = VpnStatus.reconnecting;
        break;
      default:
        _status = VpnStatus.disconnected;
        _internalIp = '';
        _externalIpAfter = '';
        _afterLocation = '';
    }
    notifyListeners();

    if (_status == VpnStatus.connected) {
      if (_internalIp.isEmpty) {
        try {
          final ip = await (_isSoftetherTunnel
              ? _softether.getAssignedIp()
              : _sstpFlutter.getAssignedIp());
          if (ip.isNotEmpty) {
            _internalIp = ip;
            notifyListeners();
            _persistIpState();
          }
        } catch (_) {}
      }
      // Recover the authoritative traffic totals, in case the per-second
      // updates were missed while the app was in the background.
      try {
        final totals = await (_isSoftetherTunnel
            ? _softether.getTrafficTotals()
            : _sstpFlutter.getTrafficTotals());
        if (totals[0] > _totalDownload) _totalDownload = totals[0];
        if (totals[1] > _totalUpload) _totalUpload = totals[1];
        notifyListeners();
      } catch (_) {}
      if (_externalIpAfter.isEmpty) {
        Future.delayed(const Duration(seconds: 1), refreshExternalIp);
      }
    } else {
      _persistIpState();
      refreshExternalIp();
    }
  }

  /// Fetches the current public IP (plus country/city) and stores it either as
  /// the "before" (direct) or "after" (tunnel) address depending on the
  /// connection state.
  ///
  /// The target row is decided *before* the request is made and the result is
  /// discarded when the state changes meanwhile. Previously the target was
  /// picked after the await, so a lookup started while the tunnel was still
  /// coming up (or tearing down) leaked the direct address into "Via VPN" — the
  /// request had already left through the direct route.
  ///
  /// For the exit address the lookup is retried a few times: right after
  /// connecting the routes may not be applied yet, so the first response can
  /// still be the direct (or previous tunnel's) address.
  Future<void> refreshExternalIp() async {
    final wantAfter = _status == VpnStatus.connected;
    final generation = _ipGeneration;

    // A lookup is already running. Ask for another pass instead of dropping the
    // request; the state may have changed while the running one was in flight.
    if (_ipLookupInFlight) {
      _ipLookupPending = true;
      return;
    }

    _ipLookupInFlight = true;
    try {
      final maxAttempts = wantAfter ? 5 : 1;
      for (var attempt = 1; attempt <= maxAttempts; attempt++) {
        final info = await _fetchExternalIpInfo();

        // The connection state changed while we were waiting: throw the result
        // away, a fresh lookup for the new state is already scheduled.
        if (generation != _ipGeneration) return;
        if (info == null || info.ip.isEmpty) break;

        if (wantAfter) {
          if (_status != VpnStatus.connected) return;

          // An exit address identical to the direct one means the lookup
          // escaped the tunnel (routes not ready yet). Retry, and on the last
          // attempt give up rather than show a wrong address.
          final leaked =
              _externalIpBefore.isNotEmpty && info.ip == _externalIpBefore;
          if (leaked) {
            if (attempt < maxAttempts) {
              await Future.delayed(const Duration(seconds: 2));
              if (generation != _ipGeneration ||
                  _status != VpnStatus.connected) {
                return;
              }
              continue;
            }
            // Still leaking after every attempt. Rather than show a wrong
            // address, leave "Via VPN" pending and try again a few more times
            // (bounded, so a server whose exit IP truly equals the direct one
            // cannot loop forever).
            if (_exitIpRetryRound < 3) {
              _exitIpRetryRound++;
              final retryGeneration = _ipGeneration;
              Future.delayed(const Duration(seconds: 5), () {
                if (retryGeneration == _ipGeneration &&
                    _status == VpnStatus.connected) {
                  refreshExternalIp();
                }
              });
            }
            return;
          }

          _externalIpAfter = info.ip;
          _afterLocation = info.location;
        } else {
          if (_status == VpnStatus.connected) return;
          _externalIpBefore = info.ip;
          _beforeLocation = info.location;
        }

        notifyListeners();
        await _persistIpState();
        break;
      }
    } finally {
      _ipLookupInFlight = false;
      if (_ipLookupPending) {
        _ipLookupPending = false;
        unawaited(refreshExternalIp());
      }
    }
  }

  /// Persists the last known addresses so they can be restored instantly on the
  /// next cold start or resume, avoiding a flash of "not connected".
  Future<void> _persistIpState() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString('vpn_ip_before', _externalIpBefore);
      await prefs.setString('vpn_ip_before_location', _beforeLocation);
      await prefs.setString('vpn_ip_after', _externalIpAfter);
      await prefs.setString('vpn_ip_after_location', _afterLocation);
      await prefs.setString('vpn_internal_ip', _internalIp);
    } catch (_) {}
  }

  /// Persists the traffic counters. Called when the app goes to the background
  /// so a later resume (or process restart) does not lose the accumulated
  /// totals while the tunnel stayed up.
  Future<void> saveTrafficTotals() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setInt('vpn_total_download', _totalDownload);
      await prefs.setInt('vpn_total_upload', _totalUpload);
    } catch (_) {}
  }

  Future<IpGeoInfo?> _fetchExternalIpInfo() async {
    // Geo-aware endpoints first so a single request yields IP + country + city.
    const endpoints = [
      'https://ipwho.is/',
      'https://ipapi.co/json/',
      'http://ip-api.com/json/',
    ];
    for (final endpoint in endpoints) {
      final client = HttpClient()
        ..connectionTimeout = const Duration(seconds: 6);
      try {
        final request = await client.getUrl(Uri.parse(endpoint));
        final response = await request.close().timeout(
          const Duration(seconds: 8),
        );
        if (response.statusCode == 200) {
          final body = await response.transform(utf8.decoder).join();
          final info = _parseGeoInfo(jsonDecode(body));
          if (info != null) return info;
        }
      } catch (_) {
        // Try the next endpoint.
      } finally {
        client.close(force: true);
      }
    }

    // Last resort: IP only, without geolocation.
    final ip = await _fetchIpOnly();
    return ip == null ? null : IpGeoInfo(ip: ip);
  }

  IpGeoInfo? _parseGeoInfo(dynamic decoded) {
    if (decoded is! Map) return null;

    // ip-api.com reports failures with status != "success".
    final status = decoded['status'];
    if (status is String && status.toLowerCase() != 'success') return null;

    final ip = (decoded['ip'] ?? decoded['query'])?.toString();
    if (ip == null || ip.isEmpty) return null;

    final country =
        (decoded['country'] ?? decoded['country_name'])?.toString() ?? '';
    final city = decoded['city']?.toString() ?? '';

    return IpGeoInfo(ip: ip, country: country, city: city);
  }

  Future<String?> _fetchIpOnly() async {
    const endpoints = ['https://api.ipify.org', 'https://icanhazip.com'];
    for (final endpoint in endpoints) {
      final client = HttpClient()
        ..connectionTimeout = const Duration(seconds: 6);
      try {
        final request = await client.getUrl(Uri.parse(endpoint));
        final response = await request.close().timeout(
          const Duration(seconds: 8),
        );
        if (response.statusCode == 200) {
          final body = await response.transform(utf8.decoder).join();
          final ip = body.trim();
          if (ip.isNotEmpty && ip.length <= 45) {
            return ip;
          }
        }
      } catch (_) {
        // Try the next endpoint.
      } finally {
        client.close(force: true);
      }
    }
    return null;
  }

  Future<String?> addCertificate() async {
    try {
      return await _sstpFlutter.addCertificate();
    } catch (e) {
      return null;
    }
  }

  Future<void> saveConnectionLogging(bool enabled) async {
    final prefs = await SharedPreferences.getInstance();
    _connectionLogEnabled = enabled;
    await prefs.setBool('vpn_connection_log', enabled);
    await _applyConnectionLogging();
    notifyListeners();
  }

  /// Pushes the current logging preference to both native plugins.
  Future<void> _applyConnectionLogging() async {
    try {
      await _sstpFlutter.setConnectionLogging(enabled: _connectionLogEnabled);
    } catch (_) {}
    try {
      await _softether.setConnectionLogging(enabled: _connectionLogEnabled);
    } catch (_) {}
  }

  Future<String> getConnectionLog() async {
    try {
      if (_isSoftetherTunnel) {
        return await _softether.getConnectionLog();
      }
      return await _sstpFlutter.getConnectionLog();
    } catch (e) {
      return '';
    }
  }

  Future<String> getConnectionLogPath() async {
    try {
      if (_isSoftetherTunnel) {
        return await _softether.getConnectionLogPath();
      }
      return await _sstpFlutter.getConnectionLogPath();
    } catch (e) {
      return '';
    }
  }

  Future<void> clearConnectionLog() async {
    try {
      if (_isSoftetherTunnel) {
        await _softether.clearConnectionLog();
      } else {
        await _sstpFlutter.clearConnectionLog();
      }
    } catch (_) {}
  }

  /// Formats a transfer rate. [bitsPerSecond] is what the native side reports,
  /// so the result uses bit-based units (bit/s, Kbit/s, Mbit/s, Gbit/s), which
  /// is the convention for link/throughput speeds.
  String formatSpeed(int bitsPerSecond) {
    final bits = bitsPerSecond < 0 ? 0 : bitsPerSecond;
    if (bits < 1000) return '$bits bit/s';
    if (bits < 1000 * 1000) {
      return '${(bits / 1000).toStringAsFixed(1)} Kbit/s';
    }
    if (bits < 1000 * 1000 * 1000) {
      return '${(bits / (1000 * 1000)).toStringAsFixed(1)} Mbit/s';
    }
    return '${(bits / (1000 * 1000 * 1000)).toStringAsFixed(2)} Gbit/s';
  }

  /// Formats a transferred volume. [bits] is what the native side reports, so it
  /// is converted to bytes first; the result uses byte-based units (B, KB, MB,
  /// GB), which is the convention for data volume.
  String formatTraffic(int bits) {
    final bytes = bits <= 0 ? 0 : bits ~/ 8;
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    if (bytes < 1024 * 1024 * 1024) {
      return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
    }
    return '${(bytes / (1024 * 1024 * 1024)).toStringAsFixed(2)} GB';
  }

  String formatDuration(Duration duration) {
    String twoDigits(int n) => n.toString().padLeft(2, '0');
    String hours = twoDigits(duration.inHours);
    String minutes = twoDigits(duration.inMinutes.remainder(60));
    String seconds = twoDigits(duration.inSeconds.remainder(60));
    return '$hours:$minutes:$seconds';
  }
}
