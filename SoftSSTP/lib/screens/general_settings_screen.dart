import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../models/vpn_profile.dart';
import '../providers/vpn_provider.dart';
import '../services/app_info.dart';
import '../services/link_launcher.dart';
import '../services/profile_transfer.dart';
import '../theme/app_theme.dart';
import '../widgets/public_profile_badge.dart';
import 'log_screen.dart';
import 'profile_settings_screen.dart';
import 'public_profiles_screen.dart';

/// A cryptocurrency wallet shown in the Donate section.
class _DonationEntry {
  const _DonationEntry(this.name, this.address, this.iconAsset);

  final String name;
  final String address;
  final String iconAsset;
}

const List<_DonationEntry> _donations = [
  _DonationEntry(
    'Bitcoin',
    'bc1qdf9wryj6tnyyr7ed4dkfv03nn0dxjf27xshfcr',
    'assets/images/bitcoin.png',
  ),
  _DonationEntry(
    'Ethereum',
    '0xE22FAB5337A5506D9e7Ef9D4914De4d963Fbbf88',
    'assets/images/ethereum.png',
  ),
  _DonationEntry(
    'Solana',
    '7XcNM2M9rndFbJdjRtyycxkQXq8dPXEP87toiQX4EZfx',
    'assets/images/solana.png',
  ),
  _DonationEntry(
    'Tron',
    'TCYpTxSmdPN8p4fmRwtPGk3HHBf77FypeA',
    'assets/images/tron.png',
  ),
];

/// Global settings: profile management, Diagnostics and About.
///
/// Everything that belongs to a single server lives in the profile editor
/// ([ProfileSettingsScreen]); this screen only holds the settings that are not
/// tied to a profile.
class GeneralSettingsScreen extends StatefulWidget {
  const GeneralSettingsScreen({super.key});

  @override
  State<GeneralSettingsScreen> createState() => _GeneralSettingsScreenState();
}

