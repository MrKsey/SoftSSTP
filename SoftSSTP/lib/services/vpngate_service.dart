import 'dart:convert';
import 'dart:io';

import '../models/public_profile.dart';
import '../models/vpn_profile.dart';

/// Fetches and parses the public server table from https://www.vpngate.net and
/// measures the latency from the device to each server.
class VpngateService {
  static const String listUrl = 'https://www.vpngate.net/en/';

  // Matches one table row.
  static final RegExp _rowRe = RegExp(r'<tr>(.*?)</tr>', dotAll: true);

  // The MS-SSTP cell: "SSTP Hostname :<br><b><span ...>host[:port]</span></b>".
  static final RegExp _sstpRe = RegExp(
    r"SSTP Hostname\s*:.*?<b><span[^>]*>\s*([^<]+?)\s*</span></b>",
    dotAll: true,
  );

  // The DDNS hostname cell: the first "<b><span ...>host</span></b>" in a row.
  static final RegExp _ddnsRe = RegExp(
    r"<b><span[^>]*>\s*([^<]+?)\s*</span></b>",
  );

  // The SSL-VPN (SoftEther) cell exposes the TCP port, e.g. "TCP: 443".
  static final RegExp _softetherPortRe = RegExp(
    r"howto_softether\.aspx.*?TCP:\s*(\d+)",
    dotAll: true,
  );

  // The SSL-VPN (SoftEther) cell may also expose a UDP port, e.g. "UDP: 443".
  static final RegExp _softetherUdpRe = RegExp(
    r"howto_softether\.aspx.*?UDP:\s*(\d+)",
    dotAll: true,
  );

  // Country flag image and the country name right after it.
  static final RegExp _flagRe = RegExp(
    r"images/flags/([A-Za-z]{2})\.png'[^>]*><br>([^<]+)",
  );

  // Line quality throughput, e.g. "151.43 Mbps".
  static final RegExp _qualityRe = RegExp(r"([\d.]+ (?:Mbps|Gbps|Kbps))");

  /// Downloads the page and returns every server that exposes an SSTP or
  /// SoftEther (SSL-VPN) endpoint.
  Future<List<PublicProfile>> fetchServers() async {
    final html = await _fetch(listUrl);
    return parse(html);
  }

  Future<String> _fetch(String url) async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    try {
      final request = await client.getUrl(Uri.parse(url));
      request.headers.set(
        HttpHeaders.userAgentHeader,
        'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 '
        '(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36',
      );
      request.headers.set(HttpHeaders.acceptHeader, 'text/html');
      final response = await request.close().timeout(
        const Duration(seconds: 20),
      );
      return await response.transform(utf8.decoder).join();
    } finally {
      client.close(force: true);
    }
  }

  /// Parses the raw HTML table into SSTP and SoftEther (SSL-VPN) servers.
  List<PublicProfile> parse(String html) {
    final servers = <PublicProfile>[];
    final seen = <String>{};

    for (final rowMatch in _rowRe.allMatches(html)) {
      final row = rowMatch.group(1);
      if (row == null) continue;

      final flag = _flagRe.firstMatch(row);
      final countryCode = flag?.group(1)?.toUpperCase() ?? '';
      final countryName = flag?.group(2)?.trim() ?? '';
      final quality = _qualityRe.firstMatch(row)?.group(1) ?? '';

      // MS-SSTP endpoint.
      final sstp = _sstpRe.firstMatch(row)?.group(1)?.trim();
      if (sstp != null && sstp.isNotEmpty) {
        final parsed = _splitHostPort(sstp);
        if (seen.add(
          '${VpnProtocol.sstp}:${parsed.$1.toLowerCase()}:${parsed.$2}',
        )) {
          servers.add(
            PublicProfile(
              protocol: VpnProtocol.sstp,
              host: parsed.$1,
              port: parsed.$2,
              countryCode: countryCode,
              countryName: countryName,
              lineQuality: quality,
            ),
          );
        }
      }

      // SoftEther (SSL-VPN) endpoint: the DDNS host plus the SSL-VPN TCP port.
      final ddns = _ddnsRe.firstMatch(row)?.group(1)?.trim();
      final sePort = int.tryParse(
        _softetherPortRe.firstMatch(row)?.group(1) ?? '',
      );
      final seUdpPort = int.tryParse(
        _softetherUdpRe.firstMatch(row)?.group(1) ?? '',
      );
      if (ddns != null && ddns.isNotEmpty && sePort != null) {
        if (seen.add(
          '${VpnProtocol.softether}:${ddns.toLowerCase()}:$sePort',
        )) {
          servers.add(
            PublicProfile(
              protocol: VpnProtocol.softether,
              host: ddns,
              port: sePort,
              udpPort: seUdpPort ?? 0,
              countryCode: countryCode,
              countryName: countryName,
              lineQuality: quality,
            ),
          );
        }
      }
    }

    return servers;
  }

  /// Splits "host[:port]" into a host and a port (443 when absent).
  (String, int) _splitHostPort(String rawHost) {
    var host = rawHost;
    var port = 443;
    final colon = rawHost.lastIndexOf(':');
    if (colon > 0) {
      final parsedPort = int.tryParse(rawHost.substring(colon + 1));
      if (parsedPort != null) {
        host = rawHost.substring(0, colon);
        port = parsedPort;
      }
    }
    return (host, port);
  }

  /// Measures the TCP connect time to [host]:[port] in milliseconds.
  ///
  /// Returns null when the server is unreachable or the attempt times out.
  Future<int?> measurePing(String host, int port) async {
    final stopwatch = Stopwatch()..start();
    Socket? socket;
    try {
      socket = await Socket.connect(
        host,
        port,
        timeout: const Duration(seconds: 4),
      );
      stopwatch.stop();
      return stopwatch.elapsedMilliseconds;
    } catch (_) {
      return null;
    } finally {
      try {
        socket?.destroy();
      } catch (_) {}
    }
  }
}
