# Native dependency maintenance and TLS evaluation

cntlm, libevent and redsocks are separate sibling Git submodules in
`app/src/main/cpp`. The parent gitlinks pin exact commits; `.gitmodules` URLs
and maintenance branches do not select versions during a build. Keep each
component's sources, licenses and tests in its own repository.

| Component | Upstream baseline | Local maintenance |
|---|---|---|
| libevent | Official `release-2.1.13-stable`, `79ddfb460847999b807cba76d04e73891f29c6ee` | No source patches or fork; use upstream CMake and generated headers |
| cntlm | Stable `0.94.1`, `8612658b0bd1facae7dfe6362e35649eb19992f8` | `LanceaKing/cntlm`; the imported upstream master `678d30b8ab4b0b2e3fb89a43bbeedd42d385e5f7` differs from that release only in CI workflows |
| redsocks | Latest upstream master checked 2026-11-19, `19b822e345f6a291f6cff6b168f1cfdfeeb2cd7d` | `LanceaKing/redsocks`; shared engine, required protocols and platform integration |
| OpenSSL | Existing locked `3.5.9` archive | Version, URL, SHA-256 and Configure options retained; no upgrade or source patch |

```sh
git clone --recurse-submodules https://github.com/LanceaKing/proxydroid.git
# Restore the recorded pins after updating an existing checkout:
git submodule sync --recursive
git submodule update --init --recursive
git submodule status --recursive
```

Prefer the latest stable upstream release before introducing a local repair.
A fork patch needs a concrete integration requirement or reproduced failure,
its smallest implementation, and a regression case. Remove it when upstream
provides the required behavior. Publish component commits before advancing the
parent gitlinks; do not replace pins with moving branches or nest dependencies
inside redsocks. `VENDOR.json` in the two forks records upstream/import
provenance. Duktape remains cntlm's 2.7.0 snapshot with the PAC repairs below.

## TLS build contract and offline archives

The only backend selector is CMake `TLS_BACKEND`, exposed as Gradle
`tlsBackend` and the tooling's `--tls-backend`/`--backends` options. Its values
follow the selected libevent release's native transports. Stable 2.1.13 offers
OpenSSL only, so `openssl` is the sole value and the default. Development
libevent's mbedTLS support is outside this pinned release. There are no custom
BoringSSL/wolfSSL backends or compatibility shims.

Unknown backends, the removed `PROXYDROID_TLS_BACKEND`, disabled OpenSSL,
incompatible libevent flags, missing submodules and missing/corrupt local TLS
archives fail configuration. Remote archives are checked against the same
locked SHA-256 when downloaded. There is no fallback to another backend or to
host libraries. libevent's OpenSSL discovery is scoped to an external CMake
bridge that reuses the selected imported OpenSSL targets; it never discovers
a second TLS installation. Native upstream TLS and all cntlm parent connections
link those same libraries. cntlm delegates transport to its host and has no
independent TLS selection. NTLM's legacy digest/DES code is not a TLS transport.

JDK 17, Python 3.9+, Perl, Make, Android NDK 25.1.8937393 and SDK CMake 3.22.1
are required for Android builds. The minimum remains API 19 for 32-bit ABIs
and API 21 for 64-bit ABIs. TLS below 1.2 stays disabled in the engine.

```sh
./gradlew :app:assembleDebug :app:assembleRelease
python3 scripts/verify-native-engine.py --output app/build/reports/native-engine.json
python3 scripts/verify-native-engine.py app/build/outputs/apk/release/app-release-unsigned.apk \
  --output app/build/reports/native-engine-release.json
# Explicit selection is equivalent to the default:
./gradlew :app:assembleDebug -PtlsBackend=openssl

# Cache the unchanged locked archive before going offline:
archive=$(python3 scripts/fetch-native-deps.py openssl)
./gradlew :app:assembleDebug --offline -PtlsArchive="file://$archive"
```

Populate Gradle/Maven and SDK caches as well before an offline APK build.
`--offline` only controls Gradle dependency resolution. Use a fresh native
build directory when migrating old backend caches; for Gradle, remove only the
obsolete backend's generated `.cxx` directory if it contains cached old flags.
libevent uses its upstream CMake probes and generates target-specific
configuration headers in each build directory. No source patching or target
program execution is needed for the Android cross build. A caller-side CMake
policy adjustment supports CMake 4 without modifying libevent.

## Retained patch audit

