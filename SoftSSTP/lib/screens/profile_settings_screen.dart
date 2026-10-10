import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/app_list_preset.dart';
import '../models/vpn_profile.dart';
import '../providers/vpn_provider.dart';
import '../theme/app_theme.dart';
import 'apps_screen.dart';

class ProfileSettingsScreen extends StatefulWidget {
  /// The profile being edited, or null to create a brand new one. A new profile
  /// is only added to the list once the user presses Save.
  final String? profileId;

  const ProfileSettingsScreen({super.key, this.profileId});

  @override
  State<ProfileSettingsScreen> createState() => _ProfileSettingsScreenState();
}

class _ProfileSettingsScreenState extends State<ProfileSettingsScreen> {
  final _formKey = GlobalKey<FormState>();
  final _nameController = TextEditingController();
  final _hostController = TextEditingController();
  final _portController = TextEditingController();
  final _usernameController = TextEditingController();
  final _passwordController = TextEditingController();
  final _dnsController = TextEditingController();
  final _proxyHostController = TextEditingController();
  final _proxyPortController = TextEditingController();
  final _sniController = TextEditingController();
  final _mtuController = TextEditingController();
  final _mruController = TextEditingController();
  final _authTimeoutController = TextEditingController();
  final _staticIpController = TextEditingController();
  final _reconnectionCountController = TextEditingController();
  final _reconnectionIntervalController = TextEditingController();

  
  int _selectedColorIndex = 0;

  bool _obscurePassword = true;
  bool _dnsEnabled = false;
  bool _requestDns = true;
  bool _proxyEnabled = false;
  bool _verifyHostName = false;
  bool _verifySSLCert = false;
  String _sslVersion = 'AUTO';
  bool _useCustomSni = false;

  // PPP
  String _authProtocol = 'AUTO';
  bool _ipv4Enabled = true;
  bool _ipv6Enabled = false;
  bool _requestStaticIp = false;

  // Routing
  bool _addDefaultRoute = true;
  bool _routePrivateAddresses = false;

  // App-based routing
  bool _appBasedEnabled = false;
  List<String> _selectedApps = [];

  // Reconnection
  bool _reconnectionEnabled = false;

  /// Draft used while creating a new profile (not yet in the provider list).
  late final VpnProfile _draft;

  bool get _isNew => widget.profileId == null;

  @override
  void initState() {
    super.initState();
    _draft = VpnProfile(id: context.read<VpnProvider>().newProfileId());
    _loadSettings();
  }

  VpnProfile get _profile {
    if (_isNew) return _draft;
    final provider = context.read<VpnProvider>();
    return provider.profiles.firstWhere(
      (p) => p.id == widget.profileId,
      orElse: () => provider.activeProfile,
    );
  }

  void _loadSettings() {
    final profile = _profile;
    _nameController.text = profile.name;
    _selectedColorIndex = profile.colorIndex;
    _hostController.text = profile.host;
    _portController.text = profile.port.toString();
    _usernameController.text = profile.username;
    _passwordController.text = profile.password;
    _dnsController.text = profile.dns;
    _dnsEnabled = profile.dnsEnabled;
    _requestDns = profile.requestDns;
    _proxyHostController.text = profile.proxyHost;
    _proxyPortController.text = profile.proxyPort.toString();
    _proxyEnabled = profile.proxyEnabled;
    _verifyHostName = profile.verifyHostName;
    _verifySSLCert = profile.verifySSLCert;
    _sslVersion = profile.sslVersion;
    _useCustomSni = profile.useCustomSni;
    _sniController.text = profile.customSni;

    _mtuController.text = profile.pppMtu.toString();
    _mruController.text = profile.pppMru.toString();
    _authProtocol = profile.authProtocol;
    _authTimeoutController.text = profile.authTimeout.toString();
    _ipv4Enabled = profile.ipv4Enabled;
    _ipv6Enabled = profile.ipv6Enabled;
    _requestStaticIp = profile.requestStaticIp;
    _staticIpController.text = profile.staticIp;
    _addDefaultRoute = profile.addDefaultRoute;
    _routePrivateAddresses = profile.routePrivateAddresses;
    _appBasedEnabled = profile.appBasedEnabled;
    _selectedApps = List<String>.from(profile.allowedApps);
    _reconnectionEnabled = profile.reconnectionEnabled;
    _reconnectionCountController.text = profile.reconnectionCount.toString();
    _reconnectionIntervalController.text =
        profile.reconnectionInterval.toString();
  }

