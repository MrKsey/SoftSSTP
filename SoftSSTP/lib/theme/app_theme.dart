import 'package:flutter/material.dart';

/// The app's colour palette.
///
/// The colours are mutable static fields so the whole app (which references
/// `AppTheme.cardColor` and friends directly) can switch between the day and
/// night skins. [ThemeController] calls [apply] before rebuilding the app, so
/// every widget that re-reads these values picks up the new skin.
class AppTheme {
  static Color primaryColor = const Color(0xFF7C7CFF);
  static Color surfaceColor = const Color(0xFF1C1C1E);
  static Color backgroundColor = const Color(0xFF000000);
  static Color cardColor = const Color(0xFF2C2C2E);
  static Color dividerColor = const Color(0xFF3C3C3E);
  static Color textPrimary = const Color(0xFFFFFFFF);
  static Color textSecondary = const Color(0xFF8E8E93);

  static Brightness _brightness = Brightness.dark;
  static Brightness get brightness => _brightness;

  /// Switches the palette to the given skin.
  static void apply(Brightness brightness) {
    _brightness = brightness;
    switch (brightness) {
      case Brightness.dark:
        primaryColor = const Color(0xFF7C7CFF);
        surfaceColor = const Color(0xFF1C1C1E);
        backgroundColor = const Color(0xFF000000);
        cardColor = const Color(0xFF2C2C2E);
        dividerColor = const Color(0xFF3C3C3E);
        textPrimary = const Color(0xFFFFFFFF);
        textSecondary = const Color(0xFF8E8E93);
      case Brightness.light:
        primaryColor = const Color(0xFF5B5BD6);
        surfaceColor = const Color(0xFFFFFFFF);
        backgroundColor = const Color(0xFFF2F2F7);
        cardColor = const Color(0xFFFFFFFF);
        dividerColor = const Color(0xFFD8D8DC);
        textPrimary = const Color(0xFF1C1C1E);
        textSecondary = const Color(0xFF6E6E73);
    }
  }

  static ThemeData buildTheme() {
    final isDark = _brightness == Brightness.dark;
    final scheme = isDark
        ? ColorScheme.dark(
            primary: primaryColor,
            secondary: primaryColor,
            surface: surfaceColor,
            onSurface: textPrimary,
            onPrimary: Colors.white,
            outline: const Color(0xFF3C3C3E),
          )
        : ColorScheme.light(
            primary: primaryColor,
            secondary: primaryColor,
            surface: surfaceColor,
            onSurface: textPrimary,
            onPrimary: Colors.white,
            outline: const Color(0xFFD1D1D6),
          );
    return ThemeData(
      useMaterial3: true,
      brightness: _brightness,
      colorScheme: scheme,
      scaffoldBackgroundColor: backgroundColor,
      // Snackbars, dialogs and bottom sheets all use the card colour; their
      // text colours are set explicitly so a message is never dark-on-dark in
      // the night skin (the default text colour comes from the colour scheme,
      // which used to be dark even in light mode).
      // Snackbars use the accent colour, so a message always looks the same
      // wherever it is shown. Individual calls must not override it.
      snackBarTheme: SnackBarThemeData(
        backgroundColor: primaryColor,
        contentTextStyle: const TextStyle(color: Colors.white, fontSize: 14),
        actionTextColor: Colors.white,
        behavior: SnackBarBehavior.floating,
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: cardColor,
        titleTextStyle: TextStyle(
          color: textPrimary,
          fontSize: 20,
          fontWeight: FontWeight.w600,
        ),
        contentTextStyle: TextStyle(color: textPrimary, fontSize: 14),
      ),
      bottomSheetTheme: BottomSheetThemeData(
        backgroundColor: cardColor,
        surfaceTintColor: Colors.transparent,
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: backgroundColor,
        elevation: 0,
        centerTitle: true,
        titleTextStyle: TextStyle(
          color: textPrimary,
          fontSize: 18,
          fontWeight: FontWeight.w600,
        ),
        iconTheme: IconThemeData(color: textPrimary),
      ),
      cardTheme: CardThemeData(
        color: cardColor,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(16),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: cardColor,
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide.none,
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide.none,
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: BorderSide(color: primaryColor, width: 2),
        ),
        labelStyle: TextStyle(color: textSecondary),
        hintStyle: TextStyle(color: textSecondary),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: primaryColor,
          foregroundColor: Colors.white,
          elevation: 0,
          padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 16),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
          ),
        ),
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return Colors.white;
          }
          return textSecondary;
        }),
        trackColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return primaryColor;
          }
          return cardColor;
        }),
        trackOutlineColor: WidgetStateProperty.resolveWith((states) {
          if (states.contains(WidgetState.selected)) {
            return Colors.transparent;
          }
          return textSecondary.withValues(alpha: 0.3);
        }),
      ),
      listTileTheme: ListTileThemeData(
        contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 4),
        titleTextStyle: TextStyle(
          color: textPrimary,
          fontSize: 16,
          fontWeight: FontWeight.w500,
        ),
        subtitleTextStyle: TextStyle(
          color: textSecondary,
          fontSize: 14,
        ),
      ),
    );
  }
}
