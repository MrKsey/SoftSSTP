import 'dart:convert';
import 'dart:io';

import '../models/public_profile.dart';

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

  // Country flag image and the country name right after it.
  static final RegExp _flagRe =
      RegExp(r"images/flags/([A-Za-z]{2})\.png'[^>]*><br>([^<]+)");

  // Line quality throughput, e.g. "151.43 Mbps".
  static final RegExp _qualityRe = RegExp(r"([\d.]+ (?:Mbps|Gbps|Kbps))");

  /// Downloads the page and returns only the servers that expose an SSTP host.
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
      final response =
          await request.close().timeout(const Duration(seconds: 20));
      return await response.transform(utf8.decoder).join();
    } finally {
      client.close(force: true);
    }
  }

  /// Parses the raw HTML table, keeping only rows that carry an SSTP hostname.
  List<PublicProfile> parse(String html) {
    final servers = <PublicProfile>[];
    final seen = <String>{};

    for (final rowMatch in _rowRe.allMatches(html)) {
      final row = rowMatch.group(1);
      if (row == null) continue;

      final sstp = _sstpRe.firstMatch(row);
      if (sstp == null) continue;

      final rawHost = sstp.group(1)?.trim() ?? '';
      if (rawHost.isEmpty) continue;

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

      if (!seen.add('$host:$port')) continue;

      final flag = _flagRe.firstMatch(row);
      servers.add(PublicProfile(
        host: host,
        port: port,
        countryCode: flag?.group(1)?.toUpperCase() ?? '',
        countryName: flag?.group(2)?.trim() ?? '',
        lineQuality: _qualityRe.firstMatch(row)?.group(1) ?? '',
      ));
    }

    return servers;
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
