# Native dependency validation, 2026-11-19

cntlm, libevent and redsocks are independent sibling submodules. The checked
component worktrees are clean at these pins:

| Component | Commit | Source status |
|---|---|---|
| cntlm | `dc03f47e1e775f73be5c781d079b7efceadff3b5` | 0.94.1 baseline plus the audited embedding/platform/protocol fixes |
| libevent | `79ddfb460847999b807cba76d04e73891f29c6ee` | Official `release-2.1.13-stable`, no source patches |
| redsocks | `4396c6daf16ebbc50637f679fc04b9de520b4c4b` | Latest upstream baseline plus the audited engine integration |

Validation was completed before publishing the two fork commits. Hosted CI was
not part of this local validation.
Recursive checkout was verified with local URL rewrites, using an isolated
mirror of the official libevent release. Each cloned component's commit and
directory content digest matches the working repository.

`TLS_BACKEND=openssl` is the only selection supported by this pinned libevent.
OpenSSL remains 3.5.9, with the original archive URL, revision, Configure options
and SHA-256 `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`.
The historical TLS comparison Markdown/CSV files also match the pre-change
backup byte for byte.

## Native checks

Linux checks ran natively on ARM64 in a privileged Ubuntu 24.04 container,
with GCC 13.3.0, Clang 18.1.3, CMake 3.28.3 and pytest 7.4.4. Pinned builds
use libevent 2.1.13/OpenSSL 3.5.9; compatibility builds use installed libevent
2.1.12/OpenSSL 3.0.13. Both compiler builds enable ASan, UBSan and leak checks.
The OpenSSL-only Clang callback exclusion follows the dependency guide.

| Configuration | Result |
|---|---|
| Linux GCC, pinned dependencies | CTest 5/5; protocol/topology 180/180; strict timing 7/7 |
| Linux Clang, pinned dependencies | CTest 5/5; protocol/topology 180/180; initial timing 6/7; isolated nonce timing 1/1 |
| Linux GCC, system dependencies | CTest 3/3; parser/TCP/TLS/NTLM loopbacks 79/79 |
| Linux Clang, system dependencies | CTest 3/3; parser/TCP/TLS/NTLM loopbacks 79/79 |
| macOS ARM64, AppleClang 21.0.0, strict C99 | CTest 3/3; portable parser/protocol loopbacks 73/73 |
| Independent cntlm, macOS strict C99 | Core test 1/1 |
| Fresh recursive checkout, offline archive, macOS strict C99 | Build succeeded; core/TLS tests 2/2 |
| Python configuration/source identity/benchmark reporting checks | 12/12 on macOS and the Git-less Linux export |

The Clang timing run's nonce case returned the expected response, but one
sample took 746.897 ms against an expected 633.020 ms and the original ±40 ms
tolerance. This run overlapped other builds/tests. After those finished, the
same case passed alone with unchanged protocol source, workload and tolerance. The
initial failure and the isolated passing result are both retained. This
establishes passing coverage of all 187 case identities across the segmented
Clang runs; the initial timing outlier remains part of the evidence.

The macOS selection excludes six parameterized cases requiring Linux procfs:
repeated lifecycle/FD accounting, ACL cleanup, and the four TLS close-notify
cases. Linux runs cover them. The full topology suite also covers real
nginx/Squid/Dante/inetd services, authentication failures, kernel connect
timeouts, early disconnects, FD leaks and twenty accept-pressure limits.

Linux builds use a Git-less source export. Its final component digests match
the Git checkouts. The only files synchronized after the initial export were
redsocks' CI cntlm pin and the corresponding embedding documentation; embedded
identity strings were regenerated afterward. OpenSSL installation in the
container used a container-only `cp --reflink=never` wrapper after the overlay
filesystem produced an empty temporary archive during a reflink copy. No dependency
source patch was introduced for that environment issue.

## Android artifacts

Debug and release builds succeeded offline with JDK 17, NDK 25.1.8937393 and
SDK CMake 3.22.1. Both APKs pass `scripts/verify-native-engine.py` for
`armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64`, including JNI/engine exports,
API 19/21, system-only runtime dependencies, 16 KiB ELF alignment, the locked
TLS identity and all component content digests. All eight libraries contain
the final redsocks commit identity.

| APK | SHA-256 |
|---|---|
| `app/build/outputs/apk/debug/app-debug.apk` | `261397b1ffb8023f7e51d271938bee1c620703f7bc1bcb949764517aa9c12d9f` |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | `664c153288ccc64e384f6c33f18a2a84f863a327898c3612341ff103121795ff` |

Verifier reports are `app/build/reports/native-engine.json` and
`app/build/reports/native-engine-release.json`. Native JUnit reports, build
logs, source manifests and recursive-checkout evidence are under
`build/native-upstream-20261119` and `build/native-upstream-20261119-*.log`.
Linux reports include `linux-reports/{gcc,clang}-openssl/{core,protocol,timing}`
and the separate `clang-openssl/nonce-isolated` results. The initial working
tree backup remains at `build/native-upstream-backup-20261119-114451`.

These checks cover source/build identity and local protocol behavior. No APK
was installed, and no device profile, service, firewall, or live proxy
acceptance check was performed for this maintenance change. Reproduction
commands and the retained patch audit are in
[the dependency guide](native-dependencies.md).
