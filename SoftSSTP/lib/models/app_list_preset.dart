/// A named, reusable list of application package names.
///
/// Lets the user keep a selection of apps (e.g. "Browsers") and apply it to
/// several VPN profiles without picking every app again.
class AppListPreset {
  final String id;
  String name;
  List<String> packages;

  AppListPreset({
    required this.id,
    required this.name,
    List<String>? packages,
  }) : packages = List<String>.from(packages ?? const []);

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'packages': packages,
      };

  factory AppListPreset.fromJson(Map<String, dynamic> json) => AppListPreset(
        id: (json['id'] ?? '') as String,
        name: (json['name'] ?? '') as String,
        packages: ((json['packages'] as List?) ?? const [])
            .map((e) => e.toString())
            .toList(),
      );
}
