import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../providers/vpn_provider.dart';
import '../theme/app_theme.dart';

/// Shows the native connection logs collected by both plugins.
///
/// SSTP and SoftEther each write their own log file. The screen shows both, so
/// switching the protocol of the selected profile no longer changes which log is
/// visible.
class LogScreen extends StatefulWidget {
  const LogScreen({super.key});

  @override
  State<LogScreen> createState() => _LogScreenState();
}

class _LogScreenState extends State<LogScreen> {
  List<({String protocol, String path, String log})> _logs = const [];
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh() async {
    final vpnProvider = context.read<VpnProvider>();
    final logs = await vpnProvider.getConnectionLogsByProtocol();
    if (!mounted) return;
    setState(() {
      _logs = logs;
    });
  }

  Future<void> _clear() async {
    await context.read<VpnProvider>().clearConnectionLog();
    await _refresh();
  }

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _plainText()));
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(SnackBar(content: Text('Log copied to clipboard')));
  }

  /// The log as plain text, protocol sections included, for the clipboard.
  String _plainText() {
    final buffer = StringBuffer();
    for (final entry in _logs) {
      if (entry.log.trim().isEmpty) continue;
      buffer.writeln('=== ${entry.protocol} log (${entry.path}) ===');
      buffer.writeln(entry.log);
    }
    return buffer.toString();
  }

  bool get _hasAnyLog => _logs.any((entry) => entry.log.trim().isNotEmpty);

  @override
  Widget build(BuildContext context) {
    final hasAnyLog = _hasAnyLog;
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      appBar: AppBar(
        title: const Text('Connection Log'),
        actions: [
          IconButton(
            icon: const Icon(Icons.copy_outlined),
            tooltip: 'Copy',
            onPressed: hasAnyLog ? _copy : null,
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline),
            tooltip: 'Clear',
            onPressed: _clear,
          ),
        ],
      ),
      body: hasAnyLog
          ? ListView(
              padding: const EdgeInsets.all(12),
              children: [
                for (final entry in _logs)
                  if (entry.log.trim().isNotEmpty) ...[
                    _ProtocolHeader(protocol: entry.protocol, path: entry.path),
                    SelectableText(
                      entry.log,
                      style: TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 11,
                        height: 1.35,
                        color: AppTheme.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 16),
                  ],
              ],
            )
          : Center(
              child: Text(
                'No log entries yet.\nConnect to a server to collect logs.',
                textAlign: TextAlign.center,
                style: TextStyle(color: AppTheme.textSecondary),
              ),
            ),
    );
  }
}

/// Section header naming the protocol whose log follows, plus its file path.
class _ProtocolHeader extends StatelessWidget {
  const _ProtocolHeader({required this.protocol, required this.path});

  final String protocol;
  final String path;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      color: AppTheme.cardColor,
      padding: const EdgeInsets.all(8),
      margin: const EdgeInsets.only(bottom: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            '$protocol log',
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: AppTheme.textPrimary,
            ),
          ),
          if (path.isNotEmpty)
            Text(
              path,
              style: TextStyle(fontSize: 10, color: AppTheme.textSecondary),
            ),
        ],
      ),
    );
  }
}
