// Refreshes the bundled VPN Gate server list used when vpngate.net is
// unreachable (see `lib/services/vpngate_snapshot.dart`).
//
// Run from the SoftSSTP/ directory:
//
//   dart run tool/update_vpngate_snapshot.dart
//
// It fetches https://www.vpngate.net/en/, parses the SSTP and SoftEther
// endpoints with the same parser the app uses and rewrites
// assets/data/vpngate_servers.json. The CI snapshot workflow runs it on a
// schedule; run it by hand to refresh the list outside that schedule.
import 'dart:convert';
import 'dart:io';

import 'package:softsstp/models/public_profile.dart';
import 'package:softsstp/services/vpngate_service.dart';

const String _outputPath = 'assets/data/vpngate_servers.json';

Future<void> main(List<String> args) async {
  final service = VpngateService();
  stdout.writeln('Fetching ${VpngateService.listUrl} ...');

  final List<PublicProfile> servers;
  try {
    servers = await service.fetchServers();
  } catch (e) {
    stderr.writeln('Could not fetch the server list: $e');
    exitCode = 1;
    return;
  }

  if (servers.isEmpty) {
    stderr.writeln('The page was fetched but no servers were parsed.');
    exitCode = 1;
    return;
  }

  final payload = <String, dynamic>{
    'generated': DateTime.now().toUtc().toIso8601String(),
    'source': VpngateService.listUrl,
    'servers': servers.map((s) => s.toJson()).toList(),
  };

  final file = File(_outputPath);
  await file.parent.create(recursive: true);
  const encoder = JsonEncoder.withIndent('  ');
  await file.writeAsString('${encoder.convert(payload)}\n');

  stdout.writeln('Wrote ${servers.length} servers to $_outputPath');
}
