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

* **JDK** 17 (Android Gradle Plugin requirement; source language level is Java 8)
* **Android SDK** with API **29** (compile and target SDK)
* **Android NDK** and **CMake** 3.10.2+ (native code is built via `externalNativeBuild`)
* **Android Studio** or a standalone Gradle install (Android Gradle Plugin **8.1.4**)

Minimum supported Android version: **API 19** (Android 4.4).

## BUILD

From the repository root:

```bash
git submodule update --init --recursive
scripts/build-redsocks-assets.sh
./gradlew assembleDebug
```

Release APK:

```bash
./gradlew assembleRelease
```

Ensure `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) points at your SDK if you build outside Android Studio.

### redsocks fork and executable assets

`app/src/main/cpp/redsocks` is a submodule of
[LanceaKing/redsocks](https://github.com/LanceaKing/redsocks/tree/master),
pinned to the tested SNI fix on the fork's `master`. The fork is based on
`darkk/redsocks`; run its complete suite before updating the submodule pin.
Clone with `git clone --recurse-submodules` or initialize it with the command above.

Run `scripts/build-redsocks-assets.sh` after changing the submodule. It uses NDK
`25.1.8937393` and SDK CMake `3.10.2.4988404` by default (override with
`REDSOCKS_NDK_VERSION` / `REDSOCKS_CMAKE_VERSION`). It builds ARMv7, ARM64, x86,
and x86_64, strips and refreshes the ARMv7/x86 assets used by the app, and writes
their commit and SHA-256 to `assets/redsocks-build.json`. Then build the APK.
An ordinary Gradle native build alone does not refresh these executable assets.

Before each service start, including the ContentProvider CLI, the app compares
the bundled and extracted redsocks hashes. Changed or missing executables are
replaced using a temporary file and atomic rename. Deployment failure stops
startup. Existing profiles and credentials need no migration.

HTTP CONNECT automatically uses visible TLS SNI with the original port. The
original TLS bytes are preserved, and Basic/Digest authentication keeps the same
target across 407 retries. Missing/invalid SNI, non-TLS traffic, a 64 KiB limit,
or a one-second deadline fall back to IP CONNECT through the configured proxy.
An upstream that rejects IP CONNECT will still reject that fallback. ECH's hidden
inner hostname and QUIC/UDP are outside this TCP fix. This change does not add an
upstream startup health check; the running indicator is not an end-to-end probe.

The fork's `All tests` GitHub Actions workflow runs the original upstream tests,
CONNECT regressions and C parser checks through the same pytest framework and
`tests/run` entry point, in one test step per compiler. It uses native Ubuntu
24.04 with GCC, Clang, ASan and UBSan; Docker is not required. Full local test
commands and dependencies are in the submodule README.

Build, CI and selected-app acceptance evidence is recorded in
[the validation notes](docs/http-connect-sni-validation.md).

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

`--bind` format: `column:TYPE:value` with `s` = string, `i` = integer, `b` = boolean (see `adb shell content help`). Boolean profile columns accept `b:true` / `b:false` or `i:1` / `i:0`.

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
| `isAuth`         | boolean | SQLite stores 0/1; bind as `b:` or `i:`    |
| `isNTLM`         | boolean | same                                       |
| `isAutoConnect`  | boolean | same                                       |
| `isAutoSetProxy` | boolean | same                                       |
| `isBypassApps`   | boolean | same                                       |
| `isPAC`          | boolean | same                                       |
| `isDNSProxy`     | boolean | same                                       |
| `domain`         | text    |                                            |
| `ssid`           | text    |                                            |
| `excludedSsid`   | text    |                                            |

Starting the service still requires the same runtime conditions as the UI (e.g. root / VPN / per-app mode as configured); the CLI only triggers the same code paths as the app.
