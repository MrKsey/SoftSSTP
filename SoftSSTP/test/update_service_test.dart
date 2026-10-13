// Tests for the release version comparison used by the in-app updater.

import 'package:flutter_test/flutter_test.dart';

import 'package:softsstp/services/update_service.dart';

void main() {
  group('UpdateService.isNewerVersion', () {
    test('treats an equal version as not newer', () {
      expect(UpdateService.isNewerVersion('v1.4.0', '1.4.0'), isFalse);
    });

    test('ignores a leading v', () {
      expect(UpdateService.isNewerVersion('v1.4.1', '1.4.0'), isTrue);
    });

    test('compares each component numerically, not as text', () {
      expect(UpdateService.isNewerVersion('1.10.0', '1.9.0'), isTrue);
      expect(UpdateService.isNewerVersion('1.9.0', '1.10.0'), isFalse);
    });

    test('ignores the +build suffix', () {
      expect(UpdateService.isNewerVersion('1.4.0+11', '1.4.0+10'), isFalse);
      expect(UpdateService.isNewerVersion('1.4.1+1', '1.4.0+10'), isTrue);
    });

    test('ignores a prerelease suffix', () {
      expect(UpdateService.isNewerVersion('1.4.1-beta', '1.4.0'), isTrue);
    });

    test('treats a missing component as zero', () {
      expect(UpdateService.isNewerVersion('1.5', '1.4.9'), isTrue);
      expect(UpdateService.isNewerVersion('1.4', '1.4.0'), isFalse);
    });

    test('an older version is not newer', () {
      expect(UpdateService.isNewerVersion('1.3.9', '1.4.0'), isFalse);
    });
  });
}
