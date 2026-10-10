import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Small "Public" tag shown next to profiles that were created from the public
/// VPN Gate list, so they stand out from user-configured ones.
class PublicProfileBadge extends StatelessWidget {
  const PublicProfileBadge({super.key});

  @override
  Widget build(BuildContext context) {
    final color = AppTheme.primaryColor;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(6),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.public, size: 11, color: color),
          const SizedBox(width: 3),
          Text(
            'Public',
            style: TextStyle(
              fontSize: 10,
              fontWeight: FontWeight.w600,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}
