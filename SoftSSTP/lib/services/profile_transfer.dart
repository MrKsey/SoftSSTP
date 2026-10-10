import 'dart:convert';
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:share_plus/share_plus.dart';

import '../models/vpn_profile.dart';

/// Reads and writes the `.json` files used to move a single VPN profile between
/// devices or into another app.
///
/// The document keeps the password in clear text on purpose: the file is meant
/// to carry a *working* configuration, so the UI warns the user before exporting
/// or sharing it.
class ProfileTransfer {
  ProfileTransfer._();

  /// Tag written into every file so an unrelated JSON document is rejected with
  /// a clear message instead of being parsed into a half-empty profile.
  static const String _appTag = 'SoftSSTP';
  static const String _type = 'profile';
  static const int _formatVersion = 1;

  /// File extension (without the dot) used for exported profiles.
  static const String extension = 'json';

  /// MIME type handed to the save and share dialogs.
  static const String mimeType = 'application/json';

  /// Serialises [profile] to the bytes written on export.
  static Uint8List encode(VpnProfile profile) {
    final document = <String, dynamic>{
      'app': _appTag,
      'type': _type,
      'version': _formatVersion,
      'profile': profile.toJson(),
    };
    final pretty = const JsonEncoder.withIndent('  ').convert(document);
    return Uint8List.fromList(utf8.encode(pretty));
  }

  /// Builds a file name from the profile's display name, stripped of the
  /// characters that are illegal on Windows, Android and iOS.
  static String fileNameFor(VpnProfile profile) {
    final cleaned = _sanitize(profile.displayName);
    return '${cleaned.isEmpty ? 'profile' : cleaned}.$extension';
  }

  /// Opens the system save dialog and writes [profile] there.
  ///
  /// Returns the destination the user picked, or null when the dialog was
  /// dismissed.
  static Future<Uri?> exportToFile(VpnProfile profile) {
    return FilePicker.saveFile(
      dialogTitle: 'Export profile',
      fileName: fileNameFor(profile),
      bytes: encode(profile),
      mimeType: mimeType,
      type: FileType.custom,
      allowedExtensions: const [extension],
    );
  }

  /// Opens the share sheet with the exported profile attached, so it can be sent
  /// straight to another app.
  static Future<void> shareProfile(VpnProfile profile) async {
    final fileName = fileNameFor(profile);
    await SharePlus.instance.share(
      ShareParams(
        files: [XFile.fromData(encode(profile), mimeType: mimeType)],
        fileNameOverrides: [fileName],
        title: fileName,
        subject: fileName,
      ),
    );
  }

  /// Opens the file picker and returns the profiles found in the chosen file.
  ///
  /// Returns null when the picker was dismissed. Throws [FormatException] when
  /// the file is not a valid profile document.
  static Future<List<VpnProfile>?> pickAndDecode() async {
    final file = await FilePicker.pickFile(
      dialogTitle: 'Import profile',
      type: FileType.custom,
      allowedExtensions: const [extension],
    );
    if (file == null) return null;

    var text = utf8.decode(await file.readAsBytes(), allowMalformed: true);
    // A text editor may have saved the file with a UTF-8 byte-order mark.
    if (text.startsWith('\uFEFF')) text = text.substring(1);
    return decode(text);
  }

  /// Parses the contents of an exported file.
  ///
  /// Accepts the wrapped document written by [encode], a bare profile object and
  /// a plain list of profiles, so hand-made or hand-edited files still import.
  /// Throws [FormatException] when no profile can be read.
  static List<VpnProfile> decode(String contents) {
    final dynamic decoded = jsonDecode(contents);
    final maps = <Map<String, dynamic>>[];

    void addFrom(dynamic value) {
      if (value is Map) maps.add(value.cast<String, dynamic>());
    }

    if (decoded is List) {
      decoded.forEach(addFrom);
    } else if (decoded is Map) {
      final map = decoded.cast<String, dynamic>();
      if (map['profile'] is Map) {
        addFrom(map['profile']);
      } else if (map['profiles'] is List) {
        (map['profiles'] as List).forEach(addFrom);
      } else if (map.containsKey('host') || map.containsKey('username')) {
        addFrom(map);
      }
    }

    if (maps.isEmpty) {
      throw const FormatException('No VPN profile found in this file.');
    }
    return maps.map(VpnProfile.fromJson).toList();
  }

  /// Replaces every character that is not safe in a file name.
  static String _sanitize(String name) {
    final withoutIllegal = name
        .replaceAll(RegExp(r'[\\/:*?"<>|\x00-\x1f]'), ' ')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
    // Windows rejects names that end with a dot or a space.
    final trimmed = withoutIllegal.replaceAll(RegExp(r'[.\s]+$'), '');
    if (trimmed.length > 80) return trimmed.substring(0, 80).trim();
    return trimmed;
  }
}
