import 'package:flutter/material.dart';

import '../services/link_launcher.dart';
import '../services/update_service.dart';
import '../theme/app_theme.dart';

/// Shows the "new version available" dialog. When the user accepts, the release
/// page is opened in the browser, where the APK can be downloaded. The app does
/// not install updates itself: F-Droid forbids that, and the build has to stay
/// identical to the one published there.
Future<void> showUpdatePrompt(BuildContext context, UpdateInfo info) async {
  final accepted = await showDialog<bool>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      backgroundColor: AppTheme.cardColor,
      title: const Text('Update available'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'SoftSSTP ${info.version} has been released.',
            style: TextStyle(color: AppTheme.textPrimary),
          ),
          if (info.notes.isNotEmpty) ...[
            const SizedBox(height: 12),
            Text(
              info.notes,
              maxLines: 8,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(fontSize: 12, color: AppTheme.textSecondary),
            ),
          ],
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(dialogContext, false),
          child: const Text('Later'),
        ),
        TextButton(
          onPressed: () => Navigator.pop(dialogContext, true),
          child: const Text('Open the release page'),
        ),
      ],
    ),
  );
  if (accepted != true) return;
  await openReleasePage(info);
}

/// Opens the release page in the device browser.
Future<void> openReleasePage(UpdateInfo info) async {
  await LinkLauncher.open(info.releaseUrl);
}
