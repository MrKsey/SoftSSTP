# SoftSSTP

**English** · [Русский](README.ru.md)

Open-source SSTP and SoftEther VPN client for Android, built with Flutter and
Material Design 3. It speaks Microsoft SSTP (MS-SSTP) and the SoftEther
(SSL-VPN, TCP) protocol, so it works with [SoftEther VPN Server](https://www.softether.org/),
[VPN Azure Cloud](https://www.vpnazure.net/) and the public
[VPN Gate](https://www.vpngate.net/) servers.

[![Build status](https://github.com/MrKsey/SoftSSTP/actions/workflows/release.yml/badge.svg)](https://github.com/MrKsey/SoftSSTP/actions/workflows/release.yml)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)
![Flutter](https://img.shields.io/badge/Flutter-3.x-blue.svg)
![License](https://img.shields.io/badge/license-GPLv3-blue.svg)

## Screenshots

<p align="center">
  <img src="docs/screenshots/screenshot-1.jpg" height="520" alt="SoftSSTP home screen">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
  <img src="docs/screenshots/screenshot-2.jpg" height="520" alt="SoftSSTP connected">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
  <img src="docs/screenshots/screenshot-3.jpg" height="520" alt="SoftSSTP split tunneling">
</p>

## Based on

| Layer | Project | Role |
|-------|---------|------|
| SSTP / PPP / TLS | [kittoku/Open-SSTP-Client](https://github.com/kittoku/Open-SSTP-Client) (MIT) | Original Android SSTP client; the protocol engine comes from it. |
| Flutter plugin | [NavidShokoufeh/sstp_flutter](https://github.com/NavidShokoufeh/sstp_flutter) (BSD-3-Clause) | Flutter wrapper around that engine. |
| SoftEther / SSL-VPN | [hoang-rio/SoftEther-Android-Module](https://github.com/hoang-rio/SoftEther-Android-Module) (Apache-2.0) | Vendored SoftEther client (C core, JNI, Kotlin) used by the `softether_flutter` plugin. |
| TLS crypto | [OpenSSL](https://www.openssl.org/) (Apache-2.0) | Built from source for the SoftEther client (see `softether_flutter`). |
| Base app | [CluvexStudio/ZedPass](https://github.com/CluvexStudio/ZedPass) | The project this fork started from. |

## Features

- Material Design 3 with Material You theming. Day, night and auto skin; tap the
  icon in the top-left corner to switch.
- Multiple VPN profiles, each with its own protocol, server, DNS, proxy, PPP, routing, split
  tunneling, reconnection and advanced settings, plus a name and an icon colour. Pick
  the active profile on the home screen; a profile is saved when you press Save.
- Two protocols per profile: Microsoft SSTP and SoftEther (SSL-VPN over TCP). SoftEther
  profiles also take a virtual hub (VPN Gate servers use `VPNGATE`, hand-made profiles
  default to `DEFAULT`), an optional client MAC address and the number of parallel TCP
  connections (default 2, profiles from VPN Gate start with 1, raised in the editor up
  to 8).
- Public profiles from VPN Gate: browse SSTP and SoftEther servers with country flag, line
  quality and ping measured from your device, filter by protocol, then add one with a tap
  (login `vpn`, password `vpn`).
- Connect and disconnect with live status on the home screen and in a notification.
- Live speed (bit rates) and total traffic (byte units).
- Public IP and geolocation before and after connecting, plus the internal IP
  assigned by the server.
- Split tunneling: route only the apps you pick, with a searchable list. The
  selection survives toggling the rule off.
- Auto-reconnect with configurable retry count and interval.
- VPN-slot recovery: if another VPN (AdGuard, for example) takes the slot, SoftSSTP
  reclaims it and reconnects.
- Recovery on network change: switching between Wi-Fi and mobile data rebuilds the
  tunnel automatically.
- DNS, proxy, SSL and PPP controls: custom DNS, HTTP proxy, custom SNI, SSL version,
  hostname and certificate verification, MTU/MRU, auth protocol, IPv4/IPv6, static
  IP and routing.
- Traffic counters that survive screen-off and app recreation.
- Power saving while the screen is off: the per-second traffic updates, the
  notification redraws and the socket polling stop, and the CPU sleeps between
  keepalives and real traffic. The Energy saving switch in Settings stretches
  the same cycles with the screen on too.
- Connection diagnostics. Both protocols write to one file,
  `Android/data/io.github.mrksey.softsstp/files/connection_log.txt`, and the
  View connection log screen shows it inside the app. Logging is off by default and
  turns on in Settings, where the per-link statistics switch sits next to it.

## Requirements

- Android 8.0 (API 26) or newer
- A reachable SSTP or SoftEther (SSL-VPN, TCP) server with DHCP enabled
- For SSTP, PAP and MS-CHAPv2 only (no EAP)

## Installation

1. Open the [Releases](https://github.com/MrKsey/SoftSSTP/releases) page.
2. Download the APK for your device:
   - `SoftSSTP-<version>-arm64-v8a.apk` for modern 64-bit devices (recommended)
   - `SoftSSTP-<version>-armeabi-v7a.apk` for older 32-bit devices
3. Install it. If asked, allow installation from your browser or file manager.
4. Open SoftSSTP, go to Settings, enter your server details and connect.

Every release is signed with the same certificate, so updates install over the
previous version without uninstalling.

An F-Droid listing is under review; the build recipe lives in `fdroid/` and the
release notes in `fastlane/metadata/android`. The build is reproducible, so
F-Droid ships the APKs from this page instead of a rebuild of its own.

## Usage

1. Tap the gear icon to open Settings. The Profiles block adds, edits, activates and
   deletes profiles; Diagnostics and About live there too.
2. Open a profile and fill in Host, Port (default 443), Username and Password. Pick the
   Protocol: SSTP or SoftEther. SoftEther profiles also ask for the virtual hub (VPN Gate
   servers use `VPNGATE`, hand-made profiles default to `DEFAULT`) and an optional client
   MAC. Add a name and icon colour if you like, then press Save.
3. Tune DNS, proxy, SSL, PPP, routing and split tunneling if you need to. DNS, proxy, SSL
   and PPP apply to SSTP; SoftEther uses the server's SSL-VPN endpoint and ignores them.
   Routing and App Routing (split tunneling) work for both protocols.
4. For a ready-made server, press Add public profile and pick one from the VPN Gate
   list.
5. On the home screen, tap the server card to switch profiles, then tap connect. Tap
   again or tap the notification to disconnect.
6. If a connection fails, turn on logging in Settings, Diagnostics and open the
   View connection log screen.

## Settings

The Settings screen is made of blocks.

- Profiles: the profile list with add, edit, export, share, set active and delete. Add
  public profile and Import profile live here too.
- Diagnostics: the Connection logging switch, the Per-link statistics switch and the
  View connection log entry.
- Background: the system battery-optimization exemption and the Energy saving switch.
  Without the exemption Android may stop the VPN in the background. The app already
  saves power on its own: while the screen is off it stops the per-second traffic
  updates, stops redrawing the notification, relaxes the socket polling and lets the
  CPU suspend, waking it only for keepalives and real traffic (the same way
  shadowsocks-android behaves). Energy saving is an extra switch on top of that: it
  also stretches those cycles while the screen is on, so the status and speed refresh
  slower; the traffic speed itself is unaffected. It is off by default and can be
  turned off again during tests.
- Update: a switch for the automatic check at startup (off by default), a Check now
  entry and, when a newer release exists, an entry that opens the release page in the
  browser. The app reads the GitHub releases only; it does not download or install APKs
  itself, so it asks for no permission to install apps and the same build works on
  F-Droid.
- Donate and About: donation wallets and links to the project.

A profile opens on tap and has its own blocks.

- Profile: name and icon colour.
- Server Configuration: host, port, username, password, protocol. SoftEther profiles
  also take the virtual hub and an optional client MAC address.
- DNS Settings: custom DNS server and asking the server for DNS.
- Proxy Settings and PPP, SSTP only: HTTP proxy, MTU/MRU, auth protocol and its
  timeout, IPv4/IPv6, static address.
- SoftEther, SoftEther only: number of TCP connections, establishing interval, MTU,
  SSL session encryption, disabling UDP acceleration and QoS.
- Routing: default route and private-address routes.
- App Routing: split tunneling, saving and loading the app list.
- Reconnection: retry count and interval.
- Advanced and Certificate, SSTP only: hostname and certificate verification, SSL
  version, custom SNI, adding a trusted certificate.

Connection logging and the per-link statistics are off by default; turn them on while
you are chasing a problem. The log is shared by both protocols, each session starts
with a `=== NEW ... SESSION ===` header that also carries the app version. The file is
capped at 512 KB: when it overflows it is cleared and starts over.

## Troubleshooting

### Another VPN (AdGuard) is running

Android allows one active VPN at a time. SoftSSTP claims the VPN slot right before
connecting, so it normally takes over from another VPN on its own. If that VPN keeps
re-claiming the slot (a watchdog) or the tunnel reconnects by itself, that is
expected: SoftSSTP waits for the other VPN to finish starting, reclaims the slot and
reconnects, while the notification shows what is happening.

If it never wins the slot, the other app is probably an always-on VPN, which the
system refuses to dethrone:

- turn off Always-on VPN for the other app (Settings, Network & Internet, VPN, gear
  icon);
- or add SoftSSTP to that app's bypass list (in AdGuard: Settings, Apps management,
  SoftSSTP, Bypass);
- or switch AdGuard to proxy mode.

### The network changes (Wi-Fi and mobile data)

The SSTP socket is bound to the underlying network, so switching breaks it. SoftSSTP
watches the network and rebuilds the tunnel on the new one, with no manual reconnect.
Both this and the slot recovery are bounded, so a flapping network or a persistent
takeover cannot loop forever.

### The connection log

Logging is off by default, so turn Connection logging on before you reproduce the
problem. Both protocols write to one file, a new session starts with a
`=== NEW ... SESSION ===` header that also carries the app version and the APK install
time. The View connection log screen shows the file path, and the log can be copied to
the clipboard or cleared. Attach the whole file to a bug report.

### SoftEther extra TCP connections

The number of parallel TCP connections lives in the profile under SoftEther, from one to
eight. A server of your own defaults to two, a profile added from the VPN Gate list starts
with one, yet public servers usually carry more than one, so raise the number on a fast
server. If the extra links never come up, the log shows how many links actually went up
and why each failed attempt died. The Per-link statistics switch adds a traffic split per
link every 5 seconds, and the split at the end of a session is always written.

Download and upload use the links differently. Download is decided by the server: it
settles on one connection for the data and keeps sending there, so the extra links carry
only keepalives and background traffic while a single connection pulls the whole
download. Upload is picked by the client for every block, but not by spreading the load:
the block goes to the link that most recently delivered data (the reference client's
LateCount rule), and round-robin only breaks a tie. In practice one link carries almost
all the upload, usually the same one that carries the download, so the split at the end
of a session looks like `primary tx=137.5M rx=135.6M | addl1 tx=1.8M rx=262.4K`. Raising
the number of connections therefore buys resilience and gives the server more links to
choose from, not extra upload throughput.

Two connections already provide a fallback. If the active link drops, the server moves the
data to the second one, and the tunnel survives an outage that would kill a single
connection. Raising the number past two is pointless. In testing with four links all the
traffic still went down one of them, with no gain in speed.

## Build from source

```bash
# Requirements: Flutter (stable), JDK 17, Android SDK + NDK.
# The SoftEther plugin cross-compiles OpenSSL during the Gradle build, so bash,
# perl and make must also be available (they are on Linux/macOS/CI; on Windows
# use WSL or Git Bash).

git clone --recursive https://github.com/MrKsey/SoftSSTP.git
cd SoftSSTP/SoftSSTP

flutter pub get
dart run flutter_launcher_icons

# Universal APK
flutter build apk --release

# Per-architecture APKs (arm64 and arm)
flutter build apk --release --split-per-abi \
  --target-platform android-arm64,android-arm
```

The `--recursive` flag checks out the pinned OpenSSL submodule that the
`softether_flutter` plugin needs; if you cloned without it, run
`git submodule update --init --recursive`.

Flutter writes the APKs to `build/app/outputs/flutter-apk/`, and Gradle puts renamed
copies next to them in `build/app/outputs/apk/release/`.

The version name and code live in `pubspec.yaml`. A split build gives each architecture
its own version code: the number from pubspec is multiplied by 10 and the slot number is
added, one for armeabi-v7a and two for arm64-v8a. The universal APK keeps the original
code. The same slots are set in `VercodeOperation` in the F-Droid recipe and in
`SoftSSTP/android/app/build.gradle.kts`.

CI builds both architectures on every push to `main` and publishes a GitHub Release when
a `v*` tag is pushed. Bump the version in `pubspec.yaml` and commit it before tagging,
and make the tag name match the version. Builds are signed with a keystore kept in
repository secrets. Documentation-only changes, `docs`, `fastlane` and `fdroid` do not
trigger a build.

### F-Droid

The build recipe lives in `fdroid/io.github.mrksey.softsstp.yml` and the release notes
for F-Droid in `fastlane/metadata/android`. The SoftEther plugin builds OpenSSL from
source and no prebuilt `.so` files are committed, so the recipe pins the submodule and
the NDK. Each architecture gets its own build entry with a version code from
`VercodeOperation`.

The build is reproducible. The recipe follows `templates/build-flutter.yml`, moves the
checkout to the path the GitHub runner uses, and points `binary` at the APK of the
matching release. F-Droid therefore publishes the APK from the releases page, signed
with the maintainer's key, which `AllowedAPKSigningKeys` pins down. Three details keep
the two builds identical: OpenSSL is configured with a fixed prefix, its build date is a
constant in the build script, and the linker build-id is dropped for every native
module. Nothing in the build switches the app between channels: when a newer version
exists the app only opens the release page and never installs APKs itself, so one APK
serves both GitHub and F-Droid.

## Donate

You can support the project by sending a donation to any of the wallets below.

<table>
  <tr>
    <td align="center">
      <img src="docs/screenshots/donate/bitcoin.jpg" width="220" alt="Bitcoin QR code"><br>
      <b>Bitcoin</b><br>
      <code>bc1qdf9wryj6tnyyr7ed4dkfv03nn0dxjf27xshfcr</code>
    </td>
    <td align="center">
      <img src="docs/screenshots/donate/ethereum.jpg" width="220" alt="Ethereum QR code"><br>
      <b>Ethereum</b><br>
      <code>0xE22FAB5337A5506D9e7Ef9D4914De4d963Fbbf88</code>
    </td>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/screenshots/donate/solana.jpg" width="220" alt="Solana QR code"><br>
      <b>Solana</b><br>
      <code>7XcNM2M9rndFbJdjRtyycxkQXq8dPXEP87toiQX4EZfx</code>
    </td>
    <td align="center">
      <img src="docs/screenshots/donate/tron.jpg" width="220" alt="Tron QR code"><br>
      <b>Tron</b><br>
      <code>TCYpTxSmdPN8p4fmRwtPGk3HHBf77FypeA</code>
    </td>
  </tr>
</table>

## License

SoftSSTP is released under the GNU General Public License v3.0; see
[`LICENSE`](LICENSE). Upstream components keep their own licenses; the list is in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). No warranty.
