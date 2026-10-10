/// Turns an ISO 3166-1 alpha-2 country code into its flag emoji.
///
/// Returns a globe when the code is missing or not a valid two-letter code.
String countryFlagEmoji(String code) {
  final upper = code.trim().toUpperCase();
  if (upper.length != 2) return '\u{1F310}';

  const base = 0x1F1E6; // regional indicator symbol letter A
  final first = upper.codeUnitAt(0);
  final second = upper.codeUnitAt(1);
  if (first < 0x41 || first > 0x5A || second < 0x41 || second > 0x5A) {
    return '\u{1F310}';
  }

  return String.fromCharCodes([
    base + (first - 0x41),
    base + (second - 0x41),
  ]);
}