class _GeneralSettingsScreenState extends State<GeneralSettingsScreen>
    with WidgetsBindingObserver {
  AppInfo? _appInfo;
  bool _connectionLogEnabled = true;
  bool _ignoringBatteryOptimizations = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadAppInfo();
    _connectionLogEnabled = context.read<VpnProvider>().connectionLogEnabled;
    _loadBatteryOptimizationStatus();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The exemption is confirmed in a system dialog, so the state can only
    // change while the app is in the background.
    if (state == AppLifecycleState.resumed) {
      _loadBatteryOptimizationStatus();
    }
  }

  Future<void> _loadBatteryOptimizationStatus() async {
    final provider = context.read<VpnProvider>();
    final ignoring = await provider.isIgnoringBatteryOptimizations();
    if (mounted) setState(() => _ignoringBatteryOptimizations = ignoring);
  }

  Future<void> _loadAppInfo() async {
    final info = await AppInfo.load();
    if (mounted) {
      setState(() => _appInfo = info);
    }
  }

  Future<void> _openProfile(String profileId) async {
    await Navigator.push(
      context,
      MaterialPageRoute(
        builder: (context) => ProfileSettingsScreen(profileId: profileId),
      ),
    );
    if (mounted) setState(() {});
  }

  Future<void> _addProfile() async {
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => const ProfileSettingsScreen()),
    );
    if (mounted) setState(() {});
  }

  Future<void> _openPublicProfiles() async {
    await Navigator.push(
      context,
      MaterialPageRoute(builder: (context) => const PublicProfilesScreen()),
    );
    if (mounted) setState(() {});
  }

  Future<void> _deleteProfile(VpnProfile profile) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppTheme.cardColor,
        title: const Text('Delete profile'),
        content: Text(
          'Delete "${profile.displayName}"? This cannot be undone.',
          style: TextStyle(color: AppTheme.textSecondary),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: const Text(
              'Delete',
              style: TextStyle(color: Colors.redAccent),
            ),
          ),
        ],
      ),
    );
    if (confirmed == true && mounted) {
      await context.read<VpnProvider>().deleteProfile(profile.id);
    }
  }

  Future<void> _copyAddress(String address) async {
    await Clipboard.setData(ClipboardData(text: address));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Address copied to clipboard'),
        duration: Duration(seconds: 2),
      ),
    );
  }

  /// Warns that an exported profile keeps its password in clear text. Returns
  /// true when the user chose to continue.
  Future<bool> _confirmPasswordWarning(String action) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppTheme.cardColor,
        title: Text('$action profile'),
        content: Text(
          "The file contains this profile's password in clear text. Anyone who "
          'can open the file can read it, so keep it somewhere safe.',
          style: TextStyle(color: AppTheme.textSecondary),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(action),
          ),
        ],
      ),
    );
    return confirmed == true;
  }

  Future<void> _exportProfile(VpnProfile profile) async {
    if (!await _confirmPasswordWarning('Export')) return;
    try {
      final destination = await ProfileTransfer.exportToFile(profile);
      if (!mounted || destination == null) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Exported "${ProfileTransfer.fileNameFor(profile)}"'),
          duration: const Duration(seconds: 2),
        ),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not export the profile: $e')),
      );
    }
  }

  Future<void> _shareProfile(VpnProfile profile) async {
    if (!await _confirmPasswordWarning('Share')) return;
    try {
      await ProfileTransfer.shareProfile(profile);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not share the profile: $e')),
      );
    }
  }

  Future<void> _importProfile() async {
    final provider = context.read<VpnProvider>();
    try {
      final profiles = await ProfileTransfer.pickAndDecode();
      if (!mounted || profiles == null) return;
      final added = await provider.importProfiles(profiles);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            added == 1 ? 'Imported 1 profile' : 'Imported $added profiles',
          ),
          duration: const Duration(seconds: 2),
        ),
      );
      setState(() {});
    } on FormatException catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(e.message)),
      );
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Could not import the profile: $e')),
      );
    }
  }

  Future<void> _handleBatteryOptimizationTap() async {
    final provider = context.read<VpnProvider>();
    if (_ignoringBatteryOptimizations) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('SoftSSTP is already exempt from battery optimization'),
          duration: Duration(seconds: 2),
        ),
      );
      return;
    }

    final requested = await provider.requestIgnoreBatteryOptimizations();
    if (!mounted) return;
    if (!requested) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Could not open the battery optimization settings'),
          duration: Duration(seconds: 3),
        ),
      );
      return;
    }
    // The user confirms in a system dialog; the state is re-read when they
    // return to the app.
    await _loadBatteryOptimizationStatus();
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
        title: const Text('Settings'),
      ),
      body: Consumer<VpnProvider>(
        builder: (context, provider, child) {
          return SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildSectionTitle('Profiles'),
                const SizedBox(height: 16),
                _buildProfilesSection(provider),
                const SizedBox(height: 32),
                _buildSectionTitle('Diagnostics'),
                const SizedBox(height: 16),
                _buildDiagnosticsSection(provider),
                const SizedBox(height: 32),
                _buildSectionTitle('Background'),
                const SizedBox(height: 16),
                _buildBackgroundSection(),
                const SizedBox(height: 32),
                _buildSectionTitle('Donate'),
                const SizedBox(height: 16),
                _buildDonateSection(),
                const SizedBox(height: 32),
                _buildSectionTitle('About'),
                const SizedBox(height: 16),
                _buildAboutSection(),
                const SizedBox(height: 40),
              ],
            ),
          );
        },
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Text(
      title,
      style: TextStyle(
        fontSize: 14,
        fontWeight: FontWeight.w600,
        color: AppTheme.textSecondary,
        letterSpacing: 0.5,
      ),
    );
  }

  Widget _buildProfilesSection(VpnProvider provider) {
    final profiles = provider.profiles;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          for (final profile in profiles) ...[
            _buildProfileRow(provider, profile),
            Divider(color: AppTheme.dividerColor, height: 20),
          ],
          InkWell(
            onTap: _addProfile,
            borderRadius: BorderRadius.circular(12),
            child: Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Row(
                children: [
                  Icon(Icons.add_circle_outline, color: AppTheme.primaryColor),
                  SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'Add profile',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                        color: AppTheme.textPrimary,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          Divider(color: AppTheme.dividerColor, height: 20),
          InkWell(
            onTap: _openPublicProfiles,
            borderRadius: BorderRadius.circular(12),
            child: Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Row(
                children: [
                  Icon(Icons.public, color: AppTheme.primaryColor),
                  SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'Add public profile',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                        color: AppTheme.textPrimary,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          Divider(color: AppTheme.dividerColor, height: 20),
          InkWell(
            onTap: _importProfile,
            borderRadius: BorderRadius.circular(12),
            child: Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Row(
                children: [
                  Icon(
                    Icons.file_download_outlined,
                    color: AppTheme.primaryColor,
                  ),
                  SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'Import profile',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                        color: AppTheme.textPrimary,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildProfileRow(VpnProvider provider, VpnProfile profile) {
    final isActive = profile.id == provider.activeProfileId;
    return Row(
      children: [
        Container(
          width: 40,
          height: 40,
          decoration: BoxDecoration(
            color: profile.color.withValues(alpha: 0.2),
            borderRadius: BorderRadius.circular(10),
          ),
          child: Icon(Icons.vpn_lock_outlined, color: profile.color, size: 22),
        ),
        const SizedBox(width: 14),
        Expanded(
          child: InkWell(
            onTap: () => _openProfile(profile.id),
            borderRadius: BorderRadius.circular(8),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Flexible(
                        child: Text(
                          profile.displayName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                            color: AppTheme.textPrimary,
                          ),
                        ),
                      ),
                      if (profile.isPublic) ...[
                        const SizedBox(width: 8),
                        const PublicProfileBadge(),
                      ],
                      if (isActive) ...[
                        const SizedBox(width: 8),
                        Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 8,
                            vertical: 2,
                          ),
                          decoration: BoxDecoration(
                            color: AppTheme.primaryColor.withValues(alpha: 0.2),
                            borderRadius: BorderRadius.circular(8),
                          ),
                          child: Text(
                            'Active',
                            style: TextStyle(
                              fontSize: 11,
                              color: AppTheme.primaryColor,
                            ),
                          ),
                        ),
                      ],
                    ],
                  ),
                  const SizedBox(height: 2),
                  Text(
                    profile.host.isEmpty ? 'Not configured' : profile.host,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      color: AppTheme.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        PopupMenuButton<String>(
          icon: Icon(Icons.more_vert, color: AppTheme.textSecondary),
          color: AppTheme.cardColor,
          onSelected: (value) {
            switch (value) {
              case 'edit':
                _openProfile(profile.id);
                break;
              case 'export':
                _exportProfile(profile);
                break;
              case 'share':
                _shareProfile(profile);
                break;
              case 'activate':
                provider.selectProfile(profile.id);
                break;
              case 'delete':
                _deleteProfile(profile);
                break;
            }
          },
          itemBuilder: (context) => [
            const PopupMenuItem(value: 'edit', child: Text('Edit')),
            const PopupMenuItem(value: 'export', child: Text('Export')),
            const PopupMenuItem(value: 'share', child: Text('Share')),
            if (!isActive)
              const PopupMenuItem(value: 'activate', child: Text('Set active')),
            const PopupMenuItem(value: 'delete', child: Text('Delete')),
          ],
        ),
      ],
    );
  }

  Widget _buildDiagnosticsSection(VpnProvider provider) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          Row(
            children: [
              Icon(Icons.bug_report_outlined, color: AppTheme.primaryColor),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Connection logging',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                        color: AppTheme.textPrimary,
                      ),
                    ),
                    SizedBox(height: 2),
                    Text(
                      'Save connection details to a file',
                      style: TextStyle(
                        fontSize: 12,
                        color: AppTheme.textSecondary,
                      ),
                    ),
                  ],
                ),
              ),
              Switch(
                value: _connectionLogEnabled,
                onChanged: (value) {
                  setState(() => _connectionLogEnabled = value);
                  provider.saveConnectionLogging(value);
                },
              ),
            ],
          ),
          Divider(color: AppTheme.dividerColor, height: 24),
          InkWell(
            onTap: () {
              Navigator.push(
                context,
                MaterialPageRoute(builder: (context) => const LogScreen()),
              );
            },
            borderRadius: BorderRadius.circular(12),
            child: Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Row(
                children: [
                  Icon(Icons.description_outlined, color: AppTheme.primaryColor),
                  SizedBox(width: 12),
                  Expanded(
                    child: Text(
                      'View connection log',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                        color: AppTheme.textPrimary,
                      ),
                    ),
                  ),
                  Icon(Icons.chevron_right, color: AppTheme.textSecondary),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildAboutSection() {
    final info = _appInfo;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          Row(
            children: [
              Container(
                width: 48,
                height: 48,
                decoration: BoxDecoration(
                  color: AppTheme.primaryColor.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  Icons.shield_outlined,
                  color: AppTheme.primaryColor,
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      info?.appName ?? 'SoftSSTP',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w600,
                        color: AppTheme.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      info == null
                          ? 'Loading…'
                          : 'Version ${info.displayVersion}',
                      style: TextStyle(
                        fontSize: 14,
                        color: AppTheme.textSecondary,
                      ),
                    ),
                  ],
                ),
              ),
            ],
          ),
          Divider(color: AppTheme.dividerColor, height: 32),
          _buildAboutLink(
            icon: Icons.person_outline,
            title: 'Author',
            subtitle: 'github.com/MrKsey/SoftSSTP',
            url: 'https://github.com/MrKsey/SoftSSTP',
          ),
          Divider(color: AppTheme.dividerColor, height: 24),
          _buildAboutLink(
            icon: Icons.fork_right,
            title: 'Original project',
            subtitle: 'github.com/CluvexStudio/ZedPass',
            url: 'https://github.com/CluvexStudio/ZedPass',
          ),
          Divider(color: AppTheme.dividerColor, height: 24),
          _buildAboutLink(
            icon: Icons.android,
            title: 'Open SSTP Client for Android',
            subtitle: 'github.com/kittoku/Open-SSTP-Client',
            url: 'https://github.com/kittoku/Open-SSTP-Client',
          ),
          Divider(color: AppTheme.dividerColor, height: 24),
          _buildAboutLink(
            icon: Icons.public,
            title: 'Public VPN servers',
            subtitle: 'vpngate.net',
            url: 'https://www.vpngate.net/en/',
          ),
        ],
      ),
    );
  }

  Widget _buildBackgroundSection() {
    final exempt = _ignoringBatteryOptimizations;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: InkWell(
        onTap: _handleBatteryOptimizationTap,
        borderRadius: BorderRadius.circular(12),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 4),
          child: Row(
            children: [
              Icon(
                exempt ? Icons.battery_saver : Icons.battery_alert_outlined,
                color: exempt ? AppTheme.primaryColor : AppTheme.textSecondary,
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'Battery optimization',
                      style: TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w500,
                        color: AppTheme.textPrimary,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      exempt
                          ? 'Exempt — the VPN keeps running with the screen off'
                          : 'Not exempt — Android may stop the VPN in the '
                              'background',
                      style: TextStyle(
                        fontSize: 12,
                        color: AppTheme.textSecondary,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(Icons.chevron_right, color: AppTheme.textSecondary),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildDonateSection() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          for (var i = 0; i < _donations.length; i++) ...[
            if (i > 0) Divider(color: AppTheme.dividerColor, height: 1),
            _buildDonateRow(_donations[i]),
          ],
        ],
      ),
    );
  }

  Widget _buildDonateRow(_DonationEntry entry) {
    return InkWell(
      onTap: () => _copyAddress(entry.address),
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 12),
        child: Row(
          children: [
            Image.asset(entry.iconAsset, width: 36, height: 36),
            const SizedBox(width: 14),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    entry.name,
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w500,
                      color: AppTheme.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    entry.address,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(
                      fontSize: 12,
                      fontFamily: 'monospace',
                      color: AppTheme.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Icon(Icons.copy_outlined, size: 18, color: AppTheme.textSecondary),
          ],
        ),
      ),
    );
  }

  Widget _buildAboutLink({
    required IconData icon,
    required String title,
    required String subtitle,
    required String url,
  }) {
    return InkWell(
      onTap: () => LinkLauncher.open(url),
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          children: [
            Icon(icon, color: AppTheme.primaryColor),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w500,
                      color: AppTheme.textPrimary,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    subtitle,
                    style: TextStyle(
                      fontSize: 12,
                      color: AppTheme.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            Icon(
              Icons.open_in_new,
              size: 18,
              color: AppTheme.textSecondary,
            ),
          ],
        ),
      ),
    );
  }
}
