import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../providers/vpn_provider.dart';
import '../theme/app_theme.dart';

/// Shows the native connection log.
///
/// Both plugins append to one shared file, so the screen shows a single
/// chronological log. Each session is delimited by a
/// "=== NEW <PROTOCOL> SESSION ===" header, so switching protocol is visible as
/// a new block after a blank line.
class LogScreen extends StatefulWidget {
  const LogScreen({super.key});

  @override
  State<LogScreen> createState() => _LogScreenState();
}

class _LogScreenState extends State<LogScreen> {
  String _log = '';
  String _path = '';
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
    final log = await vpnProvider.getConnectionLog();
    final path = await vpnProvider.getConnectionLogPath();
    if (!mounted) return;
    setState(() {
      _log = log;
      _path = path;
    });
  }

  Future<void> _clear() async {
    await context.read<VpnProvider>().clearConnectionLog();
    await _refresh();
  }

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _log));
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('Log copied to clipboard')));
  }

  bool get _hasLog => _log.trim().isNotEmpty;

  @override
  Widget build(BuildContext context) {
    final hasLog = _hasLog;
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      appBar: AppBar(
        title: const Text('Connection Log'),
        actions: [
          IconButton(
            icon: const Icon(Icons.copy_outlined),
            tooltip: 'Copy',
            onPressed: hasLog ? _copy : null,
          ),
          IconButton(
            icon: const Icon(Icons.delete_outline),
            tooltip: 'Clear',
            onPressed: _clear,
          ),
        ],
      ),
      body: hasLog
          ? ListView(
              padding: const EdgeInsets.all(12),
              children: [
                if (_path.isNotEmpty)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 8),
                    child: Text(
                      _path,
                      style: TextStyle(
                        fontSize: 10,
                        color: AppTheme.textSecondary,
                      ),
                    ),
                  ),
                SelectableText(
                  _log,
                  style: TextStyle(
                    fontFamily: 'monospace',
                    fontSize: 11,
                    height: 1.35,
                    color: AppTheme.textPrimary,
                  ),
                ),
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
