# TuoYu Clean

[简体中文](README.md) | [繁體中文](README.zh-TW.md) | [English](README.en.md)

A small utility for Xiaomi / Redmi devices that solves two everyday annoyances: **keeping the screen awake**, and **removing the ads baked into the system**.

No root, no custom ROM, just install and use.

---

## Contents

- [Features](#features)
- [Install](#install)
- [Screen control](#screen-control)
- [System ad blocking](#system-ad-blocking)
- [GKD for in-app ads](#gkd-for-in-app-ads)
- [Permissions](#permissions)
- [FAQ](#faq)
- [Related projects](#related-projects)
- [Building](#building)
- [License](#license)

---

## Features

| Feature | What it does | Prerequisite |
|---|---|---|
| Keep screen awake | Stops the screen from turning off while reading or navigating | Shizuku (or a one-time adb grant) |
| Disable auto-dim | Stops the system from lowering brightness | Shizuku (or a one-time adb grant) |
| Local ad blocking | A local VPN resolves ad hosts to `0.0.0.0` | VPN permission |
| MIUI recommendation switches | Turns off the video feed, recommendation entries and personalised ads | Shizuku |
| Full blocklist | ~190k domains, toggleable | None |
| GKD integration | One-tap GKD install, subscription link copy | None |

---

## Install

1. Download the latest `托予净化-x.y.z.apk` from [Releases](../../releases)
2. Tap to install on the device
3. On first launch a guide appears — follow it to authorise Shizuku

> If Android warns about unknown sources, allow installing from this source once.

---

## Screen control

### Prerequisite: permission to change system settings

Keep-awake and auto-dim write system settings, which requires `WRITE_SECURE_SETTINGS`. That is a signature-level permission — **an app cannot request it for itself**. There are exactly two ways to grant it.

#### Option 1: Shizuku (recommended, no computer)

1. Open the app and find the **Shizuku** card at the top of the Screen control page
2. If it says "Shizuku is not installed", tap **Install Shizuku** (the APK is bundled)
3. Open Shizuku and **start the service** following its own guide
   - On Android 11+ this can be done over wireless debugging, no computer needed
   - See the [Shizuku setup guide](https://shizuku.rikka.app/guide/setup/)
4. Come back to this app and tap **Authorise Shizuku**, then allow
5. The app then grants `WRITE_SECURE_SETTINGS` for itself in the background — nothing else to do

#### Option 2: one adb command from a computer

If you would rather not install Shizuku, connect USB and run this once:

```bash
adb shell pm grant com.tuoyu.clean android.permission.WRITE_SECURE_SETTINGS
```

One time only; no computer needed afterwards.

> On first launch, tap "Other ways" in the guide dialog to see this command with a copy button.

### Usage

Once authorised:

- **Keep screen awake** — on: the screen never sleeps; off: the original timeout is restored
- **Disable auto-dim** — on: brightness will not drop before the timeout
- **Refresh screen status** — re-reads the real system values and repaints
- **Appearance** — follow system / light / dark, remembered across launches

The status card shows the actual system timeout, e.g. "Always on / Never turns off automatically" or "1 min / Screen turns off after this time".

### Launcher shortcuts

Long-press the app icon to trigger three actions without opening the UI:

- Turn keep-awake on / off
- Turn auto-dim on / off
- Turn ad blocking on / off

---

## System ad blocking

Three layers, each covering a different kind of ad.

### Layer 1: local DNS filtering (network)

The app starts a local VPN that intercepts DNS only:

```
App resolves a hostname
  ├─ on the blocklist  → answered with 0.0.0.0, the ad fails to load
  └─ not on the list    → forwarded to 223.5.5.5, normal browsing
```

**Only DNS is routed through the VPN** — real traffic bypasses it entirely, so there is no speed penalty.

#### Usage

1. Open the Ad blocking page
2. Turn on **Block system ads**
3. Allow the system VPN consent dialog on first use

A key icon in the status bar afterwards (VPN connected) is expected.

#### Blocklists

- **Curated list** (always on): Xiaomi ad hosts verified one by one on a real device, no collateral damage
- **Full blocklist** (toggleable): ~190k domains merged from [StevenBlack/hosts](https://github.com/StevenBlack/hosts) and [GOODBYEADS](https://github.com/8680/GOODBYEADS)

The full list is experimental because a list that size inevitably contains over-broad rules. A built-in whitelist protects the app store download CDN, accounts, cloud services and push.

**If a site or app suddenly stops working**, toggle the full blocklist off first. If that fixes it, send me the domain and I will whitelist it.

#### Verifying

Tap **Verify blocking** and the app actually resolves every host on the list, then reports:

```
Blocked 14 / 14 hosts
[x] api.ad.xiaomi.com      Xiaomi ad delivery
[x] tracking.miui.com      Behavioural tracking
...
Normal sites still resolve — browsing is unaffected.
```

### Layer 2: MIUI recommendation switches (settings)

MIUI / HyperOS only:

| Setting | Effect |
|---|---|
| Video recommendation feed | App drawer left page stops pushing videos |
| Video tab | Hides the video tab |
| Recommendation entry | Hides the recommendation entry |
| Personalised ads | Disables targeted advertising |

Applied together with the master switch; the row below it shows "MIUI switches 5/5" when all are off.

> On non-Xiaomi devices these keys do not exist and the row reads "not on this ROM".

### Layer 3: GKD (UI layer)

See the next section.

### Ads the network layer cannot stop

Some ads are **first-party slots** whose content is delivered by the app's own API (the recommendation cards in Calendar and Weather, for example). Blocking DNS does nothing there — blocking the API would break the feature itself.

Those need to be turned off in that app's own settings, or hidden by GKD. The app states this limitation in the UI.

---

## GKD for in-app ads

[GKD](https://github.com/gkd-kit/gkd) is an open-source ad skipper. It uses an accessibility service to find and click or hide ad widgets — splash ads, popups, feed ads.

### Install

The **GKD** block sits at the bottom of the Ad blocking page; the APK is bundled:

1. Tap **Install GKD**
2. The system installer appears — continue
3. The button then reads "GKD installed (version)"

> If the installer warns about network problems, tap Continue — ad blocking also blocks MIUI's online installer check.

### Configure

1. Tap **Copy subscription link**; the app copies the officially recommended subscription:
   ```
   https://registry.npmmirror.com/@gkd-kit/subscription/latest/files/dist/gkd.json5
   ```
2. Open GKD → Subscriptions, paste and import
3. Grant the accessibility permission as GKD asks
4. Enable the rule groups you need

See the [GKD documentation](https://gkd.li/guide/) for details.

---

## Permissions

| Permission | Purpose | How it is granted |
|---|---|---|
| `WRITE_SECURE_SETTINGS` | Change screen timeout and brightness | Granted by Shizuku automatically, or via adb |
| `INTERNET` | Forward DNS queries through the VPN | Automatic at install |
| `BIND_VPN_SERVICE` | Run the local DNS filter | System dialog on first enable |
| `REQUEST_INSTALL_PACKAGES` | Launch the installer for Shizuku / GKD | System dialog on first use |
| `POST_NOTIFICATIONS` | Foreground service notification | System dialog |

The app **collects nothing** and uploads nothing. DNS queries are only forwarded to whatever resolver your network already uses.

---

## FAQ

**Q: I flipped a switch and nothing happened.**

Read the toast. It now names the exact blocker:

| Message | Meaning | What to do |
|---|---|---|
| Shizuku is not installed | Shizuku not detected | Tap "Install Shizuku" |
| Shizuku service is not running | Installed but not started | Open Shizuku and tap Start |
| Tap Authorise Shizuku above | Running but this app is not authorised | Tap it and allow |

**Q: Does blocking slow down my connection?**

No. The VPN only routes DNS queries (to `10.111.222.2`). Web, video and everything else bypass it completely.

**Q: Does it conflict with Clash or other proxies?**

Yes. Android allows only one active VPN, so it is either this or your proxy.

**Q: Why do Calendar and Weather still show recommendations?**

See "Ads the network layer cannot stop" above — that content is served by the app's own API. Use GKD or turn off recommendations inside that app.

**Q: Can the full blocklist break normal sites?**

It can, which is why it is a toggle. A whitelist protects common services, and over-broad entries such as `baidu.com`, `taobao.com` and `youtube.com` were removed from the list. If something breaks, toggle it off to confirm, then report the domain.

**Q: Are settings restored after uninstalling?**

The screen timeout returns to the value recorded before keep-awake was enabled. DNS blocking stops the moment the switch is turned off or the app is uninstalled.

---

## Related projects

This project depends on or bundles the following open-source work. Thanks to their authors.

| Project | Role | Links |
|---|---|---|
| Shizuku | Provides shell privileges used to change system settings | [GitHub](https://github.com/RikkaApps/Shizuku) · [Website](https://shizuku.rikka.app/) |
| GKD | Skips in-app ads via an accessibility service | [GitHub](https://github.com/gkd-kit/gkd) · [Website](https://gkd.li/) |
| StevenBlack/hosts | General-purpose ad host list | [GitHub](https://github.com/StevenBlack/hosts) |
| GOODBYEADS | Chinese ad host list | [GitHub](https://github.com/8680/GOODBYEADS) |
| @gkd-kit/subscription | Official GKD subscription | [GitHub](https://github.com/gkd-kit/subscription) |

---

## Building

No Gradle — the project calls the Android SDK toolchain directly.

### Requirements

- JDK 17
- Android SDK: build-tools 36.1.0, platform android-36
- Python 3.8+
- Dependency jars: run `python tools/fetch_libs.py` to fetch the Shizuku API

### Build

```bash
# first time: generate a signing key
python tools/make_keystore.py

# build, output at dist/托予净化-<version>.apk
python Makefile.py

# build and install to a connected device
python Makefile.py install
```

Pipeline: `aapt2 compile` → `aapt2 link` → `javac` → `d8` → `zipalign` → `apksigner`

### Versioning

The version lives in `version.txt` as `MAJOR.MINOR.PATCH`, each part capped at 9:

```
1.0.0 → 1.0.1 → ... → 1.0.9 → 1.1.0 → ... → 1.9.9 → 2.0.0
```

It bumps automatically after every successful build. `versionCode` is derived (`MAJOR*10000 + MINOR*100 + PATCH`) so it always increases.

Set it manually:

```bash
python tools/version.py set 1.2.0
python tools/version.py read
```

### Releasing

```bash
python tools/make_keystore.py     # first time
python Makefile.py                # builds dist/托予净化-x.y.z.apk
git tag -a vx.y.z -m "vx.y.z" && git push origin vx.y.z
python tools/release.py           # creates the release and uploads the APK
```

`tools/release.py` reads `version.txt`, uses the GitHub token stored in your git
credentials, creates the matching release (or reuses it), and uploads the APK as
`TuoYuClean-x.y.z.apk`.

> The ASCII asset name is deliberate: GitHub's upload endpoint drops non-ASCII
> characters from the URL, so a Chinese filename arrives as `-1.0.0.apk`.

GitHub recognises `.apk` assets automatically and shows the Android package icon.

### Layout

```
app/
  java/com/tuoyu/clean/
    MainActivity.java        two-page UI
    TuoYuClean.java          screen timeout read/write
    AdBlock.java             MIUI switches + verification probes
    AdBlockVpnService.java   local DNS filter (VpnService)
    AdHosts.java             blocklist + whitelist
    DnsPacket.java           DNS packet parse and reply
    GkdInstaller.java        GKD install
    ShizukuShell.java        Shizuku command execution
    ShizukuInstaller.java    bundled Shizuku install
    InstallProvider.java     hands bundled APKs to the installer
    EnvironmentCheck.java    ZhuoYiTong detection
    ShortcutActivity.java    headless launcher shortcuts
  assets/
    gkd.apk                  bundled GKD
    shizuku.apk              bundled Shizuku
    adhosts.txt              blocked domain list
tools/                       build and packaging scripts
version.txt                  current version
```

---

## License

The code in this project is released under the MIT license.

Bundled third-party components keep their own licenses:

- Shizuku: [Apache-2.0](https://github.com/RikkaApps/Shizuku/blob/master/LICENSE)
- GKD: [GPL-3.0](https://github.com/gkd-kit/gkd/blob/main/LICENSE)
- Domain lists: [StevenBlack/hosts](https://github.com/StevenBlack/hosts/blob/master/LICENSE), [GOODBYEADS](https://github.com/8680/GOODBYEADS)
