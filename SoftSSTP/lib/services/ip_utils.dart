// Helpers for choosing which address to show in the "Public IP" block.
//
// The native layer may report several addresses at once (for example an IPv4
// address and a link-local IPv6 address). IPv4 is always preferred; IPv6 is
// only shown when no IPv4 address is available.

/// Returns the IPv4 address contained in [raw] when there is one, otherwise the
/// first IPv6 address, otherwise the raw (trimmed) string.
String preferIpv4(String raw) {
  final trimmed = raw.trim();
  if (trimmed.isEmpty) return '';

  final parts = trimmed
      .split(RegExp(r'[\s,;]+'))
      .where((part) => part.isNotEmpty)
      .toList();
  if (parts.isEmpty) return '';

  for (final part in parts) {
    if (isIpv4(part)) return part;
  }
  for (final part in parts) {
    if (isIpv6(part)) return _stripZone(part);
  }
  return parts.first;
}

/// Whether [value] looks like a dotted-quad IPv4 address.
bool isIpv4(String value) {
  final candidate = _stripZone(value);
  final parts = candidate.split('.');
  if (parts.length != 4) return false;
  for (final part in parts) {
    final n = int.tryParse(part);
    if (n == null || n < 0 || n > 255) return false;
  }
  return true;
}

/// Whether [value] looks like an IPv6 address.
bool isIpv6(String value) => _stripZone(value).contains(':');

/// Drops the zone index (`fe80::1%wlan0` -> `fe80::1`).
String _stripZone(String value) {
  final percent = value.indexOf('%');
  return percent == -1 ? value : value.substring(0, percent);
}
