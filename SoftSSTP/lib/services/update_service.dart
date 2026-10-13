import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// A published GitHub release that is newer than the running build.
class UpdateInfo {
  /// Version without the leading "v", e.g. "1.4.1".
  final String version;

  /// Raw tag name, e.g. "v1.4.1".
  final String tagName;

  /// Release notes as written on GitHub (may be empty).
  final String notes;

  /// Browser URL of the release page.
  final String releaseUrl;

  /// Release asset name to download URL, e.g.
  /// "SoftSSTP-1.4.1-arm64-v8a.apk" to the direct download link.
  final Map<String, String> assets;

  const UpdateInfo({
    required this.version,
    required this.tagName,
    required this.notes,
    required this.releaseUrl,
    required this.assets,
  });
}

/// Raised when the release check or the download cannot be completed.
class UpdateCheckException implements Exception {
  final String message;
  const UpdateCheckException(this.message);

  @override
  String toString() => message;
}

/// Checks the project's GitHub releases for a newer version and drives the
/// download and installation of the matching APK.
class UpdateService {
  static const String repo = 'MrKsey/SoftSSTP';
  static const String _latestReleaseUrl =
      'https://api.github.com/repos/$repo/releases/latest';

  static const MethodChannel _channel = MethodChannel('softsstp/update');

  /// True in the F-Droid build, which passes `--dart-define=FDROID=true`.
  /// F-Droid forbids apps that update themselves, so the Update section is
  /// hidden there.
  static const bool isFdroidBuild = bool.fromEnvironment(
    'FDROID',
    defaultValue: false,
  );

  /// Asks GitHub for the latest release. Returns the update when it is newer
  /// than [currentVersion], null when the build is up to date. Throws
  /// [UpdateCheckException] when the release could not be read.
  static Future<UpdateInfo?> checkForUpdate({
    required String currentVersion,
  }) async {
    final client = HttpClient()
      ..connectionTimeout = const Duration(seconds: 15);
    try {
      final request = await client.getUrl(Uri.parse(_latestReleaseUrl));
      request.headers.set(HttpHeaders.userAgentHeader, 'SoftSSTP-Android');
      request.headers.set(
        HttpHeaders.acceptHeader,
        'application/vnd.github+json',
      );
      final response = await request.close().timeout(
        const Duration(seconds: 20),
      );
      if (response.statusCode != 200) {
        throw UpdateCheckException(
          'GitHub returned HTTP ${response.statusCode}',
        );
      }
      final body = await response.transform(utf8.decoder).join();
      final decoded = jsonDecode(body);
      if (decoded is! Map) {
        throw const UpdateCheckException('Unexpected release payload');
      }

      final tag = (decoded['tag_name'] as String?)?.trim() ?? '';
      if (tag.isEmpty) {
        throw const UpdateCheckException('The latest release has no tag');
      }
      if (!isNewerVersion(tag, currentVersion)) {
        return null;
      }

      final assets = <String, String>{};
      final rawAssets = decoded['assets'];
      if (rawAssets is List) {
        for (final asset in rawAssets) {
          if (asset is! Map) continue;
          final name = asset['name'];
          final url = asset['browser_download_url'];
          if (name is String && url is String) assets[name] = url;
        }
      }

      return UpdateInfo(
        version: tag.replaceFirst(RegExp(r'^[vV]'), ''),
        tagName: tag,
        notes: (decoded['body'] as String?)?.trim() ?? '',
        releaseUrl:
            (decoded['html_url'] as String?) ??
            'https://github.com/$repo/releases',
        assets: assets,
      );
    } on UpdateCheckException {
      rethrow;
    } catch (e) {
      throw UpdateCheckException('$e');
    } finally {
      client.close(force: true);
    }
  }

  /// True when the system allows this app to install packages.
  static Future<bool> canRequestPackageInstalls() async {
    try {
      return await _channel.invokeMethod<bool>('canRequestPackageInstalls') ??
          false;
    } catch (_) {
      return false;
    }
  }

  /// Opens the system screen where the user grants "install unknown apps".
  static Future<void> openInstallPermissionSettings() async {
    try {
      await _channel.invokeMethod<void>('openInstallPermissionSettings');
    } catch (_) {}
  }

  /// Downloads the APK for this device's ABI and hands it to the system
  /// installer. Throws [UpdateCheckException] when the download fails.
  static Future<void> downloadAndInstall(UpdateInfo info) async {
    final assets = info.assets.entries
        .map((e) => {'name': e.key, 'url': e.value})
        .toList();
    if (assets.isEmpty) {
      throw const UpdateCheckException('This release has no APK attached.');
    }
    try {
      await _channel.invokeMethod<void>('downloadAndInstall', {
        'version': info.version,
        'assets': assets,
      });
    } on PlatformException catch (e) {
      throw UpdateCheckException(e.message ?? 'Download failed');
    }
  }

  /// True when [candidate] is a newer version than [current]. Handles a leading
  /// "v", a "+build" suffix and a "-prerelease" suffix. Exposed for tests.
  @visibleForTesting
  static bool isNewerVersion(String candidate, String current) =>
      _compare(_parseVersion(candidate), _parseVersion(current)) > 0;

  /// Parses "v1.4.1", "1.4.1+10" or "1.4.1-beta" into numeric components.
  static List<int> _parseVersion(String raw) {
    final cleaned = raw.trim().replaceFirst(RegExp(r'^[vV]'), '');
    final core = cleaned.split('+').first.split('-').first;
    return core.split('.').map((p) => int.tryParse(p) ?? 0).toList();
  }

  /// Compares two parsed versions: negative when [a] is older, positive when it
  /// is newer, zero when they are equal.
  static int _compare(List<int> a, List<int> b) {
    final length = a.length > b.length ? a.length : b.length;
    for (var i = 0; i < length; i++) {
      final x = i < a.length ? a[i] : 0;
      final y = i < b.length ? b[i] : 0;
      if (x != y) return x.compareTo(y);
    }
    return 0;
  }
}
