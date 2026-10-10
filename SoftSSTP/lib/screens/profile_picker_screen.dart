import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/vpn_profile.dart';
import '../providers/vpn_provider.dart';
import '../theme/app_theme.dart';
import 'general_settings_screen.dart';
import 'profile_settings_screen.dart';
import 'public_profiles_screen.dart';
import '../widgets/public_profile_badge.dart';

/// Lets the user pick which profile is active. Opened by tapping the server
/// card on the home screen.
class ProfilePickerScreen extends StatelessWidget {
  const ProfilePickerScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Theme.of(context).scaffoldBackgroundColor,
      appBar: AppBar(
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () => Navigator.pop(context),
        ),
        title: const Text('Select profile'),
        actions: [
          IconButton(
            icon: const Icon(Icons.tune),
            tooltip: 'Manage profiles',
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
        builder: (context, provider, child) {
          final profiles = provider.profiles;
          return ReorderableListView(
            padding: const EdgeInsets.all(20),
            // A long press anywhere on a tile starts the drag on every platform,
            // instead of the desktop-only drag handle.
            buildDefaultDragHandles: false,
            onReorderItem: (oldIndex, newIndex) =>
                provider.reorderProfiles(oldIndex, newIndex),
            footer: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  onPressed: () async {
                    await Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (context) => const ProfileSettingsScreen(),
                      ),
                    );
                  },
                  icon: const Icon(Icons.add),
                  label: const Text('Add profile'),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: AppTheme.primaryColor,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    side: BorderSide(color: AppTheme.primaryColor),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                OutlinedButton.icon(
                  onPressed: () async {
                    await Navigator.push(
                      context,
                      MaterialPageRoute(
                        builder: (context) => const PublicProfilesScreen(),
                      ),
                    );
                  },
                  icon: const Icon(Icons.public),
                  label: const Text('Add public profile'),
                  style: OutlinedButton.styleFrom(
                    foregroundColor: AppTheme.primaryColor,
                    padding: const EdgeInsets.symmetric(vertical: 14),
                    side: BorderSide(color: AppTheme.primaryColor),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
              ],
            ),
            children: [
              for (var i = 0; i < profiles.length; i++)
                ReorderableDelayedDragStartListener(
                  key: ValueKey(profiles[i].id),
                  index: i,
                  child: _ProfileTile(
                    profile: profiles[i],
                    active: profiles[i].id == provider.activeProfileId,
                    onTap: () async {
                      await provider.selectProfile(profiles[i].id);
                      if (context.mounted) Navigator.pop(context);
                    },
                    onEdit: () {
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (context) => ProfileSettingsScreen(
                            profileId: profiles[i].id,
                          ),
                        ),
                      );
                    },
                  ),
                ),
            ],
          );
        },
      ),
    );
  }
}

class _ProfileTile extends StatelessWidget {
  final VpnProfile profile;
  final bool active;
  final VoidCallback onTap;
  final VoidCallback onEdit;

  const _ProfileTile({
    required this.profile,
    required this.active,
    required this.onTap,
    required this.onEdit,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      decoration: BoxDecoration(
        color: AppTheme.cardColor,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: active ? profile.color : Colors.transparent,
          width: 2,
        ),
      ),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 15.2),
          child: Row(
            children: [
              Container(
                width: 44,
                height: 44,
                decoration: BoxDecoration(
                  color: profile.color.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(
                  Icons.vpn_lock_outlined,
                  color: profile.color,
                  size: 24,
                ),
              ),
              const SizedBox(width: 16),
              Expanded(
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
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(
                      profile.host.isEmpty ? 'Not configured' : profile.host,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: 13,
                        color: AppTheme.textSecondary,
                      ),
                    ),
                  ],
                ),
              ),
              IconButton(
                icon: Icon(
                  Icons.edit_outlined,
                  color: AppTheme.textSecondary,
                  size: 20,
                ),
                tooltip: 'Edit',
                onPressed: onEdit,
              ),
              if (active)
                Icon(Icons.check_circle, color: profile.color)
              else
                Icon(
                  Icons.radio_button_unchecked,
                  color: AppTheme.textSecondary,
                ),
            ],
          ),
        ),
      ),
    );
  }
}
