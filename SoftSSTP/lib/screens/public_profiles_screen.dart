import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/public_profile.dart';
import '../models/vpn_profile.dart';
import '../providers/vpn_provider.dart';
import '../services/country_flags.dart';
import '../services/vpngate_service.dart';
import '../services/vpngate_snapshot.dart';
import '../theme/app_theme.dart';
import 'profile_settings_screen.dart';

/// Lists the public SSTP and SoftEther (SSL-VPN) servers from VPN Gate and lets
/// the user turn one into a profile. The username and password are always
/// "vpn" / "vpn".
class PublicProfilesScreen extends StatefulWidget {
  const PublicProfilesScreen({super.key});

  @override
  State<PublicProfilesScreen> createState() => _PublicProfilesScreenState();
}

/// The field the public server list is sorted by.
enum _SortKey { country, host, quality, ping }

extension on _SortKey {
  String get label {
    switch (this) {
      case _SortKey.country:
        return 'Country';
      case _SortKey.host:
        return 'Server address';
      case _SortKey.quality:
        return 'Bandwidth';
      case _SortKey.ping:
        return 'Ping';
    }
  }
}

class _PublicProfilesScreenState extends State<PublicProfilesScreen> {
  final VpngateService _service = VpngateService();
  final List<_ServerEntry> _entries = [];

  bool _loading = true;
  String _error = '';

  /// True when the list came from the bundled snapshot because vpngate.net
  /// could not be reached. Drives the "offline list" notice.
  bool _fromSnapshot = false;

  // Default ordering: fastest server first.
  _SortKey _sortKey = _SortKey.ping;
  bool _sortAsc = true;

  /// In-flight or finished ping measurements, keyed by "host:port". Servers that
  /// expose SSTP and SoftEther on the same endpoint share one measurement.
  final Map<String, Future<int?>> _pingFutures = {};

  // Filters. An empty country set means "all countries".
  bool _onlyPort443 = false;
  final Set<String> _selectedCountries = {};

  // Protocols to show. Empty means "all protocols".
  final Set<String> _selectedProtocols = {};

  /// Whether servers already added to profiles are shown. Hidden by default.
  bool _showAdded = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  /// Every country present in the loaded list, sorted by name.
  List<String> get _countries {
    final set = <String>{};
    for (final entry in _entries) {
      if (entry.profile.countryKey.isNotEmpty) {
        set.add(entry.profile.countryKey);
      }
    }
    final list = set.toList()
      ..sort((a, b) => a.toLowerCase().compareTo(b.toLowerCase()));
    return list;
  }

  /// Counts the filters that differ from their default. The "hide added"
  /// behaviour is the default, so it only counts when the user shows them.
  int get _activeFilterCount =>
      (_onlyPort443 ? 1 : 0) +
      (_selectedCountries.isNotEmpty ? 1 : 0) +
      (_selectedProtocols.isNotEmpty ? 1 : 0) +
      (_showAdded ? 1 : 0);

  /// Keys (`protocol:host:port`) of every server already present as a profile.
  Set<String> _addedKeys(VpnProvider provider) => provider.profiles
      .map((p) => '${p.protocol}:${p.host.trim().toLowerCase()}:${p.port}')
      .toSet();

  /// Resets every filter back to its default.
  void _resetFilters() {
    setState(() {
      _onlyPort443 = false;
      _selectedCountries.clear();
      _selectedProtocols.clear();
      _showAdded = false;
    });
  }

