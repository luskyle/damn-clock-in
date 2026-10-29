#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LAB="${ANDROID_LAB_ROOT:-/media/luskyle/DATA/android-lab}"
export JAVA_HOME="${JAVA_HOME:-${LAB}/voice/tools/amazon-corretto-17.0.20.12.1-linux-x64}"
export ANDROID_HOME="${ANDROID_SDK_ROOT:-${LAB}/voice/tools/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
GRADLE="${GRADLE_BIN:-${LAB}/voice/tools/gradle-8.9/bin/gradle}"
[[ -x "${JAVA_HOME}/bin/java" && -x "$GRADLE" ]] || {
    printf 'error: Java/Gradle not found; set JAVA_HOME and GRADLE_BIN\n' >&2
    exit 1
}
if [[ $# == 0 ]]; then
    set -- :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
fi
exec "$GRADLE" -p "$ROOT" --offline --console=plain "$@"
