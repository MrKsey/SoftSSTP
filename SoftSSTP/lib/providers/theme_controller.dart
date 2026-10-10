import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../theme/app_theme.dart';

/// Which skin the user picked. [auto] follows the system setting.
enum AppThemeMode { auto, light, dark }

/// Owns the day / night / auto skin selection.
///
/// The resolved brightness is pushed into [AppTheme] before the app rebuilds,
/// so widgets that read the static `AppTheme` colours always see the active
/// skin.
class ThemeController extends ChangeNotifier with WidgetsBindingObserver {
  static const _prefKey = 'app_theme_mode';

  AppThemeMode _mode = AppThemeMode.auto;
  Brightness _platformBrightness = Brightness.dark;

  ThemeController() {
    WidgetsBinding.instance.addObserver(this);
    _platformBrightness =
        WidgetsBinding.instance.platformDispatcher.platformBrightness;
    AppTheme.apply(resolvedBrightness);
    _load();
  }

  AppThemeMode get mode => _mode;

  /// The brightness actually in use after resolving [mode] against the system.
  Brightness get resolvedBrightness {
    switch (_mode) {
      case AppThemeMode.auto:
        return _platformBrightness;
      case AppThemeMode.light:
        return Brightness.light;
      case AppThemeMode.dark:
        return Brightness.dark;
    }
  }

  /// Cycles auto -> light -> dark -> auto.
  Future<void> cycle() async {
    _mode = AppThemeMode.values[(_mode.index + 1) % AppThemeMode.values.length];
    AppTheme.apply(resolvedBrightness);
    notifyListeners();

    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefKey, _mode.name);
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final stored = prefs.getString(_prefKey);
    if (stored == null) return;

    _mode = AppThemeMode.values.firstWhere(
      (m) => m.name == stored,
      orElse: () => AppThemeMode.auto,
    );
    AppTheme.apply(resolvedBrightness);
    notifyListeners();
  }

  @override
  void didChangePlatformBrightness() {
    _platformBrightness =
        WidgetsBinding.instance.platformDispatcher.platformBrightness;
    if (_mode == AppThemeMode.auto) {
      AppTheme.apply(resolvedBrightness);
      notifyListeners();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }
}
