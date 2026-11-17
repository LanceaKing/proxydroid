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
* **Android NDK** 25.1.8937393 and **CMake** 3.22.1 (native code is built via `externalNativeBuild`)
* **Android Studio** or a standalone Gradle install (Android Gradle Plugin **8.1.4**)

Minimum supported Android version: **API 19** (Android 4.4).

## BUILD

From the repository root:

```bash
git submodule update --init --recursive
./gradlew assembleDebug
```

Release APK:

```bash
./gradlew assembleRelease
```

Ensure `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) points at your SDK if you build outside Android Studio.

### Shared redsocks engine

`app/src/main/cpp/redsocks` tracks the `feat/redsocks-upstream-tls` branch of
`LanceaKing/redsocks`. Its root-level `cntlm/` submodule tracks the patched
`feat/redsocks-embedded` branch of `LanceaKing/cntlm`, based on
`versat/cntlm` master `678d30b8ab4b0b2e3fb89a43bbeedd42d385e5f7`
(checked 2026-11-17, version 0.94.1). Both dependencies are pinned by Git commit;
use recursive submodule initialization when cloning or updating.

Gradle builds and packages `libredsocks.so` for ARMv7, ARM64, x86 and x86_64.
The App binds an ordinary libsu RootService, which calls the shared engine
through JNI. HTTPS profiles connect directly from redsocks to the upstream
proxy using TLS 1.2 or newer, SNI and system trust anchors with hostname/IP
verification. TLS connections use buffered I/O. The target site's original TLS
stream remains intact. CONNECT still uses visible target SNI when available.

NTLM uses cntlm's HTTP/CONNECT, authentication, connection cache, NoProxy,
ACL, additional Listen/SOCKS5/Tunnel ports, NTLMToBasic and ISA scanner modules
inside the same library and upstream connection. An optional `cntlmConfigPath`
profile setting selects an existing cntlm configuration file readable by the
App. Missing Proxy/Username/Domain/Password values fall back to profile values;
supplied hashes suppress fallback password injection. Process controls such as
PidFile/User/daemonization are rejected in the bound host. Platform-specific
GSS/Kerberos and Windows SSPI are outside the Android embedded target.

The root host installs its owned `PDROID_OUT` chain after listeners are ready,
removes its tagged OUTPUT jump before stopping, and marks its outbound sockets
to prevent loops. No executable redsocks/cntlm/gost asset refresh is needed.
System trust anchors are exported through a private descriptor; the profile's
existing `certificate` setting is not used as an upstream CA override.

OpenSSL 3.5.9 is built from its pinned source archive as a static PIC dependency.
For an offline build, provide the same verified archive with
`-PopensslArchive=file:///absolute/path/openssl-3.5.9.tar.gz`.
Native embedding and runnable CLI examples are in
[the engine guide](app/src/main/cpp/redsocks/docs/embedding.md).
After building, run `python3 scripts/verify-native-engine.py --output app/build/reports/native-engine.json`
to verify all four APK libraries, JNI exports, Android API levels, alignment and
system-only dynamic dependencies. The JSON records library hashes and each
repository's commit and working-tree digest; it identifies uncommitted builds.
Build and loopback protocol checks are separate from Android device acceptance;
[the older SNI validation notes](docs/http-connect-sni-validation.md) describe
an earlier executable build.

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
| `certificate`    | text    | Existing client certificate setting        |
| `cntlmConfigPath` | text    | Optional App-readable cntlm config file    |
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
