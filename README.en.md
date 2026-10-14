<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="96" height="96" alt="Layer">
</p>

<h1 align="center">Layer</h1>

<p align="center">
  <strong>English</strong> ·
  <a href="README.md">Русский</a>
</p>

<p align="center">
  <strong>Android VPN client with smart routing</strong><br>
  Not a VPN provider: bring your own <code>vless://</code> link or subscription, and your own VPS.
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-4C6EF5?style=flat-square" alt="GPL-3.0"></a>
  <a href="https://github.com/j4st86/Layer/releases"><img src="https://img.shields.io/github/v/release/j4st86/Layer?style=flat-square&color=2F9E44" alt="Release"></a>
  <img src="https://img.shields.io/badge/Android-14%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 14+">
  <img src="https://img.shields.io/badge/arch-arm64-868E96?style=flat-square" alt="arm64">
  <img src="https://img.shields.io/badge/sing--box-1.14.3-495057?style=flat-square" alt="sing-box 1.14.3">
</p>

Layer is a client for a server you already have. By default traffic goes **direct**. VLESS carries apps and domains you send through the VPN, plus sites from the automatic lists.

The lists target services that are often blocked in Russia: YouTube, Telegram, Instagram, and the rest of [itdoginfo](https://github.com/itdoginfo/allow-domains). Banking, government sites, and LAN traffic stay off the VPS.

This is a pet project, not a key shop. You need your own `vless://` link or subscription. There is no UUID in the repo. Phones on Android 13 and older are not supported.

## Why

Many clients can split-tunnel. Layer is tuned for Russia: ready-made lists, per-app and per-domain rules, and your own VLESS.

- **Routing follows your rules.** Your apps and domains come first, then [itdoginfo/allow-domains](https://github.com/itdoginfo/allow-domains). Everything else goes direct.
- **Your own key.** Import a `vless://` link or an HTTPS subscription from a QR code or the clipboard. Reality, TLS, Vision, WebSocket, gRPC, XHTTP.
- **Several servers.** Manual nodes and subscriptions, notes, and auto-pick of a working server.
- **Ads only for apps you pick.** The list is [Hagezi Multi PRO](https://github.com/hagezi/dns-blocklists). An empty selection blocks nothing.
- **Push stays put.** Google Play services stay outside the tunnel, so notifications keep their normal path.
- **Always-on.** Android brings the VPN back after a reboot if you enable it in system settings.
- **Russian and English.** Pin a language or follow the system.

```text
app: Direct / VPN                 ← your rule beats the lists
        ↓
your domain: Direct / VPN
        ↓
ads: only the apps you selected
        ↓
itdoginfo lists (YouTube, Telegram, …)
        ↓
direct
```

The VPN server itself, the local network, and Layer also go direct. Otherwise the tunnel would loop back into itself.

## Features

- Native client: Kotlin, Jetpack Compose, Android `VpnService`, [sing-box](https://github.com/SagerNet/sing-box) 1.14.3 (libbox)
- Automatic lists: YouTube, Telegram, Instagram / Facebook, X, Discord, TikTok, Google AI, Google Play, Cloudflare, foreign media, HDRezka, geoblock. They refresh about once a week
- Ad and tracker blocking from a host list, per app you select. That list also refreshes about weekly, and only on Wi‑Fi
- After the first server, Layer asks once about common apps: messengers and video through the VPN, banks and some local services bypassing it
- App rules and custom domains, including zones like `.рф`
- Switching between Wi‑Fi and mobile rebuilds the tunnel once the new network is up. Unlocking the screen does not tear it down
- Battery mode Optimized is enough. Layer does not ask to ignore battery optimizations
- Diagnostics with secrets stripped from logs; reports go to Downloads
- **arm64** phones only. x86 emulators and 32-bit devices are not supported

## Install

1. Download the APK from [Releases](https://github.com/j4st86/Layer/releases/latest).
2. Allow installs from the browser or GitHub.
3. Add a server with **+** on the home screen.
4. Optional: Settings → VPN → Layer → Always-on VPN.

Keys and subscriptions stay on the phone.

## Build

`libbox.aar` is not in git. Build it locally:

```bash
./scripts/build-libbox.sh
./gradlew :core:test :app:assembleRelease
```

You need JDK 17, Android SDK 37, Go 1.25+, and NDK 28. The script builds slim sing-box **v1.14.3** (TUN, VLESS, Reality/uTLS, gVisor). The build leaves out QUIC, WireGuard, Tailscale, Naive, and OpenVPN. Kernel version and patches live in `sing-box.properties` and `scripts/libbox/patches`.

Release signing reads local `keystore.properties` (see `keystore.properties.example`). The keystore is not in the repo.

## Always-on VPN

Android only: **Network & internet → VPN → Layer → Always-on VPN**. The app cannot flip that switch itself.

## License

[GNU GPL v3](LICENSE). The VPN core is sing-box / libbox, also GPL-3.0; linking it requires the same license for Layer.

Third-party lists (the ad host list, itdoginfo, the core) are listed in [THIRD_PARTY.md](THIRD_PARTY.md). Layer is not affiliated with their authors.
