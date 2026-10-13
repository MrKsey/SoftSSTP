import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/theme_controller.dart';
import '../providers/vpn_provider.dart';
import '../services/ip_utils.dart';
import '../theme/app_theme.dart';
import '../widgets/update_prompt.dart';
import 'apps_screen.dart';
import 'general_settings_screen.dart';
import 'profile_picker_screen.dart';
import 'profile_settings_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen>
    with SingleTickerProviderStateMixin, WidgetsBindingObserver {
  late AnimationController _animationController;
  late Animation<double> _pulseAnimation;

  @override
  void initState() {
    super.initState();
    _animationController = AnimationController(
      duration: const Duration(milliseconds: 1500),
      vsync: this,
    );
    _pulseAnimation = Tween<double>(begin: 1.0, end: 1.1).animate(
      CurvedAnimation(parent: _animationController, curve: Curves.easeInOut),
    );
    _animationController.repeat(reverse: true);

    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) => _checkForUpdate());
  }

  /// Startup update check. Announces a newer release once per version.
  Future<void> _checkForUpdate() async {
    final provider = context.read<VpnProvider>();
    final update = await provider.checkForUpdateOnStart();
    if (update == null || !mounted) return;
    await provider.markUpdateNotified(update);
    if (!mounted) return;
    await showUpdatePrompt(context, update);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _animationController.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // The tunnel keeps running while the app is in the background, so the
    // in-memory status may be stale. Re-sync with the native side on resume.
    if (state == AppLifecycleState.resumed) {
      context.read<VpnProvider>().syncStatus();
    } else if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.inactive) {
      // Persist the traffic totals before the OS may kill the process, so the
      // counter survives a screen-off / resume cycle.
      context.read<VpnProvider>().saveTrafficTotals();
    }
  }

  @override
  Widget build(BuildContext context) {
    final themeController = context.watch<ThemeController>();
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      appBar: AppBar(
        leading: IconButton(
          tooltip: 'Theme: ${_themeModeLabel(themeController.mode)}',
          icon: Icon(_themeModeIcon(themeController.mode)),
          onPressed: themeController.cycle,
        ),
        title: const Text('SoftSSTP'),
        actions: [
          IconButton(
            icon: const Icon(Icons.settings_outlined),
            onPressed: () {
              Navigator.push(
                context,
                MaterialPageRoute(
                  builder: (context) => const GeneralSettingsScreen(),
                ),
              );
            },
          ),
        ],
      ),
      body: Consumer<VpnProvider>(
        builder: (context, vpnProvider, child) {
          return SingleChildScrollView(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _buildConnectionCard(vpnProvider),
                const SizedBox(height: 16),
                _buildStatsCard(vpnProvider),
                const SizedBox(height: 16),
                _buildIpCard(vpnProvider),
                const SizedBox(height: 16),
                _buildServerCard(vpnProvider),
              ],
            ),
          );
        },
      ),
    );
  }

  IconData _themeModeIcon(AppThemeMode mode) {
    switch (mode) {
      case AppThemeMode.auto:
        return Icons.brightness_auto;
      case AppThemeMode.light:
        return Icons.light_mode_outlined;
      case AppThemeMode.dark:
        return Icons.dark_mode_outlined;
    }
  }

  String _themeModeLabel(AppThemeMode mode) {
    switch (mode) {
      case AppThemeMode.auto:
        return 'Auto';
      case AppThemeMode.light:
        return 'Day';
      case AppThemeMode.dark:
        return 'Night';
    }
  }

  String _statusLabel(VpnProvider vpnProvider) {
    switch (vpnProvider.status) {
      case VpnStatus.connected:
        return 'Internet traffic protected';
      case VpnStatus.reconnecting:
        return 'Reconnecting…';
      case VpnStatus.connecting:
        return 'Connecting…';
      case VpnStatus.disconnected:
        return vpnProvider.errorMessage.isNotEmpty
            ? 'Connection failed'
            : 'Tap to connect';
    }
  }

  Color _statusColor(VpnProvider vpnProvider) {
    switch (vpnProvider.status) {
      case VpnStatus.connected:
        return AppTheme.textSecondary;
      case VpnStatus.reconnecting:
      case VpnStatus.connecting:
        return Colors.orange;
      case VpnStatus.disconnected:
        return vpnProvider.errorMessage.isNotEmpty
            ? Colors.redAccent
            : AppTheme.textSecondary;
    }
  }

  /// Extra detail shown under the status line: the server while connecting, the
  /// reason while reconnecting, and a full error box when a connection failed.
  List<Widget> _buildStatusDetail(VpnProvider vpnProvider) {
    switch (vpnProvider.status) {
      case VpnStatus.connected:
        return [
          const SizedBox(height: 6),
          Text(
            vpnProvider.isSoftether
                ? '${vpnProvider.host}:${vpnProvider.port} • '
                      'SoftEther • hub ${vpnProvider.hubName}'
                : '${vpnProvider.host}:${vpnProvider.port} • '
                      '${vpnProvider.effectiveAuthProtocol} • '
                      '${vpnProvider.sslVersionLabel}',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 11, color: AppTheme.textSecondary),
          ),
        ];
      case VpnStatus.connecting:
        return [
          const SizedBox(height: 8),
          _buildProgressRow(
            vpnProvider.statusMessage.isNotEmpty
                ? vpnProvider.statusMessage
                : 'Establishing secure tunnel to '
                      '${vpnProvider.host}:${vpnProvider.port}…',
          ),
        ];
      case VpnStatus.reconnecting:
        return [
          const SizedBox(height: 8),
          _buildProgressRow(
            vpnProvider.statusMessage.isEmpty
                ? 'Waiting to retry…'
                : vpnProvider.statusMessage,
          ),
        ];
      case VpnStatus.disconnected:
        if (vpnProvider.errorMessage.isEmpty) return const [];
        return [const SizedBox(height: 10), _buildErrorBox(vpnProvider)];
    }
  }

  Widget _buildProgressRow(String text) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        const SizedBox(
          width: 12,
          height: 12,
          child: CircularProgressIndicator(
            strokeWidth: 2,
            color: Colors.orange,
          ),
        ),
        const SizedBox(width: 8),
        Flexible(
          child: Text(
            text,
            textAlign: TextAlign.center,
            style: const TextStyle(
              fontSize: 11,
              color: Colors.orange,
              height: 1.25,
            ),
          ),
        ),
      ],
    );
  }

  Widget _buildErrorBox(VpnProvider vpnProvider) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
      decoration: BoxDecoration(
        color: Colors.redAccent.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: Colors.redAccent.withValues(alpha: 0.4)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.error_outline, color: Colors.redAccent, size: 18),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  vpnProvider.errorMessage,
                  style: const TextStyle(
                    fontSize: 11.5,
                    color: Colors.redAccent,
                    height: 1.25,
                  ),
                ),
                if (vpnProvider.errorCode.isNotEmpty) ...[
                  const SizedBox(height: 4),
                  Text(
                    'Code: ${vpnProvider.errorCode}',
                    style: TextStyle(
                      fontSize: 10,
                      fontFamily: 'monospace',
                      color: Colors.redAccent.withValues(alpha: 0.85),
                    ),
                  ),
                ],
                const SizedBox(height: 4),
                Text(
                  'Tap the button to try again.',
                  style: TextStyle(fontSize: 10, color: AppTheme.textSecondary),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildConnectionCard(VpnProvider vpnProvider) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 17, vertical: 14.535),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Column(
        children: [
          const SizedBox(height: 9),
          _buildConnectionButton(vpnProvider),
          const SizedBox(height: 12.6),
          Text(
            vpnProvider.status == VpnStatus.connected
                ? vpnProvider.formatTraffic(
                    vpnProvider.totalDownload + vpnProvider.totalUpload,
                  )
                : '0 B',
            style: TextStyle(
              fontSize: 23.4,
              fontWeight: FontWeight.bold,
              color: AppTheme.textPrimary,
            ),
          ),
          const SizedBox(height: 4.5),
          Text(
            _statusLabel(vpnProvider),
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 9.9, color: _statusColor(vpnProvider)),
          ),
          ..._buildStatusDetail(vpnProvider),
          const SizedBox(height: 9),
        ],
      ),
    );
  }

  Widget _buildConnectionButton(VpnProvider vpnProvider) {
    Color buttonColor;
    IconData buttonIcon;

    switch (vpnProvider.status) {
      case VpnStatus.connected:
        buttonColor = AppTheme.primaryColor;
        buttonIcon = Icons.shield_outlined;
        break;
      case VpnStatus.connecting:
        buttonColor = Colors.orange;
        buttonIcon = Icons.sync;
        break;
      case VpnStatus.reconnecting:
        buttonColor = Colors.orange;
        buttonIcon = Icons.sync_problem;
        break;
      case VpnStatus.disconnected:
        buttonColor = AppTheme.textSecondary;
        buttonIcon = Icons.power_settings_new;
        break;
    }

    Widget buttonContent = Container(
      width: 73.8,
      height: 73.8,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: buttonColor.withValues(alpha: 0.15),
        border: Border.all(color: buttonColor, width: 2.7),
      ),
      child: Icon(buttonIcon, size: 30.6, color: buttonColor),
    );

    if (vpnProvider.status == VpnStatus.connecting ||
        vpnProvider.status == VpnStatus.reconnecting) {
      return AnimatedBuilder(
        animation: _pulseAnimation,
        builder: (context, child) {
          return Transform.scale(
            scale: _pulseAnimation.value,
            child: buttonContent,
          );
        },
      );
    }

    return GestureDetector(
      onTap: () {
        if (vpnProvider.isConfigured) {
          vpnProvider.toggleConnection();
        } else {
          Navigator.push(
            context,
            MaterialPageRoute(
              builder: (context) =>
                  ProfileSettingsScreen(profileId: vpnProvider.activeProfileId),
            ),
          );
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(content: Text('Please configure server settings first')),
          );
        }
      },
      child: buttonContent,
    );
  }

  Widget _buildServerCard(VpnProvider vpnProvider) {
    final appBased = vpnProvider.appBasedEnabled;
    final profileColor = vpnProvider.activeProfileColor;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 15.2),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          Row(
            children: [
              Container(
                width: 40,
                height: 40,
                decoration: BoxDecoration(
                  color: profileColor.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(8),
                ),
                child: Icon(
                  Icons.vpn_lock_outlined,
                  color: profileColor,
                  size: 24,
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: InkWell(
                  onTap: () {
                    Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (context) => const ProfilePickerScreen(),
                      ),
                    );
                  },
                  borderRadius: BorderRadius.circular(8),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 4),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          vpnProvider.activeProfile.displayName,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                            color: AppTheme.textPrimary,
                          ),
                        ),
                        const SizedBox(height: 4),
                        Text(
                          vpnProvider.statusMessage,
                          style: TextStyle(
                            fontSize: 14,
                            color: vpnProvider.status == VpnStatus.reconnecting
                                ? Colors.orange
                                : AppTheme.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              ),
              Icon(Icons.unfold_more, color: AppTheme.textSecondary),
              Switch(
                value:
                    vpnProvider.status == VpnStatus.connected ||
                    vpnProvider.status == VpnStatus.connecting ||
                    vpnProvider.status == VpnStatus.reconnecting,
                onChanged: vpnProvider.isConfigured
                    ? (value) => vpnProvider.toggleConnection()
                    : null,
              ),
            ],
          ),
          if (appBased) ...[
            Divider(color: AppTheme.dividerColor, height: 20),
            InkWell(
              onTap: () => _openAppPicker(vpnProvider),
              borderRadius: BorderRadius.circular(8),
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 4),
                child: Row(
                  children: [
                    const Icon(
                      Icons.call_split,
                      color: Colors.orange,
                      size: 18,
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(
                        'Split tunneling: ${vpnProvider.allowedApps.length} '
                        'selected app(s) only',
                        style: const TextStyle(
                          fontSize: 12,
                          color: Colors.orange,
                        ),
                      ),
                    ),
                    const Icon(
                      Icons.chevron_right,
                      color: Colors.orange,
                      size: 18,
                    ),
                  ],
                ),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _openAppPicker(VpnProvider vpnProvider) async {
    final result = await Navigator.push<List<String>>(
      context,
      MaterialPageRoute(
        builder: (context) =>
            AppsScreen(initiallySelected: vpnProvider.allowedApps),
      ),
    );
    if (result != null) {
      await vpnProvider.saveAppRouting(
        enabled: vpnProvider.appBasedEnabled,
        packages: result,
      );
    }
  }

  Widget _buildIpCard(VpnProvider vpnProvider) {
    // IPv4 is preferred; IPv6 is only shown when no IPv4 address is available.
    final before = preferIpv4(vpnProvider.externalIpBefore);
    final after = preferIpv4(vpnProvider.externalIpAfter);
    final internal = preferIpv4(vpnProvider.internalIp);
    final connected = vpnProvider.status == VpnStatus.connected;

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Public IP',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: AppTheme.textSecondary,
            ),
          ),
          const SizedBox(height: 16),
          _buildIpRow(
            icon: Icons.public,
            label: 'Before connection',
            value: before.isEmpty ? '—' : before,
            location: vpnProvider.beforeLocation,
            color: AppTheme.textSecondary,
          ),
          const SizedBox(height: 12),
          _buildIpRow(
            icon: Icons.vpn_lock_outlined,
            label: 'Via VPN',
            value: after.isEmpty ? (connected ? 'Detecting…' : '—') : after,
            location: vpnProvider.afterLocation,
            color: AppTheme.primaryColor,
          ),
          const SizedBox(height: 12),
          _buildIpRow(
            icon: Icons.settings_ethernet,
            label: 'Server-assigned VPN address',
            value: internal.isEmpty
                ? (connected ? 'Detecting…' : '—')
                : internal,
            location: '',
            color: Colors.green,
          ),
        ],
      ),
    );
  }

  Widget _buildIpRow({
    required IconData icon,
    required String label,
    required String value,
    required String location,
    required Color color,
  }) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 2),
          child: Icon(icon, color: color, size: 22),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                label,
                style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
              ),
              const SizedBox(height: 2),
              Text(
                value,
                style: TextStyle(
                  fontSize: 16,
                  fontWeight: FontWeight.w600,
                  color: AppTheme.textPrimary,
                ),
              ),
              if (location.isNotEmpty) ...[
                const SizedBox(height: 2),
                Row(
                  children: [
                    Icon(
                      Icons.place_outlined,
                      size: 13,
                      color: AppTheme.textSecondary,
                    ),
                    const SizedBox(width: 4),
                    Text(
                      location,
                      style: TextStyle(
                        fontSize: 12,
                        color: AppTheme.textSecondary,
                      ),
                    ),
                  ],
                ),
              ],
            ],
          ),
        ),
      ],
    );
  }

  Widget _buildStatsCard(VpnProvider vpnProvider) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            vpnProvider.status == VpnStatus.connected
                ? 'Connection Stats '
                      '(${vpnProvider.formatDuration(vpnProvider.connectionDuration)})'
                : 'Connection Stats',
            style: TextStyle(
              fontSize: 16,
              fontWeight: FontWeight.w600,
              color: AppTheme.textSecondary,
            ),
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: _buildStatItem(
                  Icons.arrow_downward,
                  'Download',
                  vpnProvider.formatSpeed(vpnProvider.downloadSpeed),
                  Colors.green,
                ),
              ),
              Container(
                width: 1,
                height: 50,
                color: AppTheme.textSecondary.withValues(alpha: 0.2),
              ),
              Expanded(
                child: _buildStatItem(
                  Icons.arrow_upward,
                  'Upload',
                  vpnProvider.formatSpeed(vpnProvider.uploadSpeed),
                  Colors.blue,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _buildStatItem(
    IconData icon,
    String label,
    String value,
    Color color,
  ) {
    return Column(
      children: [
        Icon(icon, color: color, size: 24),
        const SizedBox(height: 8),
        Text(
          label,
          style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
        ),
        const SizedBox(height: 4),
        Text(
          value,
          style: TextStyle(
            fontSize: 16,
            fontWeight: FontWeight.w600,
            color: AppTheme.textPrimary,
          ),
        ),
      ],
    );
  }
}