| Owner / source area | Required behavior | Regression evidence |
|---|---|---|
| libevent | Upstream stable APIs and native OpenSSL transport | Pristine gitlink; root `tls-contract` and protocol suite. Former custom build, RNG and non-native TLS patches removed |
| cntlm `embedded.h`, `main.c`, `socket.c`, `http.c` | Host-owned lifecycle, logging/failure callbacks and one buffered TLS stream; no daemon or process exit in an embedding host | Core test host stubs; engine lifecycle, stop-during-handshake, NTLM dialect and extra-service tests |
| cntlm `ntlm.c`, `utils.h`, `xcrypt.c` | Bounds-check Type2 security buffers/AV pairs, support unaligned packets, avoid signed DES shifts | `test_challenge_bounds`, `test_des_vector`, malformed-Type2 protocol cases and ASan/UBSan |
| cntlm `proxy.c`, `forward.c`, `auth.c`, `utils.c` | Restart Type1 after a closed 407, authenticate HTTP with bodyless probes, never replay a submitted POST, reset parent/cache and credential ownership | Closed-407, no-auth probe, cached-stream/no-replay and repeated-lifecycle tests |
| cntlm `config.c`, `config.h` | Escaped/final-line parsing and borrowed descriptor ownership without procfs | `test_configuration`, `test_descriptor_configuration` |
| cntlm `pac.c`, `duktape/duktape.c` | Protected PAC evaluation, owned result strings, defined empty-buffer/property arithmetic | `test_pac_errors`, `test_pac_empty_storage` under ASan/UBSan |
| cntlm `CMakeLists.txt`, `pac.c` | Target libc probes, strict C99/macOS declarations and Android API 19/21 interface lookup | Strict C99 core build; four-ABI ELF checks reject unavailable imports |
| redsocks `engine.c`, `jni.c`, `cntlm-embed.c`, `cntlm-io.c` | Shared C/JNI engine with listeners/threads owned by the host, verified parent TLS and repeatable shutdown | Lifecycle, port-conflict, TLS trust/failure, every NTLM dialect, extra listeners, direct routes and ACL tests |
| redsocks `http-connect.c`, `tls-sni.c` | Hostname CONNECT from bounded SNI inspection with original bytes preserved, bounded fallback and authentication | Parser vectors and `test_http_connect_sni.py` for buffer/splice pumps |
| redsocks relay modules, `utils.c` | Public libevent 2.x buffer/callback/watermark/descriptor APIs; preserve backpressure and descriptor accounting | Full protocol/topology suite with both pumps; upstream libevent >= 2.1.1 compatibility build |
| redsocks EOF/shutdown, `event-cleanup.h`, cntlm readiness | Drain TLS input/output before half-close, make shutdown idempotent, release deferred callbacks and yield between readiness dispatches | TLS 1.2/1.3 close-notify/NTLM response tests, `redsocks-tls-half-close`, `redsocks-deferred-free`, FD/lifecycle regressions |
| redsocks platform adapters | API 19 splice syscall, optional keepalive controls and macOS buffered forwarding/signal wait | Four Android ABI builds/ELF checks and macOS TCP/TLS/NTLM loopback tests |

Feature macros and cntlm's forced `embedded.h` stay scoped to cntlm and its
adapter object. They must not redirect libc calls in the rest of redsocks.
No protocol-code change is retained solely as a speculative improvement.

## Native tests and provenance

```sh
archive=$(python3 scripts/fetch-native-deps.py openssl)
cmake -S app/src/main/cpp -B build/native-openssl -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DTLS_BACKEND=openssl \
  -DPROXYDROID_TLS_ARCHIVE="file://$archive" -DCNTLM_BUILD_TESTS=ON
cmake --build build/native-openssl --parallel 4
ctest --test-dir build/native-openssl --output-on-failure
python3 -m unittest discover -s scripts/tests -v

# Linux full namespace/protocol suite (see .github/workflows/native.yml):
REDSOCKS_BINARY="$PWD/build/native-openssl/redsocks/redsocks" \
  app/src/main/cpp/redsocks/tests/run

# Independent component compatibility build against installed libevent/OpenSSL:
cmake -S app/src/main/cpp/redsocks -B build/native-system -G Ninja \
  -DCMAKE_BUILD_TYPE=Debug -DCNTLM_BUILD_TESTS=ON
cmake --build build/native-system --parallel 4
ctest --test-dir build/native-system --output-on-failure
```

The root workflow runs Linux GCC/Clang ASan+UBSan for both pinned and system
builds, macOS strict C99/core/loopback tests, and Android debug/release builds
for all four ABIs. The full Linux suite needs root and network namespace,
iptables, scheduling and netem privileges plus nginx, Squid, Dante and xinetd.
Its timerfd scheduling helpers retain the caller's CPU mask and are stopped
when shaping ends. The component repositories retain independent workflows.
On macOS the generic TCP engine uses buffered forwarding; splice, redudp and
dnsu2t remain Linux/Android modules.

