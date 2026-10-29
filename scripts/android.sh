#!/usr/bin/env bash
set -euo pipefail

die() {
    printf 'error: %s\n' "$*" >&2
    exit 1
}

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
LAB="${ANDROID_LAB_ROOT:-/media/luskyle/DATA/android-lab}"
SDK="${ANDROID_SDK_ROOT:-${LAB}/voice/tools/android-sdk}"
EMULATOR="${ANDROID_EMULATOR_BIN:-${LAB}/voice/tools/emulator-audio-36.1.9/emulator/emulator}"
AVD="${ANDROID_AVD_NAME:-DamnClockIn_Google_API35}"
AVD_PATH="${ANDROID_AVD_PATH:-${LAB}/${AVD}.avd}"
ADB="${SDK}/platform-tools/adb"
APK="${BOE_APK:-${ROOT}/BOEYiDongMenHu.apk}"
PACKAGE=com.mobile.boe
ACTIVITY="${PACKAGE}/com.meicloud.start.activity.SplashActivity"
ACTION="${1:-help}"

[[ $# -le 1 ]] || die "expected at most one action"
case "$ACTION" in
    help|--help|-h)
        cat <<'EOF'
Usage: bash scripts/android.sh init|start|install|launch|status|helper-install|helper-open|stop

  init     Create the project AVD from the already installed Google APIs image.
  start    Open its standalone emulator window in the foreground; preserve data.
  install  Install/update the local APK, preserving app data.
  launch   Launch the app and check that it stays alive.
  status   Show the project's device, ARM bridge and installed app.
  helper-install  Install/update the locally built navigation-test helper.
  helper-open     Open the helper panel (does not start navigation or a timer).
  stop     Close only the project AVD, preserving its data.

Overrides: ANDROID_LAB_ROOT, ANDROID_SDK_ROOT, ANDROID_EMULATOR_BIN,
ANDROID_AVD_NAME, ANDROID_AVD_PATH, JAVA_HOME, BOE_APK.
No downloads, factory resets, automatic login or permission acceptance.
EOF
        exit 0
        ;;
    init|start|install|launch|status|helper-install|helper-open|stop) ;;
    *) die "unknown action: $ACTION (use help)" ;;
esac

[[ -x "$ADB" ]] || die "SDK adb not found: $ADB"
[[ -x "$EMULATOR" ]] || die "emulator not found: $EMULATOR"
export ANDROID_SDK_ROOT="$SDK"

avd_exists() {
    "$EMULATOR" -list-avds | grep -Fxq "$AVD"
}

if [[ "$ACTION" == init ]]; then
    if avd_exists; then
        printf 'AVD already exists: %s (data preserved).\n' "$AVD"
        exit 0
    fi
    MANAGER="${SDK}/cmdline-tools/latest/bin/avdmanager"
    [[ -x "$MANAGER" ]] || die "avdmanager not found: $MANAGER"
    [[ -f "${SDK}/system-images/android-35/google_apis/x86_64/package.xml" ]] ||
        die "install system-images;android-35;google_apis;x86_64 first"
    export JAVA_HOME="${JAVA_HOME:-${LAB}/voice/tools/amazon-corretto-17.0.20.12.1-linux-x64}"
    [[ -x "${JAVA_HOME}/bin/java" ]] || die "set JAVA_HOME to Java 17 or newer"
    printf 'no\n' | "$MANAGER" create avd --name "$AVD" \
        --package 'system-images;android-35;google_apis;x86_64' \
        --device pixel_5 --path "$AVD_PATH"
    exit 0
fi

SERIAL=""
DEVICES="$("$ADB" devices)"
while read -r candidate state _; do
    [[ "$candidate" == emulator-* && "$state" == device ]] || continue
    NAME="$("$ADB" -s "$candidate" emu avd name)"
    NAME="${NAME%%$'\n'*}"
    NAME="${NAME%$'\r'}"
    if [[ "$NAME" == "$AVD" ]]; then
        [[ -z "$SERIAL" ]] || die "multiple running devices for AVD: $AVD"
        SERIAL="$candidate"
    fi
done <<< "$DEVICES"

if [[ "$ACTION" == start ]]; then
    if [[ -n "$SERIAL" ]]; then
        printf '%s is already running on %s.\n' "$AVD" "$SERIAL"
        exit 0
    fi
    avd_exists || die "AVD not found: $AVD; run init first"
    # Keep host ROS/Flutter libraries out of the emulator's Qt/graphics loader.
    exec env -u LD_LIBRARY_PATH -u QT_IM_MODULE "$EMULATOR" \
        -avd "$AVD" -gpu swiftshader_indirect -feature -Vulkan \
        -memory 4096 -cores 4 -no-snapshot-load -no-boot-anim
fi

[[ -n "$SERIAL" ]] || die "project AVD is not connected; run start and wait for boot"
if [[ "$ACTION" == stop ]]; then
    exec "$ADB" -s "$SERIAL" emu kill
fi

BOOT="$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed)"
[[ "${BOOT%$'\r'}" == 1 ]] || die "Android has not finished booting"
BRIDGE="$("$ADB" -s "$SERIAL" shell getprop ro.dalvik.vm.native.bridge)"
[[ -n "$BRIDGE" && "${BRIDGE%$'\r'}" != 0 ]] ||
    die "ARM native-code translation is unavailable"

case "$ACTION" in
    helper-install)
        HELPER="${ROOT}/app/build/outputs/apk/debug/app-debug.apk"
        [[ -f "$HELPER" ]] || die "helper APK not found; run bash scripts/build-android.sh first"
        exec "$ADB" -s "$SERIAL" install -r "$HELPER"
        ;;
    helper-open)
        exec "$ADB" -s "$SERIAL" shell am start --activity-clear-top \
            -n com.luskyle.clockintest/.MainActivity
        ;;
    install)
        [[ -f "$APK" ]] || die "APK not found: $APK"
        exec "$ADB" -s "$SERIAL" install -r "$APK"
        ;;
    launch)
        "$ADB" -s "$SERIAL" shell input keyevent KEYCODE_WAKEUP
        "$ADB" -s "$SERIAL" shell wm dismiss-keyguard
        RESULT="$("$ADB" -s "$SERIAL" shell am start -W -n "$ACTIVITY")"
        printf '%s\n' "$RESULT"
        grep -q '^Status: ok' <<< "$RESULT" || die "activity launch failed"
        sleep 5
        "$ADB" -s "$SERIAL" shell pidof "$PACKAGE" ||
            die "app exited; inspect adb -s $SERIAL logcat -b crash -d"
        FOCUSED="$("$ADB" -s "$SERIAL" shell dumpsys activity activities |
            grep -E 'topResumedActivity|mResumedActivity')"
        printf '%s\n' "$FOCUSED"
        grep -Fq "${PACKAGE}/" <<< "$FOCUSED" ||
            die "app is alive but is not the resumed activity"
        ;;
    status)
        printf 'AVD: %s\nDevice: %s\nARM bridge: %s\n' "$AVD" "$SERIAL" "$BRIDGE"
        INSTALLED="$("$ADB" -s "$SERIAL" shell pm path "$PACKAGE")"
        [[ "$INSTALLED" == package:* ]] || die "app not installed; run install"
        printf '%s\n' "$INSTALLED"
        "$ADB" -s "$SERIAL" shell dumpsys package "$PACKAGE" |
            grep -E 'versionCode=|versionName=|primaryCpuAbi='
        ;;
esac
