import 'package:flutter/material.dart';

import '../services/update_service.dart';
import '../theme/app_theme.dart';

/// Shows the "new version available" dialog. When the user accepts, the APK is
/// downloaded and the system installer is opened.
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
            'SoftSSTP ${info.version} is ready to install.',
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
          child: const Text('Download'),
        ),
      ],
    ),
  );
  if (accepted != true || !context.mounted) return;
  await startUpdate(context, info);
}

/// Downloads the update and opens the installer. Asks for the "install unknown
/// apps" permission first when it is missing.
Future<void> startUpdate(BuildContext context, UpdateInfo info) async {
  final messenger = ScaffoldMessenger.of(context);

  if (!await UpdateService.canRequestPackageInstalls()) {
    messenger.showSnackBar(
      const SnackBar(
        content: Text(
          'Allow SoftSSTP to install apps, then tap the download again.',
        ),
        duration: Duration(seconds: 4),
      ),
    );
    await UpdateService.openInstallPermissionSettings();
    return;
  }

  messenger.showSnackBar(
    const SnackBar(
      content: Text('Downloading the update…'),
      duration: Duration(seconds: 2),
    ),
  );
  try {
    await UpdateService.downloadAndInstall(info);
  } catch (e) {
    messenger.showSnackBar(SnackBar(content: Text('Update failed: $e')));
  }
}
