/// A public VPN Gate server that speaks MS-SSTP.
class PublicProfile {
  /// SSTP hostname without the port.
  final String host;

  /// SSTP port (443 when the list does not specify one).
  final int port;

  /// ISO 3166-1 alpha-2 country code, e.g. "JP".
  final String countryCode;

  /// Country name as shown on the site, e.g. "Japan".
  final String countryName;

  /// The site's "Line quality" throughput, e.g. "151.43 Mbps".
  final String lineQuality;

  const PublicProfile({
    required this.host,
    required this.port,
    required this.countryCode,
    required this.countryName,
    required this.lineQuality,
  });

  /// "host" or "host:port" depending on whether a custom port is used.
  String get displayHost => port == 443 ? host : '$host:$port';

  /// Key used to group and filter servers by country. Prefers the readable
  /// country name and falls back to the code when the name is missing.
  String get countryKey => countryName.isNotEmpty ? countryName : countryCode;

  /// Stable identity used to detect duplicates and to filter out servers that
  /// are already in the profile list (compared by host + port).
  String get key => '${host.trim().toLowerCase()}:$port';

  /// Suggested profile name: the uppercase country code, a dash and the server,
  /// e.g. "RU - server.com". Falls back to the bare host when the country is
  /// unknown.
  String get defaultName =>
      countryCode.isEmpty ? displayHost : '$countryCode - $displayHost';

  /// The site's "Line quality" throughput expressed in Mbps, so the list can be
  /// sorted numerically. Returns 0 when the value is missing or unparseable.
  double get qualityMbps {
    final match = RegExp(r'([\d.]+)\s*(Mbps|Gbps|Kbps)', caseSensitive: false)
        .firstMatch(lineQuality);
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
