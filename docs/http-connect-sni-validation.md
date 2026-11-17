# HTTP CONNECT SNI validation

Date: 2026-11-17.

The redsocks fork is `LanceaKing/redsocks`; its `master` includes the SNI fix based
on upstream master `19b822e345f6a291f6cff6b168f1cfdfeeb2cd7d`. ProxyDroid pins
the fork as a submodule; `app/src/main/assets/redsocks-build.json` records the
exact commit and the hashes of the executable assets.

## Complete automated suite

The [All tests run](https://github.com/LanceaKing/redsocks/actions/runs/44013060865)
tested commit `d4dca32d126729656ce62d683698f15c20da5b84`. Both GCC and Clang
passed all 143 tests, with zero failures, errors or skipped cases in each JUnit
report. Each complete test step took about nine minutes.

Each GCC/Clang job uses native Ubuntu 24.04 with ASan and UBSan. One
`Run all tests` step calls `tests/run`, which collects all 143 cases with pytest:

- 108 original upstream automated cases, including authentication, refusal,
  timeout, latency, nonce reuse, client cancellation, FD leaks and accept overflow.
- 34 CONNECT cases covering both buffer and splice pumps, fragmentation,
  Basic/Digest retries, fallback, response rejection and half-close behavior.
- The C parser executable, compiled and run by pytest, with 21,281 checks and
  20,000 deterministic malformed inputs.

There is no separate SNI test runner or CI step. The real nginx, Squid, Dante
and xinetd services run in temporary Linux network namespaces, without Docker,
external proxy credentials or secrets. Each compiler produces one JUnit report.

## Android build and deployment

`scripts/build-redsocks-assets.sh` built ARMv7, ARM64, x86 and x86_64 with NDK
25.1.8937393. `:app:assembleDebug` and `:app:lintDebug` passed with JDK 17.
The packaged ARMv7/x86 assets and embedded manifest match their source hashes.
The APK signing certificate matches the previously installed application.

The final APK was upgraded in place on the device. The extracted and running
`/proc/<pid>/exe` hashes match the ARMv7 asset. Its version reports
`redsocks.git/d4dca32d1267-http-connect-sni`. All three complete profile rows,
including credentials, and all static application settings match their
pre-upgrade values. The runtime configuration uses normal logging again.

APK SHA-256:
`fd101abfafee5e2572ed17f0571cf2791af2a71191b0748dec48cf7cd5910a62`.

The installer was exercised through the ContentProvider CLI: a missing binary
was restored, and a directory blocking the executable path caused startup to
fail with the running flag clear. Both test conditions were restored afterward.

## Selected-app acceptance

On the specified rooted Pixel 5 (Android 14), Profile 3 retained its existing
settings and credentials. Temu was restarted to open fresh sockets. Sampling
its UID and matching client source ports to redsocks logs confirmed 49 selected
connections using SNI hostname CONNECT with upstream status 200.

The private upstream capture contained 50 CONNECT exchanges: 49 returned 200,
and 41 completed a ServerHello followed by encrypted TLS records within the
capture. Successful hosts included `sg.temu.com`, `gslb.temu.com`,
`sg-ds.temu.com` and `img-2.kwcdn.com`. The Temu homepage displayed products,
prices and images. `www.googleadservices.com` still received provider status
403 despite using a hostname.

The capture used native commit `884c3fc0b7fdd4fc348c8a16a532b724fc6df3f5`.
The later pinned commit changes only tests, CI and documentation; production
C sources, headers and CMake files are identical. Temporary diagnostic logging
and task-owned capture files on the device were removed. Raw captures,
credentials and profile backups remain private and are excluded from git.

Visible TLS SNI is required for hostname recovery. Missing or invalid SNI,
non-TLS traffic, the 64 KiB cap and the one-second deadline retain IP CONNECT
fallback. ECH's hidden inner name and QUIC are outside this TCP change.
