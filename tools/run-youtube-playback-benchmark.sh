#!/usr/bin/env bash
set -euo pipefail

url="${1:-https://www.youtube.com/watch?v=G-eNlqqkn1w}"
repetitions="${REPETITIONS:-5}"
warmups="${WARMUPS:-1}"
play_seconds="${PLAY_SECONDS:-60}"
start_position_ms="${START_POSITION_MS:-2995000}"
seek_target_ms="${SEEK_TARGET_MS:--1}"
paths="${PATHS:-sabr,tv_downgraded_generated_dash}"
cookie_file="${COOKIE_FILE:-/tmp/token.txt}"
device_cookie_file="${DEVICE_COOKIE_FILE:-/data/local/tmp/pipepipe-benchmark-token.txt}"
warm_webview_runtime="${WARM_WEBVIEW_RUNTIME:-false}"
cold_sabr_caches_each_trial="${COLD_SABR_CACHES_EACH_TRIAL:-false}"
diagnostic_details="${DIAGNOSTIC_DETAILS:-false}"
max_height="${MAX_VIDEO_HEIGHT:-1080}"
target_codec="${TARGET_CODEC:-avc}"
hls_extraction_retries="${HLS_EXTRACTION_RETRIES:-5}"
replace_player_cache="${REPLACE_PLAYER_CACHE:-false}"
output="${OUTPUT:-../log/youtube-playback-benchmark-$(date +%Y%m%d-%H%M%S).log}"
jsonl="${JSONL_OUTPUT:-${output%.log}.jsonl}"
adb="${ADB:-adb}"
cookie_pushed=false

cleanup() {
  if [[ "$cookie_pushed" == true ]]; then
    $adb shell rm -f "$device_cookie_file" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

mkdir -p "$(dirname "$output")" "$(dirname "$jsonl")"

case "${replace_player_cache,,}" in
  1|true|yes) replace_player_cache=true ;;
  0|false|no) replace_player_cache=false ;;
  *) echo "REPLACE_PLAYER_CACHE must be true or false" >&2; exit 2 ;;
esac
case "${warm_webview_runtime,,}" in
  1|true|yes) warm_webview_runtime=true ;;
  0|false|no) warm_webview_runtime=false ;;
  *) echo "WARM_WEBVIEW_RUNTIME must be true or false" >&2; exit 2 ;;
esac
case "${cold_sabr_caches_each_trial,,}" in
  1|true|yes) cold_sabr_caches_each_trial=true ;;
  0|false|no) cold_sabr_caches_each_trial=false ;;
  *) echo "COLD_SABR_CACHES_EACH_TRIAL must be true or false" >&2; exit 2 ;;
esac
case "${diagnostic_details,,}" in
  1|true|yes) diagnostic_details=true ;;
  0|false|no) diagnostic_details=false ;;
  *) echo "DIAGNOSTIC_DETAILS must be true or false" >&2; exit 2 ;;
esac

./gradlew assembleDebug assembleDebugAndroidTest

abi="$($adb shell getprop ro.product.cpu.abi | tr -d '\r')"
app_apk="$(find app/build/outputs/apk/debug -name "*-${abi}-debug.apk" -print -quit)"
test_apk="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
app_metadata="app/build/outputs/apk/debug/output-metadata.json"
test_metadata="app/build/outputs/apk/androidTest/debug/output-metadata.json"
app_id="$(sed -n 's/.*"applicationId": "\([^"]*\)".*/\1/p' "$app_metadata" | head -1)"
test_id="$(sed -n 's/.*"applicationId": "\([^"]*\)".*/\1/p' "$test_metadata" | head -1)"

if [[ -z "$app_apk" || ! -f "$test_apk" || -z "$app_id" || -z "$test_id" ]]; then
  echo "Could not locate benchmark APKs or application IDs" >&2
  exit 2
fi

# install -r intentionally retains the target app's private player-response cache. The Gradle
# connectedAndroidTest task uninstalls the app and would silently turn every invocation into a miss.
$adb install -r -t "$app_apk"
$adb install -r -t "$test_apk"
$adb logcat -c
device_cookie_arg=()
if [[ -n "$cookie_file" ]]; then
  if [[ ! -f "$cookie_file" ]]; then
    echo "Cookie/token file does not exist: $cookie_file" >&2
    exit 2
  fi
  $adb push "$cookie_file" "$device_cookie_file" >/dev/null
  cookie_pushed=true
  device_cookie_arg=(-e cookieFile "$device_cookie_file")
fi
instrument_args=(
  -e class org.schabi.newpipe.player.YoutubePlaybackBenchmarkTest
  -e url "$url"
  -e repetitions "$repetitions"
  -e warmups "$warmups"
  -e playSeconds "$play_seconds"
  -e startPositionMs "$start_position_ms"
  -e seekTargetMs "$seek_target_ms"
  -e paths "$paths"
  -e warmWebViewRuntime "$warm_webview_runtime"
  -e coldSabrCachesEachTrial "$cold_sabr_caches_each_trial"
  -e diagnosticDetails "$diagnostic_details"
  -e maxVideoHeight "$max_height"
  -e hlsExtractionRetries "$hls_extraction_retries"
  -e replacePlayerCache "$replace_player_cache"
)
if [[ -n "$target_codec" ]]; then
  instrument_args+=(-e targetCodec "$target_codec")
fi
$adb shell am instrument -w -r "${instrument_args[@]}" "${device_cookie_arg[@]}" \
  "$test_id/androidx.test.runner.AndroidJUnitRunner" | tee "$output"

$adb logcat -d -v brief \
  | rg 'YoutubePlayerCache|PIPEPIPE_BENCHMARK_' \
  | tee -a "$output"

benchmark_pid="$(rg 'PIPEPIPE_BENCHMARK_CONFIG' "$output" | tail -1 \
  | sed -nE 's/^.*System\.out\( *([0-9]+)\).*$/\1/p')"
if [[ -n "$benchmark_pid" ]]; then
  rg --no-filename "System\\.out\\( *${benchmark_pid}\\).*PIPEPIPE_BENCHMARK_" "$output"
else
  rg --no-filename 'PIPEPIPE_BENCHMARK_' "$output"
fi | sed -E 's/^.*PIPEPIPE_BENCHMARK_[A-Z_]+ (\{.*\})$/\1/' | tee "$jsonl"
