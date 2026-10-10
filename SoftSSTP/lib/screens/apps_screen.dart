import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:sstp_flutter/app_info.dart';
import '../providers/vpn_provider.dart';
import '../theme/app_theme.dart';

/// Multi-select list of installed applications. Pops with the selected package
/// names (or null when dismissed without saving). The caller is responsible for
/// persisting the result.
class AppsScreen extends StatefulWidget {
  final List<String> initiallySelected;

  const AppsScreen({super.key, required this.initiallySelected});

  @override
  State<AppsScreen> createState() => _AppsScreenState();
}

class _AppsScreenState extends State<AppsScreen> {
  final _searchController = TextEditingController();

  List<InstalledAppInfo> _allApps = [];
  late Set<String> _selected;
  bool _loading = true;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _selected = widget.initiallySelected.toSet();
    _loadApps();
  }

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  Future<void> _loadApps() async {
    final apps = await context.read<VpnProvider>().getInstalledApps();
    if (!mounted) return;

    // Selected apps first, then the rest, each group alphabetically. The order
    // is fixed here (not on every toggle) so items don't jump while selecting.
    apps.sort((a, b) {
      final aSelected = _selected.contains(a.packageName) ? 0 : 1;
      final bSelected = _selected.contains(b.packageName) ? 0 : 1;
      if (aSelected != bSelected) return aSelected - bSelected;
      return (a.name ?? '').toLowerCase().compareTo((b.name ?? '').toLowerCase());
    });

    setState(() {
      _allApps = apps;
      _loading = false;
    });
  }

  List<InstalledAppInfo> get _filteredApps {
    if (_query.isEmpty) return _allApps;
    final q = _query.toLowerCase();
    return _allApps.where((app) {
      final name = (app.name ?? '').toLowerCase();
      final pkg = (app.packageName ?? '').toLowerCase();
      return name.contains(q) || pkg.contains(q);
    }).toList();
  }

  void _toggle(String? packageName) {
    if (packageName == null) return;
    setState(() {
      if (_selected.contains(packageName)) {
        _selected.remove(packageName);
      } else {
        _selected.add(packageName);
      }
    });
  }

  void _selectAllVisible() {
    setState(() {
      _selected.addAll(
        _filteredApps.map((app) => app.packageName).whereType<String>(),
      );
    });
  }

  void _clearSelection() {
    setState(() {
      _selected.clear();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => Navigator.pop(context),
        ),
        title: const Text('Select applications'),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, _selected.toList()),
            child: Text(
              'Save',
              style: TextStyle(
                color: AppTheme.primaryColor,
                fontWeight: FontWeight.w600,
              ),
            ),
          ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
            child: TextField(
              controller: _searchController,
              style: TextStyle(color: AppTheme.textPrimary),
              decoration: InputDecoration(
                hintText: 'Search apps',
                prefixIcon: Icon(
                  Icons.search,
                  color: AppTheme.textSecondary,
                ),
                suffixIcon: _query.isEmpty
                    ? null
                    : IconButton(
                        icon: Icon(
                          Icons.close,
                          color: AppTheme.textSecondary,
                        ),
                        onPressed: () {
                          _searchController.clear();
                          setState(() => _query = '');
                        },
                      ),
              ),
              onChanged: (value) => setState(() => _query = value.trim()),
            ),
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Row(
              children: [
                Text(
                  '${_selected.length} selected',
                  style: TextStyle(
                    fontSize: 14,
                    color: AppTheme.textSecondary,
                  ),
                ),
                const Spacer(),
                TextButton(
                  onPressed: _selectAllVisible,
                  child: const Text('Select all'),
                ),
                TextButton(
                  onPressed: _clearSelection,
                  child: const Text('Clear'),
                ),
              ],
            ),
          ),
          Divider(color: AppTheme.dividerColor, height: 1),
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _filteredApps.isEmpty
                    ? Center(
                        child: Text(
                          'No applications found',
                          style: TextStyle(color: AppTheme.textSecondary),
                        ),
                      )
                    : ListView.builder(
                        itemCount: _filteredApps.length,
                        itemBuilder: (context, index) {
                          final app = _filteredApps[index];
                          final selected =
                              _selected.contains(app.packageName);
                          return ListTile(
                            // 30% shorter than the default two-line tile (72dp),
                            // so more apps fit on screen.
                            minTileHeight: 50,
                            leading: _buildIcon(app.icon),
                            title: Text(
                              app.name ?? '',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                color: AppTheme.textPrimary,
                              ),
                            ),
                            subtitle: Text(
                              app.packageName ?? '',
                              maxLines: 1,
                              overflow: TextOverflow.ellipsis,
                              style: TextStyle(
                                fontSize: 12,
                                color: AppTheme.textSecondary,
                              ),
                            ),
                            trailing: Checkbox(
                              value: selected,
                              onChanged: (_) => _toggle(app.packageName),
                            ),
                            onTap: () => _toggle(app.packageName),
                          );
                        },
                      ),
          ),
        ],
      ),
    );
  }

  Widget _buildIcon(dynamic icon) {
    if (icon is List<int> && icon.isNotEmpty) {
      return ClipRRect(
        borderRadius: BorderRadius.circular(8),
        child: Image.memory(
          Uint8List.fromList(icon),
          width: 40,
          height: 40,
          gaplessPlayback: true,
          errorBuilder: (context, error, stackTrace) => _fallbackIcon(),
        ),
      );
    }
    return _fallbackIcon();
  }

  Widget _fallbackIcon() {
    return Container(
      width: 40,
      height: 40,
      decoration: BoxDecoration(
        color: AppTheme.dividerColor,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Icon(
        Icons.android,
        color: AppTheme.textSecondary,
        size: 22,
      ),
    );
  }
}
