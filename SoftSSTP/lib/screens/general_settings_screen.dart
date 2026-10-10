import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/vpn_profile.dart';
import '../providers/vpn_provider.dart';
import '../services/app_info.dart';
import '../services/link_launcher.dart';
import '../theme/app_theme.dart';
import '../widgets/public_profile_badge.dart';
import 'log_screen.dart';
import 'profile_settings_screen.dart';
import 'public_profiles_screen.dart';

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

class _GeneralSettingsScreenState extends State<GeneralSettingsScreen> {
  AppInfo? _appInfo;
  bool _connectionLogEnabled = true;

  @override
  void initState() {
    super.initState();
    _loadAppInfo();
    _connectionLogEnabled = context.read<VpnProvider>().connectionLogEnabled;
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
