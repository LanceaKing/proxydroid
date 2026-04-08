## INTRO

Global Proxy App for Android System

ProxyDroid is distributed under GPLv3 with many other open source software,
here is a list of them:

 * cntlm - authentication proxy: http://cntlm.sourceforge.net/
 * redsocks - transparent socks redirector: http://darkk.net.ru/redsocks/
 * netfilter/iptables - NAT module: http://www.netfilter.org/
 * transproxy - transparent proxy for HTTP: http://transproxy.sourceforge.net/
 * stunnel - multiplatform SSL tunneling proxy: http://www.stunnel.org/

## REQUIREMENTS

* **JDK** 8 or newer (project uses Java 8 language level)
* **Android SDK** with API **29** (compile and target SDK)
* **Android NDK** and **CMake** 3.10.2+ (native code is built via `externalNativeBuild`)
* **Android Studio** or a standalone Gradle install (Android Gradle Plugin **8.1.4**)

Minimum supported Android version: **API 19** (Android 4.4).

## BUILD

From the repository root:

```bash
./gradlew assembleDebug
```

Release APK:

```bash
./gradlew assembleRelease
```

Ensure `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) points at your SDK if you build outside Android Studio.

## ADB shell content (automation / CLI)

The app exposes an exported `ContentProvider` (`org.proxydroid.cli`) so you can script profiles and start/stop the proxy from `adb` (or any tool that can talk to `content://` URIs). Package name: `org.proxydroid`.

**Base URI:** `content://org.proxydroid.cli/profiles`  
**Single profile:** `content://org.proxydroid.cli/profiles/<id>` (numeric row id, same as `_id`)

Because the provider is exported, any app or `adb` user on the device can read or change profiles and toggle the service. Use only on trusted devices.

### Control proxy (ContentProvider.call)

These map to `ProxyDroidCLI` methods `start`, `stop`, and `activate`:

```bash
# Start proxy using the currently active profile
adb shell content call --uri content://org.proxydroid.cli/profiles --method start

# Stop proxy service
adb shell content call --uri content://org.proxydroid.cli/profiles --method stop

# Set active profile by database row id (must exist), then optionally call start
adb shell content call --uri content://org.proxydroid.cli/profiles --method activate --arg 1
```

On multi-user devices you may need `--user 0` (or the target user id) on the `content` subcommand.

### List profiles

```bash
adb shell content query --uri content://org.proxydroid.cli/profiles
```

Optional projection (colon-separated column names):

```bash
adb shell content query --uri content://org.proxydroid.cli/profiles --projection _id:profileName:host:port:proxyType
```

### Insert / update / delete

`--bind` format: `column:TYPE:value` with `s` = string, `i` = integer (see `adb shell content help`).

Minimal insert example:

```bash
adb shell content insert --uri content://org.proxydroid.cli/profiles \
  --bind profileName:s:Office \
  --bind host:s:10.0.0.1 \
  --bind port:i:1080 \
  --bind proxyType:s:http
```

Update one row by id:

```bash
adb shell content update --uri content://org.proxydroid.cli/profiles/1 \
  --bind host:s:192.168.1.10 --bind port:i:3128
```

Delete one row:

```bash
adb shell content delete --uri content://org.proxydroid.cli/profiles/1
```

### Profile columns (SQLite / ContentValues)

| Column           | Type    | Notes                                      |
|------------------|---------|--------------------------------------------|
| `_id`            | integer | Auto; omit on insert                       |
| `profileName`    | text    | Required on insert                         |
| `host`           | text    |                                            |
| `port`           | integer | Default 3128 in schema                     |
| `proxyType`      | text    | e.g. `http` (default in schema)            |
| `bypassAddrs`    | text    |                                            |
| `user` / `password` | text |                                            |
| `certificate`    | text    |                                            |
| `proxiedApps`    | text    |                                            |
| `isAuth`         | integer | 0 or 1                                     |
| `isNTLM`         | integer | 0 or 1                                     |
| `isAutoConnect`  | integer | 0 or 1                                     |
| `isAutoSetProxy` | integer | 0 or 1                                     |
| `isBypassApps`   | integer | 0 or 1                                     |
| `isPAC`          | integer | 0 or 1                                     |
| `isDNSProxy`     | integer | 0 or 1                                     |
| `domain`         | text    |                                            |
| `ssid`           | text    |                                            |
| `excludedSsid`   | text    |                                            |

Starting the service still requires the same runtime conditions as the UI (e.g. root / VPN / per-app mode as configured); the CLI only triggers the same code paths as the app.
