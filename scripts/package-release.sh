#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
VERSION="$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts)"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
    printf 'error: invalid app version\n' >&2
    exit 1
}
TEST_APK="app/build/outputs/apk/debug/app-debug.apk"
UNSIGNED_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -f "$TEST_APK" && -f "$UNSIGNED_APK" ]] || {
    printf 'error: build assembleDebug and assembleRelease before packaging\n' >&2
    exit 1
}
DEST="dist"
mkdir -p "$DEST"
TEST_NAME="yongbu-chidao-${VERSION}-test.apk"
UNSIGNED_NAME="yongbu-chidao-${VERSION}-unsigned.apk"
cp "$TEST_APK" "$DEST/$TEST_NAME"
cp "$UNSIGNED_APK" "$DEST/$UNSIGNED_NAME"
cp docs/RELEASE.md "$DEST/INSTALL.md"
(
    cd "$DEST"
    sha256sum "$TEST_NAME" "$UNSIGNED_NAME" INSTALL.md > SHA256SUMS
)
printf 'Delivery ready: %s/dist\n' "$ROOT"