Sanitized OpenSSL builds keep upstream `-DPEDANTIC`. Only Clang's function-type
check is excluded inside downloaded OpenSSL because of its generic stack/lhash
callback casts; ASan and the remaining UBSan checks remain enabled there. The
full sanitizer set remains on redsocks, cntlm, libevent and benchmark drivers.
Effective OpenSSL flags participate in the TLS configuration checksum.

APK report schema 2 records component commits, directory-scoped worktree
digests, Duktape provenance, TLS archive/configuration hashes and each ABI's
ELF identity. The verifier rejects stale source markers, unexpected runtime
libraries, JNI/API mismatches and sub-16-KiB load alignment. Hashes cover file
names, contents, executable modes and symlinks while excluding build outputs;
Git-less source exports remain buildable. These are source/build and local
protocol checks, separate from installed Android service or live upstream
acceptance. No new device benchmark is required for this maintenance refactor.

Local [2026-11-19 validation evidence](native-dependencies-validation-20261119.md)
records the exact component pins, compiler/test coverage, recursive checkout,
offline builds and verified debug/release APK hashes, including the retained
Clang timing outlier and its isolated rerun.

## Controlled device benchmark

The [2026-11-19 Pixel 5 comparison](tls-backend-comparison-20261119.md) records
840 accepted loopback samples, the TCP controls and an isolated socket-option
diagnostic. Its Markdown/CSV files are unchanged historical results for older
custom backend variants. They do not describe the current build options.
The current runner measures the OpenSSL backend supported by pinned libevent;
a single backend cannot produce a formal backend comparison. The reference
continues to use OpenSSL.

The reference server is an echo proxy for a controlled LAN, with public test
credentials `Domain/User/Password`. It does not forward arbitrary targets.
On the reference host, install OpenSSL development files and start:

```sh
python3 scripts/benchmark-tls.py server --bind 0.0.0.0 --base-port 9443
```

It generates a two-day test CA/server certificate under
`build/benchmark-server/pki`, then opens ports 9443–9450. The four TLS/cipher
pairs are TLS 1.2 AES-GCM, TLS 1.2 ChaCha20, TLS 1.3 AES-GCM and TLS 1.3
ChaCha20, each with an unauthenticated/NTLM port pair. All use RSA-2048 and
X25519. Copy only `ca.pem` to the runner machine if it differs from the server.
Use `--openssl-root` if server CMake cannot find OpenSSL.
The generated CA includes critical CA/key-usage extensions and is checked with
strict certificate and hostname verification before reuse.

Replace the example LAN address with the reachable reference host:

```sh
# Short correctness smoke. This is not a five-round performance conclusion.
python3 scripts/benchmark-tls.py run --serial 06021FDD4000D2 \
  --server 192.168.1.10 --ca build/benchmark-server/pki/ca.pem \
  --rounds 1 --seconds 2 --warmup 1 --iterations 5 --concurrency 1 \
  --output build/tls-smoke

# Complete supported-backend measurement; allow several hours for all workloads.
python3 scripts/benchmark-tls.py run --serial 06021FDD4000D2 \
  --server 192.168.1.10 --ca build/benchmark-server/pki/ca.pem \
  --output build/tls-evaluation
```

When the device can reach only one LAN port, run the reference on the runner's
own host and let the runner restart each TLS/auth profile on that same port:

```sh
python3 scripts/benchmark-tls.py server --prepare-only --directory build/lan-reference
python3 scripts/benchmark-tls.py run --serial 06021FDD4000D2 \
  --server 192.168.1.10 --base-port 8888 \
  --ca build/lan-reference/pki/ca.pem \
  --reference-server build/lan-reference/cmake/tls-bench-server \
  --output build/tls-lan-evaluation
```

Each profile still fixes the same TLS version, cipher, X25519 group,
certificate and authentication. The runner owns and stops its reference
process, records its binary hash, profile starts and per-sample CPU/RSS,
and leaves other listeners alone. Both modes retain exact engine/driver
copies under `artifacts`; `--native-directory` can reuse a separate build
cache without reusing earlier measurements. Reports are checkpointed after
each complete round.

For a comparison entirely on one Android device, generate the same test PKI,
then run a fixed OpenSSL reference on that device's IPv4 loopback:

```sh
python3 scripts/benchmark-tls.py server --prepare-only --directory build/device-reference
python3 scripts/benchmark-tls.py run --serial 06021FDD4000D2 \
  --server 127.0.0.1 --transport loopback --base-port 8888 \
  --ca build/device-reference/pki/ca.pem \
  --output build/tls-device-evaluation
```

