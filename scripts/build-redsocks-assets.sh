#!/bin/sh
# Rebuild all supported ABIs and refresh the two executable assets used at runtime.
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/Library/Android/sdk"}}
ndk="$sdk/ndk/${REDSOCKS_NDK_VERSION:-25.1.8937393}"
cmake="$sdk/cmake/${REDSOCKS_CMAKE_VERSION:-3.10.2.4988404}/bin/cmake"
ninja="$(dirname -- "$cmake")/ninja"
strip="$ndk/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin/llvm-strip"
source_dir="$root/app/src/main/cpp/redsocks"
build_root="$root/build/redsocks-assets"

if ! test -f "$source_dir/tls-sni.c"; then
    echo 'Initialize the redsocks submodule: git submodule update --init --recursive' >&2
    exit 1
fi
for abi in armeabi-v7a arm64-v8a x86 x86_64; do
    api=19
    case "$abi" in arm64-v8a|x86_64) api=21 ;; esac
    build="$build_root/$abi"
    mkdir -p "$build"
    (cd "$build" && "$cmake" -G Ninja \
        -DCMAKE_MAKE_PROGRAM="$ninja" \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM="android-$api" \
        -DCMAKE_BUILD_TYPE=Release "$root/app/src/main/cpp")
    "$cmake" --build "$build" --target redsocks -- -j "${REDSOCKS_BUILD_JOBS:-4}"
done

# Publish assets only once every ABI has compiled successfully.
for abi in armeabi-v7a x86; do
    destination="$root/app/src/main/assets/$abi/redsocks"
    "$strip" --strip-unneeded -o "$destination.tmp" "$build_root/$abi/redsocks/redsocks"
    chmod 644 "$destination.tmp"
    mv "$destination.tmp" "$destination"
done
python3 - "$root" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1])
source = root / 'app/src/main/cpp/redsocks'
commit = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
dirty = bool(subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain', '--untracked-files=no']))
assets = root / 'app/src/main/assets'
manifest = {
    'repository': 'https://github.com/LanceaKing/redsocks',
    'commit': commit,
    'dirty': dirty,
    'ndk': os.environ.get('REDSOCKS_NDK_VERSION', '25.1.8937393'),
    'sha256': {abi: hashlib.sha256((assets / abi / 'redsocks').read_bytes()).hexdigest()
               for abi in ('armeabi-v7a', 'x86')},
}
(assets / 'redsocks-build.json').write_text(json.dumps(manifest, indent=2) + '\n')
print(json.dumps(manifest, indent=2))
PY
