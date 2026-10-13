import 'dart:convert';

import 'package:flutter/services.dart';

import '../models/public_profile.dart';

/// The bundled copy of the VPN Gate server list, used when the live page cannot
/// be reached. The asset is refreshed by the snapshot workflow
/// (`.github/workflows/vpngate-snapshot.yml`), so the list is a point-in-time
/// snapshot and may be older than what vpngate.net shows right now.
class VpngateSnapshot {
  static const String assetPath = 'assets/data/vpngate_servers.json';

  /// Loads the bundled list. Returns an empty list when the asset is missing or
  /// malformed, so a broken snapshot never breaks the public-profiles screen.
  static Future<List<PublicProfile>> load() async {
    try {
      final raw = await rootBundle.loadString(assetPath);
      final decoded = jsonDecode(raw);
      if (decoded is! Map) return const [];
      final servers = decoded['servers'];
      if (servers is! List) return const [];
      return servers
          .whereType<Map>()
          .map((entry) => PublicProfile.fromJson(entry.cast<String, dynamic>()))
          .where((profile) => profile.host.isNotEmpty)
          .toList();
    } catch (_) {
      return const [];
    }
  }
}
