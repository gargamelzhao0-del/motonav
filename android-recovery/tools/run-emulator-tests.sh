#!/bin/bash
# Only the disposable AVD created by android-emulator-runner. Never use an arbitrary connected device.
set -euo pipefail
cd "$(dirname "$0")/.."
serial="emulator-${EMULATOR_PORT:-5554}"
mkdir -p emulator-evidence
test "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" = 1
adb -s "$serial" shell getprop ro.build.fingerprint > emulator-evidence/runtime.txt
adb -s "$serial" shell getprop ro.product.cpu.abilist >> emulator-evidence/runtime.txt
adb -s "$serial" shell getprop ro.hardware >> emulator-evidence/runtime.txt
adb -s "$serial" shell getprop ro.dalvik.vm.native.bridge >> emulator-evidence/runtime.txt
adb -s "$serial" shell settings put secure location_mode 3
(
    while true; do
        # Public fixture; a framework GPS callback, not an AMap route or the user's location.
        adb -s "$serial" emu geo fix 116.481763 39.989614 50
        sleep 1
    done
) > emulator-evidence/gps-injection.txt 2>&1 &
replay_pid=$!
finish() {
    kill "$replay_pid" 2>/dev/null || true
    wait "$replay_pid" 2>/dev/null || true
    adb -s "$serial" pull /sdcard/Android/data/com.motonav.recovery/files/emulator-evidence emulator-evidence/app || true
}
trap finish EXIT
gradle --no-daemon connectedDebugAndroidTest