The OpenSSL build also produces the Android reference executable; its exact
copy is retained in `artifacts/tls-bench-server`. To reuse an already built
Android reference with a restricted backend list, pass `--reference-server`.
The runner uploads the test server key only into its unique temporary
directory with mode 600, binds to loopback, verifies process ownership before
stopping it, and removes that directory afterward. Reference CPU/RSS is
recorded separately using that process's own clock-tick and page sizes.
Both endpoints share device CPU and thermal capacity: throughput describes
this complete loopback workload, and requires a reference-capacity check
before being interpreted as a client limit. It is reported as
`controlled_loopback`, never as a formal LAN result. All normal minimum
durations, workload coverage, accepted rounds and thermal gates still apply.
Optional `--engine-cpu-mask`, `--client-cpu-mask` and `--reference-cpu-mask`
take hexadecimal CPU affinity masks and apply only to benchmark processes.
Choose disjoint masks from the device's measured topology. For example, on
the eight-core Pixel 5, `--engine-cpu-mask 20 --client-cpu-mask 10
--reference-cpu-mask f` assigns the engine/TLS harness to CPU 5, the load
generator to CPU 4, and the reference to CPUs 0–3, as in the measured comparison.
All threads inherit
their process's assigned mask. The masks are retained in the report, and an
affinity failure invalidates the run. The loopback runner allows up to ten
minutes of idle cooling before a sample, retaining the requirement for
thermal status zero in the controlled result.

The runner builds matching optimized `RelWithDebInfo` drivers by default,
warms each case across five rounds, and measures
handshake/resumption, short requests and bidirectional buffered forwarding
with none/NTLM authentication at concurrency 1/16/64. The isolated handshake and
resumption harness runs sequentially at concurrency 1. `--modes`, `--versions`,
`--ciphers`, `--auth` and `--concurrency` can restrict a matrix. Reports retain
the exact parameters; a restricted matrix never implies unmeasured coverage.

Forwarding uses the real shared engine in an isolated child process, whose
CPU/RSS excludes the client load generator. Payloads are verified byte for
byte; errors or incomplete streams invalidate a sample. Resumption belongs
to the isolated TLS harness and must actually report reused sessions; the
product's session-caching behavior is unchanged. The harness constrains client
groups, while the forwarding case retains the engine's actual ClientHello.

Each forwarding stream has a fixed 256 KiB window of sent data awaiting its
verified echo. Nonblocking reads and writes stop new sends at the deadline,
then drain and check the complete stream. This prevents a short 64-connection
USB run from queuing hundreds of MiB before TLS setup finishes and starving
other streams past the unchanged 10-second I/O timeout. Raw results retain
`stream_window_bytes` and `peak_inflight_bytes`; artifact records also hash the
driver. Compare forwarding measurements made with the same workload window.

`samples.jsonl`, `report.json` and `report.md` include P50/P95 latency,
bidirectional throughput, CPU seconds/GiB, peak RSS, stripped engine sizes,
source/build identities and thermal status. Throttled samples are retained,
rejected and retried. Unknown thermal status prevents a formal result. The
runner retains failed warmup/measurement samples and stops with an incomplete
report when payload, transport or TLS errors occur. Such failures never become
accepted performance samples. The
runner cannot establish that the server or link has spare capacity: verify
that separately before interpreting a throughput plateau. Optional
`--apk-directory` records the supplied `openssl.apk`
sizes and hashes; compare identical APK variants and their verifier reports.
Forwarding does not sample request latency; its Markdown latency cells are
marked `n/a`. The standalone driver's `network` mode bypasses the engine/TLS
and uses the same verified echo workload, 256 KiB window and 10-second timeout
against the reference executable's `network` profile. Keep these TCP capacity
controls separate from backend measurements; their CPU/RSS includes the load
generator.

If LAN reachability is unavailable, an explicitly managed `adb reverse` tunnel
can exercise correctness with `--server 127.0.0.1 --transport adb-reverse`.
Remove only the tunnel ports created for that run afterward. These results are
always marked nonformal. Controlled LAN/loopback status requires all supported
backends and default workloads, five accepted rounds, the default minimum
durations and known unthrottled thermal status; restricted runs remain smoke
measurements. Formal backend comparison status additionally requires multiple
supported backends and stays false for this pinned release.

The runner uses a unique temporary directory and borrowed unlinked config
descriptors. It does not install an APK or change app profiles, services,
firewall rules or system trust. Native/device benchmark evidence is separate
from actual selected-app traffic acceptance and API 19 runtime verification.
The benchmark evaluates explicit backend variants without changing the default build selection.
