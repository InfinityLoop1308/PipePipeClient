#!/usr/bin/env bash
set -euo pipefail

samples="${SAMPLES:-20}"
timeout_ms="${TIMEOUT_MS:-15000}"
adb="${ADB:-adb}"

./gradlew :app:assembleDebug :app:assembleDebugAndroidTest

abi="$($adb shell getprop ro.product.cpu.abi | tr -d '\r')"
app_apk="$(find app/build/outputs/apk/debug -name "*-${abi}-debug.apk" -print -quit)"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
app_metadata="app/build/outputs/apk/debug/output-metadata.json"
test_metadata="app/build/outputs/apk/androidTest/debug/output-metadata.json"
app_id="$(sed -n 's/.*"applicationId": "\([^"]*\)".*/\1/p' "$app_metadata" | head -1)"
test_id="$(sed -n 's/.*"applicationId": "\([^"]*\)".*/\1/p' "$test_metadata" | head -1)"

if [[ ! "$samples" =~ ^[1-9][0-9]*$ || ! "$timeout_ms" =~ ^[1-9][0-9]*$ ]]; then
  echo "SAMPLES and TIMEOUT_MS must be positive integers" >&2
  exit 2
fi
if [[ -z "$app_apk" || ! -f "$test_apk" || -z "$app_id" || -z "$test_id" ]]; then
  echo "Could not locate cold-start test APKs or application IDs" >&2
  exit 2
fi

$adb install -r -t "$app_apk"
$adb install -r -t "$test_apk"

ready=0
failed=0
retries=0
for sample in $(seq 1 "$samples"); do
  $adb shell am force-stop "$app_id" >/dev/null
  $adb logcat -c
  result="$($adb shell am instrument -w -r \
    -e class org.schabi.newpipe.WebViewRuntimeColdStartTest \
    -e timeoutMs "$timeout_ms" \
    "$test_id/androidx.test.runner.AndroidJUnitRunner" 2>&1 || true)"
  logs="$($adb logcat -d -v brief)"
  if printf '%s' "$result" | rg -q 'OK \(1 test\)'; then
    ready=$((ready + 1))
    attempt="$(printf '%s\n' "$logs" \
      | sed -n 's/.*SharedWebViewRuntime.*ready source=bridge attempt=\([0-9][0-9]*\).*/\1/p' \
      | tail -1)"
    if [[ "$attempt" == "2" ]]; then
      retries=$((retries + 1))
    fi
    printf 'sample=%02d result=ready attempt=%s\n' "$sample" "${attempt:-unknown}"
  else
    failed=$((failed + 1))
    reason="$(printf '%s' "$result" \
      | rg -o 'timed out waiting for WebView runtime|[A-Za-z]+Exception: [^\r\n]+' \
      | head -1 || true)"
    printf 'sample=%02d result=failed reason=%s\n' "$sample" "${reason:-unknown}"
  fi
done

printf 'summary samples=%d ready=%d failed=%d retries=%d timeoutMs=%d\n' \
  "$samples" "$ready" "$failed" "$retries" "$timeout_ms"

if (( failed > 0 )); then
  exit 1
fi
