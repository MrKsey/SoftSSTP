# SoftSSTP

**English** · [Русский](README.ru.md)

A modern, open-source **SSTP VPN client for Android**, written in Flutter with a
Material Design 3 interface. It works with any server that speaks the Microsoft
**SSTP (MS-SSTP)** protocol — most commonly
[SoftEther VPN Server](https://www.softether.org/) and
[VPN Azure Cloud](https://www.vpnazure.net/).

[![Build & Release](https://github.com/MrKsey/SoftSSTP/actions/workflows/release.yml/badge.svg)](https://github.com/MrKsey/SoftSSTP/actions/workflows/release.yml)
![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-green.svg)
![Flutter](https://img.shields.io/badge/Flutter-3.x-blue.svg)
![License](https://img.shields.io/badge/license-GPLv3-blue.svg)

---

## Screenshots

<p align="center">
  <img src="docs/screenshots/screenshot-1.jpg" height="520" alt="SoftSSTP home screen">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
  <img src="docs/screenshots/screenshot-2.jpg" height="520" alt="SoftSSTP connected">&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;
  <img src="docs/screenshots/screenshot-3.jpg" height="520" alt="SoftSSTP split tunneling">
</p>

---

## Based on

SoftSSTP is a fork of **[CluvexStudio/ZedPass](https://github.com/CluvexStudio/ZedPass)**,
which itself is built on top of the original SSTP protocol implementation.

| Layer | Project | Role |
|-------|---------|------|
| SSTP / PPP / TLS protocol | [kittoku/Open-SSTP-Client](https://github.com/kittoku/Open-SSTP-Client) (MIT) | Original Android SSTP client; the protocol engine derives from it. |
| Flutter plugin | [NavidShokoufeh/sstp_flutter](https://github.com/NavidShokoufeh/sstp_flutter) | Flutter wrapper around that engine. |
| Base app | [CluvexStudio/ZedPass](https://github.com/CluvexStudio/ZedPass) | The project this fork is based on. |
| This project | **SoftSSTP** (this repo) | Reworked app **and** a patched `sstp_flutter` with bug fixes and new features. |

## Features

- **Material Design 3 UI** with Material You theming.
- **Day / night / auto skin** — tap the icon in the top-left corner to cycle
  between *auto* (follows the system), *day* and *night*.
- **Multiple VPN profiles** — keep several servers side by side. Each profile
  stores all of its own settings (server, DNS, proxy, PPP, routing, split
  tunneling, reconnection, advanced), plus a name and an icon colour. Pick the
  active profile from the home screen; a new profile is only created when you
  press **Save**.
- **Public profiles from VPN Gate** — browse the public MS-SSTP server list
  (country flag, line quality and the ping measured from your device) and turn
  any server into a ready-to-use profile (login `vpn` / password `vpn`) with a
  single tap.
- **Connect / disconnect** with live status, shown both on the home screen and in
  a foreground notification.
- **Live speed** (in bit rates) and **total traffic** (in byte units).
- **Public IP + geolocation** before and after connecting, plus the
  **server-assigned internal IP**.
- **Split tunneling** — route only the apps you choose, with a searchable picker.
  The selection persists even when the rule is toggled off.
- **Auto-reconnect** with configurable retry count and interval.
- **Automatic VPN-slot recovery** — if another VPN (e.g. AdGuard) takes over the
  slot, SoftSSTP reclaims it and reconnects on its own.
- **Automatic recovery on network change** — switching between Wi-Fi and mobile
  data rebuilds the tunnel automatically.
- **Full DNS / proxy / SSL / PPP controls:** custom DNS, HTTP proxy, custom SNI,
  SSL version, hostname and certificate verification, MTU/MRU, auth protocol,
  IPv4/IPv6, static IP and routing.
- **Persistent traffic counters** that survive screen-off and app recreation.

### Fixes over the upstream engine

- **TLS handshake fix** — the original used `SSLContext.getInstance("TLSv1.3")`,
  which throws on Android. SoftSSTP uses the generic `"TLS"` context and adds
  custom SNI and socket protection.
- **High-throughput traffic accounting** — the original sent a MethodChannel call
  per packet on the main thread, capping throughput. SoftSSTP uses lock-free
  counters with a 1-second reporter.

## Requirements

- Android 8.0 (API 26) or newer
- A reachable SSTP server with DHCP enabled
- Only **PAP** and **MS-CHAPv2** authentication are supported (no EAP)

## Installation

1. Open the [**Releases**](https://github.com/MrKsey/SoftSSTP/releases) page.
2. Download the APK for your device:
   - `SoftSSTP-<version>-arm64-v8a.apk` — modern 64-bit devices (**recommended**)
   - `SoftSSTP-<version>-armeabi-v7a.apk` — older 32-bit devices
3. Install it (allow installation from your browser/file manager if asked).
4. Open SoftSSTP, go to **Settings**, enter your server details and connect.

> Every release is signed with the same certificate, so updates install over the
> previous version without uninstalling.

## Usage

1. Tap the gear icon to open **Settings**. The **Profiles** block lets you add,
   edit, activate or delete profiles, and holds **Diagnostics** and **About**.
2. Open a profile and fill in **Host**, **Port** (default `443`), **Username**
   and **Password**. Give it a name and an icon colour if you like, then press
   **Save**.
3. Optionally tune DNS, proxy, SSL, PPP, routing and split tunneling.
4. To add a ready-made public server, press **Add public profile** and pick one
   from the VPN Gate list.
5. On the home screen, tap the server card to switch the active profile, then tap
   the connect button. Tap the button or the notification again to disconnect.

## Troubleshooting

### Another VPN (AdGuard) is running

Android allows only **one active VPN at a time**. SoftSSTP claims the VPN slot
right before connecting, so it normally **takes over from another VPN
automatically**. If that other VPN keeps re-claiming the slot (a watchdog), or
you see the tunnel reconnect on its own, this is expected: SoftSSTP **waits for
the other VPN to finish starting, then reclaims the slot** and reconnects, while
the notification shows what is happening.

If it never wins the slot, the other app is probably an **always-on VPN**, which
the system refuses to dethrone:

- turn off **Always-on VPN** for the other app
  (*Settings → Network & Internet → VPN → gear icon*);
- or add **SoftSSTP** to that app's bypass/exclusion list (in AdGuard:
  *Settings → Apps management → SoftSSTP* → **Bypass**);
- or switch AdGuard to **proxy mode** instead of VPN mode.

### The network changes (Wi-Fi ↔ mobile data)

The SSTP socket is bound to the underlying network, so switching networks breaks
it. SoftSSTP watches the network and **rebuilds the tunnel automatically** on the
new one — no manual reconnect needed. Both this and the VPN-slot recovery are
bounded, so a flapping network or a persistent takeover cannot loop forever.

## Build from source

```bash
# Requirements: Flutter (stable), JDK 17, Android SDK

git clone https://github.com/MrKsey/SoftSSTP.git
cd SoftSSTP/SoftSSTP

flutter pub get
dart run flutter_launcher_icons

# Universal APK
flutter build apk --release

# Per-architecture APKs (arm64 + arm)
flutter build apk --release --split-per-abi \
  --target-platform android-arm64,android-arm
```

The APKs are written to `build/app/outputs/apk/release/`.

Pushing to `main` (or a `v*` tag) triggers CI, which builds both architectures
and publishes a GitHub Release. The version is auto-incremented from the highest
existing tag (`1.0.0`, `1.0.1`, `1.0.2`, …), or taken verbatim from a pushed
tag; builds are signed with a stable keystore provided through repository
secrets.

## License

SoftSSTP is released under the **GNU General Public License v3.0** — see
[`SoftSSTP/LICENSE`](SoftSSTP/LICENSE). Upstream components keep their own
licenses. Use this software at your own risk; no warranty is provided.

<!-- Docs-only changes (*.md) are excluded from CI on purpose. -->
