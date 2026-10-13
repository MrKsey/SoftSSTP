/// The VPN protocol a profile connects with.
///
/// Kept in its own file with no Flutter imports so the offline server-list tool
/// (`tool/update_vpngate_snapshot.dart`) can reuse the parser and the models
/// without pulling in `dart:ui`.
class VpnProtocol {
  /// MS-SSTP over TLS (the app's original protocol).
  static const String sstp = 'sstp';

  /// SoftEther VPN (SSL-VPN) over TLS.
  static const String softether = 'softether';

  static const List<String> values = [sstp, softether];

  /// Human readable label, e.g. "SSTP" or "SoftEther".
  static String label(String value) =>
      value == softether ? 'SoftEther' : 'SSTP';

  /// Short badge label for server lists.
  static String badge(String value) =>
      value == softether ? 'SoftEther' : 'SSTP';
}
