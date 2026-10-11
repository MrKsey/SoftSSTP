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
| Flutter plugin | [NavidShokoufeh/sstp_flutter](https://github.com/NavidShokoufeh/sstp_flutter) | Flutter wrapper around that engine. |
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
  profiles also take a virtual hub (VPN Gate servers use `VPN`, hand-made profiles
  default to `DEFAULT`) and an optional client MAC address.
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

## Requirements

- Android 8.0 (API 26) or newer
- A reachable SSTP or SoftEther (SSL-VPN, TCP) server with DHCP enabled
- PAP and MS-CHAPv2 only (no EAP)

## Installation

1. Open the [Releases](https://github.com/MrKsey/SoftSSTP/releases) page.
2. Download the APK for your device:
   - `SoftSSTP-<version>-arm64-v8a.apk` for modern 64-bit devices (recommended)
   - `SoftSSTP-<version>-armeabi-v7a.apk` for older 32-bit devices
3. Install it. If asked, allow installation from your browser or file manager.
4. Open SoftSSTP, go to Settings, enter your server details and connect.

Every release is signed with the same certificate, so updates install over the
previous version without uninstalling.

## Usage

1. Tap the gear icon to open Settings. The Profiles block adds, edits, activates and
   deletes profiles; Diagnostics and About live there too.
2. Open a profile and fill in Host, Port (default 443), Username and Password. Pick the
   Protocol: SSTP or SoftEther. SoftEther profiles also ask for the virtual hub (VPN Gate
   servers use `VPN`, hand-made profiles default to `DEFAULT`) and an optional client MAC.
   Add a name and icon colour if you like, then press Save.
3. Tune DNS, proxy, SSL, PPP, routing and split tunneling if you need to. DNS, proxy, SSL
   and PPP apply to SSTP; SoftEther uses the server's SSL-VPN endpoint and ignores them.
   Routing and App Routing (split tunneling) work for both protocols.
4. For a ready-made server, press Add public profile and pick one from the VPN Gate
   list.
5. On the home screen, tap the server card to switch profiles, then tap connect. Tap
   again or tap the notification to disconnect.

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

## Build from source

```bash
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
```

APKs are written to `build/app/outputs/apk/release/`.

CI builds both architectures on every push to `main` and publishes a GitHub
Release when a `v*` tag is pushed. The version name and code live in
`pubspec.yaml`; bump them, commit and tag `vX.Y.Z` to release. Builds are signed
with a keystore kept in repository secrets.

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
[`SoftSSTP/LICENSE`](SoftSSTP/LICENSE). Upstream components keep their own licenses.
No warranty.
