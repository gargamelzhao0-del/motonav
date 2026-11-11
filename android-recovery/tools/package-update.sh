#!/bin/sh
set -eu

if [ "$#" -lt 2 ] || [ "$#" -gt 3 ]; then
    echo "Usage: sh tools/package-update.sh <unsigned-apk> <output-dir> [expected-tag]" >&2
    exit 2
fi

: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK directory}"
: "${MOTONAV_KEYSTORE:?Set MOTONAV_KEYSTORE to the release keystore path}"
: "${MOTONAV_KEY_ALIAS:?Set MOTONAV_KEY_ALIAS}"
: "${MOTONAV_KEYSTORE_PASSWORD:?Set MOTONAV_KEYSTORE_PASSWORD}"
: "${MOTONAV_KEY_PASSWORD:?Set MOTONAV_KEY_PASSWORD}"

build_tools=$(find "$ANDROID_HOME/build-tools" -name apksigner -type f | sort -V | tail -n 1)
if [ -z "$build_tools" ]; then
    echo "Android build-tools with apksigner are required" >&2
    exit 1
fi
build_tools=$(dirname "$build_tools")
script_dir=$(dirname "$0")
scratch=$(mktemp -d)
trap 'rm -f "$scratch/aligned.apk" "$scratch/signed.apk" "$scratch/signed.apk.idsig"; rmdir "$scratch"' EXIT

"$build_tools/zipalign" -f 4 "$1" "$scratch/aligned.apk"
"$build_tools/apksigner" sign --ks "$MOTONAV_KEYSTORE" --ks-key-alias "$MOTONAV_KEY_ALIAS" \
    --ks-pass env:MOTONAV_KEYSTORE_PASSWORD --key-pass env:MOTONAV_KEY_PASSWORD \
    --out "$scratch/signed.apk" "$scratch/aligned.apk"

python3 "$script_dir/update_release.py" prepare --apk "$scratch/signed.apk" \
    --build-tools "$build_tools" --output "$2" --tag "${3:-}"
