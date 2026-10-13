import 'vpn_protocol.dart';

/// A public VPN Gate server that speaks MS-SSTP or SoftEther (SSL-VPN).
class PublicProfile {
  /// VPN protocol this server entry uses: [VpnProtocol.sstp] or
  /// [VpnProtocol.softether].
  final String protocol;

  /// Server hostname (without the port).
  final String host;

  /// Protocol port (443 when the list does not specify one).
  final int port;

  /// SoftEther UDP port for UDP acceleration (0 when the list does not specify
  /// one). Ignored for SSTP.
  final int udpPort;

  /// ISO 3166-1 alpha-2 country code, e.g. "JP".
  final String countryCode;

  /// Country name as shown on the site, e.g. "Japan".
  final String countryName;

  /// The site's "Line quality" throughput, e.g. "151.43 Mbps".
  final String lineQuality;

  const PublicProfile({
    this.protocol = VpnProtocol.sstp,
    required this.host,
    required this.port,
    this.udpPort = 0,
    required this.countryCode,
    required this.countryName,
    required this.lineQuality,
  });

  /// Serialises the entry for the bundled offline snapshot
  /// (`assets/data/vpngate_servers.json`). Empty optional fields are left out so
  /// the snapshot stays compact.
  Map<String, dynamic> toJson() => {
    'protocol': protocol,
    'host': host,
    'port': port,
    if (udpPort != 0) 'udpPort': udpPort,
    if (countryCode.isNotEmpty) 'countryCode': countryCode,
    if (countryName.isNotEmpty) 'countryName': countryName,
    if (lineQuality.isNotEmpty) 'lineQuality': lineQuality,
  };

  /// Reads an entry written by [toJson]. Missing fields fall back to the same
  /// defaults as the constructor, so an older snapshot keeps working.
  static PublicProfile fromJson(Map<String, dynamic> json) => PublicProfile(
    protocol: (json['protocol'] as String?) ?? VpnProtocol.sstp,
    host: (json['host'] as String?) ?? '',
    port: (json['port'] as num?)?.toInt() ?? 443,
    udpPort: (json['udpPort'] as num?)?.toInt() ?? 0,
    countryCode: (json['countryCode'] as String?) ?? '',
    countryName: (json['countryName'] as String?) ?? '',
    lineQuality: (json['lineQuality'] as String?) ?? '',
  );

  /// "host" or "host:port" depending on whether a custom port is used.
  String get displayHost => port == 443 ? host : '$host:$port';

  /// Key used to group and filter servers by country. Prefers the readable
  /// country name and falls back to the code when the name is missing.
  String get countryKey => countryName.isNotEmpty ? countryName : countryCode;

  /// Stable identity used to detect duplicates and to filter out servers that
  /// are already in the profile list (compared by protocol + host + port).
  String get key =>
      '${protocol.trim().toLowerCase()}:${host.trim().toLowerCase()}:$port';

  /// Suggested profile name: the uppercase country code, a dash and the server,
  /// e.g. "RU - server.com". Falls back to the bare host when the country is
  /// unknown.
  String get defaultName =>
      countryCode.isEmpty ? displayHost : '$countryCode - $displayHost';

  /// The site's "Line quality" throughput expressed in Mbps, so the list can be
  /// sorted numerically. Returns 0 when the value is missing or unparseable.
  double get qualityMbps {
    final match = RegExp(
      r'([\d.]+)\s*(Mbps|Gbps|Kbps)',
      caseSensitive: false,
    ).firstMatch(lineQuality);
    if (match == null) return 0;
    final value = double.tryParse(match.group(1)!) ?? 0;
    switch (match.group(2)!.toLowerCase()) {
      case 'gbps':
        return value * 1000;
      case 'kbps':
        return value / 1000;
      default:
        return value;
    }
  }
}