  @override
  void dispose() {
    _nameController.dispose();
    _hostController.dispose();
    _portController.dispose();
    _usernameController.dispose();
    _passwordController.dispose();
    _dnsController.dispose();
    _proxyHostController.dispose();
    _proxyPortController.dispose();
    _sniController.dispose();
    _mtuController.dispose();
    _mruController.dispose();
    _authTimeoutController.dispose();
    _staticIpController.dispose();
    _reconnectionCountController.dispose();
    _reconnectionIntervalController.dispose();
    super.dispose();
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
        title: Text(_isNew ? 'New profile' : 'Edit profile'),
        actions: [
          TextButton(
            onPressed: _saveSettings,
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
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(20),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _buildSectionTitle('Profile'),
              const SizedBox(height: 16),
              _buildProfileSection(),
              const SizedBox(height: 32),
              _buildSectionTitle('Server Configuration'),
              const SizedBox(height: 16),
              _buildServerSection(),
              const SizedBox(height: 32),
              _buildSectionTitle('DNS Settings'),
              const SizedBox(height: 16),
              _buildDnsSection(),
              const SizedBox(height: 32),
              _buildSectionTitle('Proxy Settings'),
              const SizedBox(height: 16),
              _buildProxySection(),
              const SizedBox(height: 32),
              _buildSectionTitle('PPP'),
              const SizedBox(height: 16),
              _buildPppSection(),
              const SizedBox(height: 32),
              _buildSectionTitle('Routing'),
              const SizedBox(height: 16),
              _buildRoutingSection(),
              const SizedBox(height: 32),
              _buildSectionTitle('App Routing'),
              const SizedBox(height: 16),
              _buildAppRoutingSection(),
              const SizedBox(height: 32),
              _buildSectionTitle('Reconnection'),
              const SizedBox(height: 16),
              _buildReconnectionSection(),
              const SizedBox(height: 32),
              _buildSectionTitle('Advanced'),
              const SizedBox(height: 16),
              _buildAdvancedSection(),
              const SizedBox(height: 32),
              _buildSectionTitle('Certificate'),
              const SizedBox(height: 16),
              _buildCertificateSection(),
              const SizedBox(height: 40),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildProfileSection() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          TextFormField(
            controller: _nameController,
            style: TextStyle(color: AppTheme.textPrimary),
            decoration: InputDecoration(
              labelText: 'Profile name',
              hintText: 'Defaults to the server host',
              prefixIcon: Icon(Icons.badge_outlined, color: AppTheme.textSecondary),
            ),
          ),
          const SizedBox(height: 20),
          Text(
            'Icon colour',
            style: TextStyle(
              fontSize: 14,
              color: AppTheme.textSecondary,
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: List.generate(profileColors.length, (index) {
              final selected = index == _selectedColorIndex;
              return GestureDetector(
                onTap: () => setState(() => _selectedColorIndex = index),
                child: Container(
                  width: 36,
                  height: 36,
                  decoration: BoxDecoration(
                    color: profileColors[index],
                    shape: BoxShape.circle,
                    border: Border.all(
                      color: selected ? AppTheme.textPrimary : Colors.transparent,
                      width: 3,
                    ),
                  ),
                  child: selected
                      ? const Icon(Icons.check, size: 18, color: Colors.white)
                      : null,
                ),
              );
            }),
          ),
        ],
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

  Widget _buildServerSection() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          TextFormField(
            controller: _hostController,
            style: TextStyle(color: AppTheme.textPrimary),
            decoration: InputDecoration(
              labelText: 'Host',
              hintText: 'vpn.example.com',
              prefixIcon: Icon(Icons.dns_outlined, color: AppTheme.textSecondary),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'Please enter host';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),
          TextFormField(
            controller: _portController,
            style: TextStyle(color: AppTheme.textPrimary),
            keyboardType: TextInputType.number,
            decoration: InputDecoration(
              labelText: 'Port',
              hintText: '443',
              prefixIcon: Icon(Icons.numbers_outlined, color: AppTheme.textSecondary),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'Please enter port';
              }
              if (int.tryParse(value) == null) {
                return 'Please enter valid port';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),
          TextFormField(
            controller: _usernameController,
            style: TextStyle(color: AppTheme.textPrimary),
            decoration: InputDecoration(
              labelText: 'Username',
              hintText: 'Enter username',
              prefixIcon: Icon(Icons.person_outline, color: AppTheme.textSecondary),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'Please enter username';
              }
              return null;
            },
          ),
          const SizedBox(height: 16),
          TextFormField(
            controller: _passwordController,
            style: TextStyle(color: AppTheme.textPrimary),
            obscureText: _obscurePassword,
            decoration: InputDecoration(
              labelText: 'Password',
              hintText: 'Enter password',
              prefixIcon: Icon(Icons.lock_outline, color: AppTheme.textSecondary),
              suffixIcon: IconButton(
                icon: Icon(
                  _obscurePassword ? Icons.visibility_outlined : Icons.visibility_off_outlined,
                  color: AppTheme.textSecondary,
                ),
                onPressed: () {
                  setState(() {
                    _obscurePassword = !_obscurePassword;
                  });
                },
              ),
            ),
            validator: (value) {
              if (value == null || value.isEmpty) {
                return 'Please enter password';
              }
              return null;
            },
          ),
        ],
      ),
    );
  }