  /// The loaded servers narrowed down by the active filters.
  List<_ServerEntry> _filteredEntries(Set<String> addedKeys) {
    return _entries.where((entry) {
      if (!_showAdded && addedKeys.contains(entry.profile.key)) return false;
      if (_onlyPort443 && entry.profile.port != 443) return false;
      if (_selectedProtocols.isNotEmpty &&
          !_selectedProtocols.contains(entry.profile.protocol)) {
        return false;
      }
      if (_selectedCountries.isNotEmpty &&
          !_selectedCountries.contains(entry.profile.countryKey)) {
        return false;
      }
      return true;
    }).toList();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = '';
      _entries.clear();
      _pingFutures.clear();
      _fromSnapshot = false;
    });

    try {
      final servers = await _service.fetchServers();
      if (!mounted) return;
      setState(() {
        _entries.addAll(servers.map(_ServerEntry.new));
        _loading = false;
      });
      _measurePings();
    } catch (_) {
      // The live page could not be reached: fall back to the bundled snapshot so
      // the list still works without a connection.
      final snapshot = await VpngateSnapshot.load();
      if (!mounted) return;
      if (snapshot.isEmpty) {
        setState(() {
          _loading = false;
          _error = 'Could not load the server list. Check your connection.';
        });
        return;
      }
      setState(() {
        _fromSnapshot = true;
        _entries.addAll(snapshot.map(_ServerEntry.new));
        _loading = false;
      });
      _measurePings();
    }
  }

  /// Measures the ping of every server once, a few at a time, updating each
  /// row as soon as its result arrives. Servers that share an endpoint (same
  /// host and port, e.g. SSTP and SoftEther on 443) are measured a single time.
  Future<void> _measurePings() async {
    const concurrency = 8;
    var next = 0;

    Future<void> worker() async {
      while (true) {
        final index = next++;
        if (index >= _entries.length) return;

        final entry = _entries[index];
        final key = '${entry.profile.host}:${entry.profile.port}';
        final future = _pingFutures.putIfAbsent(
          key,
          () => _service.measurePing(entry.profile.host, entry.profile.port),
        );
        final ping = await future;
        if (!mounted) return;
        setState(() {
          entry.pingMs = ping;
          entry.measuring = false;
        });
      }
    }

    await Future.wait(List.generate(concurrency, (_) => worker()));
  }

  Future<void> _addProfile(PublicProfile server) async {
    final provider = context.read<VpnProvider>();

    // Never add the same server twice (compared by protocol + host + port).
    if (provider.hasProfileFor(
      server.host,
      server.port,
      protocol: server.protocol,
    )) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('${server.displayHost} is already in your profiles'),
        ),
      );
      return;
    }

    final profile = VpnProfile(
      id: provider.newProfileId(),
      // Default name is "CC - host[:port]"; the user can rename it in the editor.
      name: server.defaultName,
      isPublic: true,
      protocol: server.protocol,
      // VPN Gate public servers join the "VPNGATE" virtual hub.
      hubName: 'VPNGATE',
      host: server.host,
      port: server.port,
      username: 'vpn',
      password: 'vpn',
      seUdpPort: server.udpPort,
      // VPN Gate relays drop extra TCP links (and sometimes the whole session
      // when several are forced), so start public profiles with a single
      // connection. The editor still allows raising it to 8 for fast servers.
      seTcpConnections: 1,
    );

    await provider.addProfile(profile);
    if (!mounted) return;

    // Open the editor so the profile can be renamed right away.
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => ProfileSettingsScreen(profileId: profile.id),
      ),
    );
  }

  /// A sorted copy of the filtered server list according to the current sort
  /// key and direction. Unmeasured pings always sort last.
  List<_ServerEntry> _sortedEntries(Set<String> addedKeys) {
    final filtered = _filteredEntries(addedKeys);
    final list = List<_ServerEntry>.from(filtered);

    int byFields(_ServerEntry a, _ServerEntry b) {
      switch (_sortKey) {
        case _SortKey.country:
          final byCountry = a.profile.countryName.toLowerCase().compareTo(
            b.profile.countryName.toLowerCase(),
          );
          if (byCountry != 0) return byCountry;
          return a.profile.host.toLowerCase().compareTo(
            b.profile.host.toLowerCase(),
          );
        case _SortKey.host:
          return a.profile.host.toLowerCase().compareTo(
            b.profile.host.toLowerCase(),
          );
        case _SortKey.quality:
          return a.profile.qualityMbps.compareTo(b.profile.qualityMbps);
        case _SortKey.ping:
          return 0;
      }
    }

    list.sort((a, b) {
      if (_sortKey == _SortKey.ping) {
        final pa = a.pingMs;
        final pb = b.pingMs;
        // Unmeasured servers stay last regardless of the sort direction.
        if (pa == null && pb == null) return 0;
        if (pa == null) return 1;
        if (pb == null) return -1;
        return _sortAsc ? pa.compareTo(pb) : pb.compareTo(pa);
      }
      final result = byFields(a, b);
      return _sortAsc ? result : -result;
    });
    return list;
  }

  void _selectSort(_SortKey key) {
    setState(() {
      if (_sortKey == key) {
        _sortAsc = !_sortAsc;
      } else {
        _sortKey = key;
        // Text keys read best A→Z; numbers best smallest-first (fastest first).
        _sortAsc = true;
      }
    });
  }

  /// Opens the filter sheet (port 443 and country selection). Changes apply
  /// live, so the list behind the sheet updates as options are toggled.
  Future<void> _openFilterSheet() async {
    if (_entries.isEmpty) return;

    await showModalBottomSheet<void>(
      context: context,
      backgroundColor: AppTheme.cardColor,
      isScrollControlled: true,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      builder: (sheetContext) {
        return StatefulBuilder(
          builder: (sheetContext, setSheetState) {
            void refresh(VoidCallback change) {
              setState(change);
              setSheetState(() {});
            }

            final countries = _countries;
            return DraggableScrollableSheet(
              expand: false,
              initialChildSize: 0.6,
              minChildSize: 0.3,
              maxChildSize: 0.9,
              builder: (context, scrollController) {
                return ListView(
                  controller: scrollController,
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  children: [
                    Center(
                      child: Container(
                        width: 40,
                        height: 4,
                        decoration: BoxDecoration(
                          color: AppTheme.textSecondary.withValues(alpha: 0.3),
                          borderRadius: BorderRadius.circular(2),
                        ),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(20, 16, 12, 4),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              'Filters',
                              style: TextStyle(
                                fontSize: 18,
                                fontWeight: FontWeight.w600,
                                color: AppTheme.textPrimary,
                              ),
                            ),
                          ),
                          TextButton(
                            onPressed: _activeFilterCount == 0
                                ? null
                                : () => refresh(() {
                                    _onlyPort443 = false;
                                    _selectedCountries.clear();
                                    _selectedProtocols.clear();
                                    _showAdded = false;
                                  }),
                            child: const Text('Reset'),
                          ),
                        ],
                      ),
                    ),
                    SwitchListTile(
                      value: _showAdded,
                      onChanged: (value) => refresh(() => _showAdded = value),
                      title: Text(
                        'Show added servers',
                        style: TextStyle(color: AppTheme.textPrimary),
                      ),
                      subtitle: Text(
                        'Include servers already saved as profiles',
                        style: TextStyle(
                          fontSize: 12,
                          color: AppTheme.textSecondary,
                        ),
                      ),
                    ),
                    SwitchListTile(
                      value: _onlyPort443,
                      onChanged: (value) => refresh(() => _onlyPort443 = value),
                      title: Text(
                        'Port 443 only',
                        style: TextStyle(color: AppTheme.textPrimary),
                      ),
                      subtitle: Text(
                        'Hide servers that use a custom port',
                        style: TextStyle(
                          fontSize: 12,
                          color: AppTheme.textSecondary,
                        ),
                      ),
                    ),
                    Divider(color: AppTheme.dividerColor, height: 12),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(20, 8, 20, 4),
                      child: Text(
                        'Protocol',
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.w600,
                          color: AppTheme.textSecondary,
                        ),
                      ),
                    ),
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 20),
                      child: Wrap(
                        spacing: 8,
                        children: [
                          for (final protocol in VpnProtocol.values)
                            FilterChip(
                              label: Text(VpnProtocol.label(protocol)),
                              selected: _selectedProtocols.contains(protocol),
                              onSelected: (selected) => refresh(() {
                                if (selected) {
                                  _selectedProtocols.add(protocol);
                                } else {
                                  _selectedProtocols.remove(protocol);
                                }
                              }),
                            ),
                        ],
                      ),
                    ),
                    Divider(color: AppTheme.dividerColor, height: 12),
                    Padding(
                      padding: const EdgeInsets.fromLTRB(20, 8, 20, 4),
                      child: Row(
                        children: [
                          Expanded(
                            child: Text(
                              'Country',
                              style: TextStyle(
                                fontSize: 14,
                                fontWeight: FontWeight.w600,
                                color: AppTheme.textSecondary,
                              ),
                            ),
                          ),
                          if (_selectedCountries.isNotEmpty)
                            Text(
                              '${_selectedCountries.length} selected',
                              style: TextStyle(
                                fontSize: 12,
                                color: AppTheme.primaryColor,
                              ),
                            ),
                        ],
                      ),
                    ),
                    CheckboxListTile(
                      value: _selectedCountries.isEmpty,
                      onChanged: (_) => refresh(_selectedCountries.clear),
                      title: Text(
                        'All countries',
                        style: TextStyle(color: AppTheme.textPrimary),
                      ),
                      controlAffinity: ListTileControlAffinity.leading,
                      dense: true,
                    ),
                    for (final country in countries)
                      CheckboxListTile(
                        value: _selectedCountries.contains(country),
                        onChanged: (checked) => refresh(() {
                          if (checked == true) {
                            _selectedCountries.add(country);
                          } else {
                            _selectedCountries.remove(country);
                          }
                        }),
                        title: Text(
                          country,
                          style: TextStyle(color: AppTheme.textPrimary),
                        ),
                        controlAffinity: ListTileControlAffinity.leading,
                        dense: true,
                      ),
                    const SizedBox(height: 12),
                  ],
                );
              },
            );
          },
        );
      },
    );
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
        title: const Text('Public profiles'),
        actions: [
          IconButton(
            icon: Badge(
              isLabelVisible: _activeFilterCount > 0,
              label: Text('$_activeFilterCount'),
              child: const Icon(Icons.filter_list),
            ),
            tooltip: 'Filter',
            onPressed: _openFilterSheet,
          ),
          PopupMenuButton<_SortKey>(
            icon: const Icon(Icons.sort),
            tooltip: 'Sort by ${_sortKey.label}',
            initialValue: _sortKey,
            onSelected: _selectSort,
            itemBuilder: (context) => [
              for (final key in _SortKey.values)
                PopupMenuItem(
                  value: key,
                  child: Row(
                    children: [
                      SizedBox(
                        width: 24,
                        child: key == _sortKey
                            ? Icon(
                                Icons.check,
                                size: 18,
                                color: AppTheme.primaryColor,
                              )
                            : null,
                      ),
                      Text(key.label),
                    ],
                  ),
                ),
            ],
          ),
          IconButton(
            icon: Icon(_sortAsc ? Icons.arrow_upward : Icons.arrow_downward),
            tooltip: _sortAsc ? 'Ascending' : 'Descending',
            onPressed: () => setState(() => _sortAsc = !_sortAsc),
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Reload',
            onPressed: _loading ? null : _load,
          ),
        ],
      ),
      body: _buildBody(),
    );
  }

  Widget _buildBody() {
    if (_loading) {
      return const Center(child: CircularProgressIndicator());
    }
    if (_error.isNotEmpty) {
      return Center(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                _error,
                textAlign: TextAlign.center,
                style: TextStyle(color: AppTheme.textSecondary),
              ),
              const SizedBox(height: 16),
              ElevatedButton(onPressed: _load, child: const Text('Retry')),
            ],
          ),
        ),
      );
    }
    if (_entries.isEmpty) {
      return Center(
        child: Text(
          'No public servers found.',
          style: TextStyle(color: AppTheme.textSecondary),
        ),
      );
    }

    final provider = context.watch<VpnProvider>();
    final addedKeys = _addedKeys(provider);
    final sorted = _sortedEntries(addedKeys);
    final hiddenAdded = _showAdded
        ? 0
        : _entries.where((e) => addedKeys.contains(e.profile.key)).length;
    return Column(
      children: [
        _buildSortHeader(sorted.length, hiddenAdded),
        if (_fromSnapshot) _buildSnapshotNotice(),
        Expanded(
          child: sorted.isEmpty
              ? Center(
                  child: Padding(
                    padding: const EdgeInsets.all(24),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(
                          'No servers match the current filters.',
                          textAlign: TextAlign.center,
                          style: TextStyle(color: AppTheme.textSecondary),
                        ),
                        const SizedBox(height: 12),
                        TextButton(
                          onPressed: _resetFilters,
                          child: const Text('Reset filters'),
                        ),
                      ],
                    ),
                  ),
                )
              : ListView.builder(
                  padding: const EdgeInsets.all(20),
                  itemCount: sorted.length,
                  itemBuilder: (context, index) {
                    final entry = sorted[index];
                    final added = addedKeys.contains(entry.profile.key);
                    return _buildServerCard(entry, added);
                  },
                ),
        ),
      ],
    );
  }

  Widget _buildSortHeader(int shown, int hiddenAdded) {
    final measuring = _entries.any((e) => e.measuring);
    final filtered = shown != _entries.length;
    return Container(
      width: double.infinity,
      color: AppTheme.cardColor,
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
      child: Row(
        children: [
          Expanded(
            child: Text(
              filtered
                  ? '$shown of ${_entries.length} servers'
                  : '${_entries.length} servers',
              style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
            ),
          ),
          if (hiddenAdded > 0)
            Padding(
              padding: const EdgeInsets.only(right: 10),
              child: Text(
                '$hiddenAdded added hidden',
                style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
              ),
            ),
          if (measuring)
            Padding(
              padding: const EdgeInsets.only(right: 10),
              child: Text(
                'measuring ping…',
                style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
              ),
            ),
          Text(
            'Sort: ${_sortKey.label} ${_sortAsc ? '▲' : '▼'}',
            style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
          ),
        ],
      ),
    );
  }

  /// Shown when the list came from the bundled snapshot instead of the live
  /// page, so the user knows the entries and their ping may be out of date.
  Widget _buildSnapshotNotice() {
    return Container(
      width: double.infinity,
      color: AppTheme.cardColor,
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 10),
      child: Row(
        children: [
          Icon(
            Icons.cloud_off_outlined,
            size: 16,
            color: AppTheme.textSecondary,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              'Offline list: vpngate.net is unreachable, showing the built-in '
              'copy. Entries may be out of date.',
              style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildServerCard(_ServerEntry entry, bool added) {
    final server = entry.profile;
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 15.2),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
        border: added
            ? Border.all(color: AppTheme.primaryColor.withValues(alpha: 0.4))
            : null,
      ),
      child: InkWell(
        onTap: added ? null : () => _addProfile(server),
        borderRadius: BorderRadius.circular(16),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: AppTheme.primaryColor.withValues(alpha: 0.2),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Text(
                countryFlagEmoji(server.countryCode),
                style: const TextStyle(fontSize: 22),
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: Text(
                          server.displayHost,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                            color: AppTheme.textPrimary,
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      _protocolBadge(server.protocol),
                    ],
                  ),
                  const SizedBox(height: 4),
                  if (added)
                    Row(
                      children: [
                        Icon(
                          Icons.check_circle,
                          size: 14,
                          color: AppTheme.primaryColor,
                        ),
                        const SizedBox(width: 5),
                        Text(
                          'Already added',
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w500,
                            color: AppTheme.primaryColor,
                          ),
                        ),
                      ],
                    )
                  else
                    _buildSubtitle(entry),
                ],
              ),
            ),
            const SizedBox(width: 8),
            if (added)
              Icon(
                Icons.check_circle_outline,
                color: AppTheme.primaryColor.withValues(alpha: 0.6),
              )
            else
              Icon(Icons.add_circle_outline, color: AppTheme.primaryColor),
          ],
        ),
      ),
    );
  }

  /// Small chip that tells SSTP and SoftEther servers apart in the list.
  Widget _protocolBadge(String protocol) {
    final isSoftether = protocol == VpnProtocol.softether;
    final color = isSoftether ? AppTheme.primaryColor : AppTheme.textSecondary;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(
        VpnProtocol.badge(protocol),
        style: TextStyle(
          fontSize: 10,
          fontWeight: FontWeight.w600,
          color: color,
        ),
      ),
    );
  }

  Widget _buildSubtitle(_ServerEntry entry) {
    if (entry.measuring) {
      return Row(
        children: [
          SizedBox(
            width: 12,
            height: 12,
            child: CircularProgressIndicator(
              strokeWidth: 2,
              color: AppTheme.textSecondary,
            ),
          ),
          const SizedBox(width: 8),
          Text(
            'Measuring ping…',
            style: TextStyle(fontSize: 13, color: AppTheme.textSecondary),
          ),
        ],
      );
    }

    final parts = <String>[];
    parts.add(VpnProtocol.label(entry.profile.protocol));
    if (entry.profile.lineQuality.isNotEmpty) {
      parts.add(entry.profile.lineQuality);
    }
    parts.add(entry.pingMs == null ? 'n/a' : '${entry.pingMs} ms');

    return Text(
      parts.join('  •  '),
      maxLines: 1,
      overflow: TextOverflow.ellipsis,
      style: TextStyle(fontSize: 13, color: AppTheme.textSecondary),
    );
  }
}

class _ServerEntry {
  final PublicProfile profile;
  int? pingMs;
  bool measuring = true;

  _ServerEntry(this.profile);
}