  Widget _buildDnsSection() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Row(
                children: [
                  Icon(Icons.dns, color: AppTheme.primaryColor),
                  SizedBox(width: 12),
                  Text(
                    'Custom DNS',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w500,
                      color: AppTheme.textPrimary,
                    ),
                  ),
                ],
              ),
              Switch(
                value: _dnsEnabled,
                onChanged: (value) {
                  setState(() {
                    _dnsEnabled = value;
                  });
                },
              ),
            ],
          ),
          if (_dnsEnabled) ...[
            const SizedBox(height: 16),
            TextFormField(
              controller: _dnsController,
              style: TextStyle(color: AppTheme.textPrimary),
              decoration: InputDecoration(
                labelText: 'DNS Server',
                hintText: '8.8.8.8',
                prefixIcon: Icon(Icons.public_outlined, color: AppTheme.textSecondary),
              ),
            ),
          ],
          Divider(color: AppTheme.dividerColor, height: 24),
          _buildSwitchTile(
            icon: Icons.dns_outlined,
            title: 'Request DNS from server',
            subtitle: 'Use DNS servers pushed by the VPN server',
            value: _requestDns,
            onChanged: (value) {
              setState(() {
                _requestDns = value;
              });
            },
          ),
        ],
      ),
    );
  }

  Widget _buildProxySection() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Row(
                children: [
                  Icon(Icons.security, color: AppTheme.primaryColor),
                  SizedBox(width: 12),
                  Text(
                    'Proxy',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w500,
                      color: AppTheme.textPrimary,
                    ),
                  ),
                ],
              ),
              Switch(
                value: _proxyEnabled,
                onChanged: (value) {
                  setState(() {
                    _proxyEnabled = value;
                  });
                },
              ),
            ],
          ),
          if (_proxyEnabled) ...[
            const SizedBox(height: 16),
            TextFormField(
              controller: _proxyHostController,
              style: TextStyle(color: AppTheme.textPrimary),
              decoration: InputDecoration(
                labelText: 'Proxy Host',
                hintText: 'proxy.example.com',
                prefixIcon: Icon(Icons.computer_outlined, color: AppTheme.textSecondary),
              ),
            ),
            const SizedBox(height: 16),
            TextFormField(
              controller: _proxyPortController,
              style: TextStyle(color: AppTheme.textPrimary),
              keyboardType: TextInputType.number,
              decoration: InputDecoration(
                labelText: 'Proxy Port',
                hintText: '8080',
                prefixIcon: Icon(Icons.numbers_outlined, color: AppTheme.textSecondary),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildPppSection() {
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
              Expanded(
                child: TextFormField(
                  controller: _mtuController,
                  style: TextStyle(color: AppTheme.textPrimary),
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    labelText: 'MTU',
                    hintText: '1500',
                  ),
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
                child: TextFormField(
                  controller: _mruController,
                  style: TextStyle(color: AppTheme.textPrimary),
                  keyboardType: TextInputType.number,
                  decoration: const InputDecoration(
                    labelText: 'MRU',
                    hintText: '1500',
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Icon(Icons.lock_outline, color: AppTheme.primaryColor),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  'Authentication',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w500,
                    color: AppTheme.textPrimary,
                  ),
                ),
              ),
              DropdownButton<String>(
                value: _authProtocol,
                dropdownColor: AppTheme.cardColor,
                style: TextStyle(color: AppTheme.textPrimary),
                underline: const SizedBox(),
                items: const [
                  DropdownMenuItem(
                      value: 'AUTO',
                      child: Text('Auto', style: TextStyle(fontSize: 14))),
                  DropdownMenuItem(value: 'MSCHAPv2', child: Text('MSCHAPv2')),
                  DropdownMenuItem(value: 'PAP', child: Text('PAP')),
                ],
                onChanged: (value) {
                  if (value != null) {
                    setState(() {
                      _authProtocol = value;
                    });
                  }
                },
              ),
            ],
          ),
          if (_authProtocol == 'AUTO')
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                'Tries MSCHAPv2 first, then PAP if the server rejects it.',
                style: TextStyle(fontSize: 11, color: AppTheme.textSecondary),
              ),
            ),
          const SizedBox(height: 16),
          TextFormField(
            controller: _authTimeoutController,
            style: TextStyle(color: AppTheme.textPrimary),
            keyboardType: TextInputType.number,
            decoration: InputDecoration(
              labelText: 'Authentication timeout (s)',
              hintText: '5',
              prefixIcon:
                  Icon(Icons.timer_outlined, color: AppTheme.textSecondary),
            ),
          ),
          Divider(color: AppTheme.dividerColor, height: 32),
          _buildSwitchTile(
            icon: Icons.language_outlined,
            title: 'IPv4',
            subtitle: 'Enable IPv4 over PPP',
            value: _ipv4Enabled,
            onChanged: (value) {
              setState(() {
                _ipv4Enabled = value;
              });
            },
          ),
          Divider(color: AppTheme.dividerColor, height: 24),
          _buildSwitchTile(
            icon: Icons.language_outlined,
            title: 'IPv6',
            subtitle: 'Enable IPv6 over PPP',
            value: _ipv6Enabled,
            onChanged: (value) {
              setState(() {
                _ipv6Enabled = value;
              });
            },
          ),
          Divider(color: AppTheme.dividerColor, height: 24),
          _buildSwitchTile(
            icon: Icons.pin_outlined,
            title: 'Static IPv4 address',
            subtitle: 'Request a fixed address instead of DHCP',
            value: _requestStaticIp,
            onChanged: (value) {
              setState(() {
                _requestStaticIp = value;
              });
            },
          ),
          if (_requestStaticIp) ...[
            const SizedBox(height: 16),
            TextFormField(
              controller: _staticIpController,
              style: TextStyle(color: AppTheme.textPrimary),
              keyboardType: TextInputType.number,
              decoration: InputDecoration(
                labelText: 'IPv4 address',
                hintText: '10.8.0.4',
                prefixIcon:
                    Icon(Icons.pin_outlined, color: AppTheme.textSecondary),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildRoutingSection() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          _buildSwitchTile(
            icon: Icons.route_outlined,
            title: 'Default route',
            subtitle: 'Route all traffic through the VPN',
            value: _addDefaultRoute,
            onChanged: (value) {
              setState(() {
                _addDefaultRoute = value;
              });
            },
          ),
          Divider(color: AppTheme.dividerColor, height: 24),
          _buildSwitchTile(
            icon: Icons.lan_outlined,
            title: 'Route private addresses',
            subtitle: 'Route 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16',
            value: _routePrivateAddresses,
            onChanged: (value) {
              setState(() {
                _routePrivateAddresses = value;
              });
            },
          ),
        ],
      ),
    );
  }

  Widget _buildAppRoutingSection() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          _buildSwitchTile(
            icon: Icons.apps_outlined,
            title: 'Route only selected apps',
            subtitle: 'Only the chosen apps use the VPN tunnel',
            value: _appBasedEnabled,
            onChanged: (value) {
              setState(() {
                _appBasedEnabled = value;
              });
            },
          ),
          Divider(color: AppTheme.dividerColor, height: 24),
          InkWell(
            onTap: _openAppPicker,
            borderRadius: BorderRadius.circular(12),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 8),
              child: Row(
                children: [
                  Icon(
                    Icons.checklist_outlined,
                    color: AppTheme.primaryColor,
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Select applications',
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w500,
                            color: AppTheme.textPrimary,
                          ),
                        ),
                        const SizedBox(height: 2),
                        Text(
                          _selectedApps.isEmpty
                              ? 'No apps selected'
                              : '${_selectedApps.length} app(s) selected',
                          style: TextStyle(
                            fontSize: 12,
                            color: AppTheme.textSecondary,
                          ),
                        ),
                      ],
                    ),
                  ),
                  Icon(
                    Icons.chevron_right,
                    color: AppTheme.textSecondary,
                  ),
                ],
              ),
            ),
          ),
          Divider(color: AppTheme.dividerColor, height: 24),
          _buildAppRoutingAction(
            icon: Icons.bookmark_add_outlined,
            title: 'Save selected apps',
            subtitle: _selectedApps.isEmpty
                ? 'Nothing to save yet'
                : 'Store this list to reuse in other profiles',
            enabled: _selectedApps.isNotEmpty,
            onTap: _saveAppPreset,
          ),
          Divider(color: AppTheme.dividerColor, height: 24),
          _buildAppRoutingAction(
            icon: Icons.bookmarks_outlined,
            title: 'Load saved list',
            subtitle: context.watch<VpnProvider>().appPresets.isEmpty
                ? 'No saved lists yet'
                : '${context.watch<VpnProvider>().appPresets.length} saved '
                    'list(s) available',
            enabled: context.watch<VpnProvider>().appPresets.isNotEmpty,
            onTap: _loadAppPreset,
          ),
        ],
      ),
    );
  }

  Widget _buildAppRoutingAction({
    required IconData icon,
    required String title,
    required String subtitle,
    required bool enabled,
    required VoidCallback onTap,
  }) {
    final color = enabled ? AppTheme.primaryColor : AppTheme.textSecondary;
    return InkWell(
      onTap: enabled ? onTap : null,
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          children: [
            Icon(icon, color: color),
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
                      color: enabled ? AppTheme.textPrimary : color,
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
            Icon(Icons.chevron_right, color: color),
          ],
        ),
      ),
    );
  }

  /// Asks for a name and stores the current selection as a reusable preset.
  Future<void> _saveAppPreset() async {
    if (_selectedApps.isEmpty) return;
    final controller = TextEditingController();
    final name = await showDialog<String>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        backgroundColor: AppTheme.cardColor,
        title: Text(
          'Save app list',
          style: TextStyle(color: AppTheme.textPrimary),
        ),
        content: TextField(
          controller: controller,
          autofocus: true,
          style: TextStyle(color: AppTheme.textPrimary),
          decoration: const InputDecoration(
            labelText: 'List name',
            hintText: 'e.g. Browsers',
          ),
          onSubmitted: (value) => Navigator.pop(dialogContext, value),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.pop(dialogContext, controller.text),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    controller.dispose();
    if (name == null || name.trim().isEmpty || !mounted) return;

    await context
        .read<VpnProvider>()
        .saveAppPreset(name, List<String>.from(_selectedApps));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text('Saved "${name.trim()}"'),
      ),
    );
  }

  /// Shows the saved lists and applies the chosen one to the current profile.
  Future<void> _loadAppPreset() async {
    final provider = context.read<VpnProvider>();
    final presets = provider.appPresets;
    if (presets.isEmpty) return;

    final selected = await showDialog<AppListPreset>(
      context: context,
      builder: (dialogContext) => AlertDialog(
        backgroundColor: AppTheme.cardColor,
        title: Text(
          'Load app list',
          style: TextStyle(color: AppTheme.textPrimary),
        ),
        contentPadding: const EdgeInsets.symmetric(vertical: 12),
        content: SizedBox(
          width: double.maxFinite,
          child: ListView.builder(
            shrinkWrap: true,
            itemCount: presets.length,
            itemBuilder: (context, index) {
              final preset = presets[index];
              return ListTile(
                leading: Icon(
                  Icons.bookmarks_outlined,
                  color: AppTheme.primaryColor,
                ),
                title: Text(
                  preset.name,
                  style: TextStyle(color: AppTheme.textPrimary),
                ),
                subtitle: Text(
                  '${preset.packages.length} app(s)',
                  style: TextStyle(
                    fontSize: 12,
                    color: AppTheme.textSecondary,
                  ),
                ),
                trailing: IconButton(
                  icon: Icon(
                    Icons.delete_outline,
                    color: AppTheme.textSecondary,
                  ),
                  tooltip: 'Delete',
                  onPressed: () async {
                    await provider.deleteAppPreset(preset.id);
                    if (dialogContext.mounted) Navigator.pop(dialogContext);
                  },
                ),
                onTap: () => Navigator.pop(dialogContext, preset),
              );
            },
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(dialogContext),
            child: const Text('Cancel'),
          ),
        ],
      ),
    );

    if (selected == null || !mounted) return;
    setState(() {
      _selectedApps = List<String>.from(selected.packages);
    });
  }

  Future<void> _openAppPicker() async {
    final result = await Navigator.push<List<String>>(
      context,
      MaterialPageRoute(
        builder: (context) => AppsScreen(initiallySelected: _selectedApps),
      ),
    );
    if (result != null && mounted) {
      setState(() {
        _selectedApps = result;
      });
    }
  }

  Widget _buildReconnectionSection() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          _buildSwitchTile(
            icon: Icons.refresh_outlined,
            title: 'Auto reconnection',
            subtitle: 'Reconnect automatically when the tunnel drops',
            value: _reconnectionEnabled,
            onChanged: (value) {
              setState(() {
                _reconnectionEnabled = value;
              });
            },
          ),
          if (_reconnectionEnabled) ...[
            const SizedBox(height: 16),
            Row(
              children: [
                Expanded(
                  child: TextFormField(
                    controller: _reconnectionCountController,
                    style: TextStyle(color: AppTheme.textPrimary),
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Retry count',
                      hintText: '3',
                    ),
                  ),
                ),
                const SizedBox(width: 16),
                Expanded(
                  child: TextFormField(
                    controller: _reconnectionIntervalController,
                    style: TextStyle(color: AppTheme.textPrimary),
                    keyboardType: TextInputType.number,
                    decoration: const InputDecoration(
                      labelText: 'Retry interval (s)',
                      hintText: '10',
                    ),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildAdvancedSection() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: Column(
        children: [
          _buildSwitchTile(
            icon: Icons.verified_user_outlined,
            title: 'Verify Hostname',
            subtitle: 'Validate server hostname',
            value: _verifyHostName,
            onChanged: (value) {
              setState(() {
                _verifyHostName = value;
              });
            },
          ),
          Divider(color: AppTheme.dividerColor, height: 24),
          _buildSwitchTile(
            icon: Icons.security_outlined,
            title: 'Verify SSL Certificate',
            subtitle: 'Validate SSL certificate',
            value: _verifySSLCert,
            onChanged: (value) {
              setState(() {
                _verifySSLCert = value;
              });
            },
          ),
          Divider(color: AppTheme.dividerColor, height: 24),
          Row(
            children: [
              Icon(Icons.lock_clock_outlined, color: AppTheme.primaryColor),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  'SSL Version',
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w500,
                    color: AppTheme.textPrimary,
                  ),
                ),
              ),
              DropdownButton<String>(
                value: _sslVersion,
                dropdownColor: AppTheme.cardColor,
                style: TextStyle(color: AppTheme.textPrimary),
                underline: const SizedBox(),
                items: const [
                  DropdownMenuItem(
                      value: 'AUTO',
                      child: Text('Auto', style: TextStyle(fontSize: 14))),
                  DropdownMenuItem(value: 'TLSv1', child: Text('TLS 1.0')),
                  DropdownMenuItem(value: 'TLSv1_1', child: Text('TLS 1.1')),
                  DropdownMenuItem(value: 'TLSv1_2', child: Text('TLS 1.2')),
                  DropdownMenuItem(value: 'TLSv1_3', child: Text('TLS 1.3')),
                ],
                onChanged: (value) {
                  if (value != null) {
                    setState(() {
                      _sslVersion = value;
                    });
                  }
                },
              ),
            ],
          ),
          if (_sslVersion == 'AUTO')
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(
                'Tries TLS 1.3 first, then falls back to 1.2, 1.1 and 1.0.',
                style: TextStyle(fontSize: 11, color: AppTheme.textSecondary),
              ),
            ),
          Divider(color: AppTheme.dividerColor, height: 24),
          _buildSwitchTile(
            icon: Icons.dns_outlined,
            title: 'Custom SNI',
            subtitle: 'Override the TLS server name (SNI)',
            value: _useCustomSni,
            onChanged: (value) {
              setState(() {
                _useCustomSni = value;
              });
            },
          ),
          if (_useCustomSni) ...[
            const SizedBox(height: 16),
            TextFormField(
              controller: _sniController,
              style: TextStyle(color: AppTheme.textPrimary),
              decoration: InputDecoration(
                labelText: 'SNI Hostname',
                hintText: 'example.com',
                prefixIcon: Icon(Icons.dns_outlined, color: AppTheme.textSecondary),
              ),
            ),
          ],
        ],
      ),
    );
  }

  Widget _buildSwitchTile({
    required IconData icon,
    required String title,
    required String subtitle,
    required bool value,
    required ValueChanged<bool> onChanged,
  }) {
    return Row(
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
        Switch(value: value, onChanged: onChanged),
      ],
    );
  }

  Widget _buildCertificateSection() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 19),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
      ),
      child: InkWell(
        onTap: () async {
          final vpnProvider = context.read<VpnProvider>();
          final certPath = await vpnProvider.addCertificate();
          if (certPath != null && mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              SnackBar(
                content: Text('Certificate added: $certPath'),
              ),
            );
          }
        },
        borderRadius: BorderRadius.circular(16),
        child: Row(
          children: [
            Container(
              width: 48,
              height: 48,
              decoration: BoxDecoration(
                color: AppTheme.primaryColor.withValues(alpha: 0.2),
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(
                Icons.add_card_outlined,
                color: AppTheme.primaryColor,
              ),
            ),
            const SizedBox(width: 16),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Add Certificate',
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: AppTheme.textPrimary,
                    ),
                  ),
                  SizedBox(height: 4),
                  Text(
                    'Import custom SSL certificate',
                    style: TextStyle(
                      fontSize: 14,
                      color: AppTheme.textSecondary,
                    ),
                  ),
                ],
              ),
            ),
            Icon(
              Icons.chevron_right,
              color: AppTheme.textSecondary,
            ),
          ],
        ),
      ),
    );
  }

  void _saveSettings() async {
    if (!_formKey.currentState!.validate()) return;

    if (_appBasedEnabled && _selectedApps.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text('Select at least one application for App Routing'),
        ),
      );
      return;
    }

    final vpnProvider = context.read<VpnProvider>();
    final current = _profile;

    final updated = VpnProfile(
      id: current.id,
      name: _nameController.text.trim(),
      colorIndex: _selectedColorIndex,
      isPublic: current.isPublic,
      host: _hostController.text.trim(),
      port: int.tryParse(_portController.text) ?? 443,
      username: _usernameController.text,
      password: _passwordController.text,
      dns: _dnsController.text,
      dnsEnabled: _dnsEnabled,
      requestDns: _requestDns,
      proxyHost: _proxyHostController.text,
      proxyPort: int.tryParse(_proxyPortController.text) ?? 8080,
      proxyEnabled: _proxyEnabled,
      pppMtu: int.tryParse(_mtuController.text) ?? 1500,
      pppMru: int.tryParse(_mruController.text) ?? 1500,
      authProtocol: _authProtocol,
      authTimeout: int.tryParse(_authTimeoutController.text) ?? 5,
      ipv4Enabled: _ipv4Enabled,
      ipv6Enabled: _ipv6Enabled,
      requestStaticIp: _requestStaticIp,
      staticIp: _staticIpController.text.trim(),
      addDefaultRoute: _addDefaultRoute,
      routePrivateAddresses: _routePrivateAddresses,
      appBasedEnabled: _appBasedEnabled,
      allowedApps: _selectedApps,
      reconnectionEnabled: _reconnectionEnabled,
      reconnectionCount: int.tryParse(_reconnectionCountController.text) ?? 3,
      reconnectionInterval:
          int.tryParse(_reconnectionIntervalController.text) ?? 10,
      verifyHostName: _verifyHostName,
      verifySSLCert: _verifySSLCert,
      sslVersion: _sslVersion,
      useCustomSni: _useCustomSni,
      customSni: _sniController.text.trim(),
    );

    if (_isNew) {
      await vpnProvider.addProfile(updated);
    } else {
      await vpnProvider.saveProfile(updated);
    }

    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            _isNew ? 'Profile added successfully' : 'Profile saved successfully',
          ),
        ),
      );
      Navigator.pop(context);
    }
  }
}
