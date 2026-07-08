#!/usr/bin/env bash
set -euo pipefail

url="${1:-https://www.youtube.com/watch?v=dQw4w9WgXcQ}"
repetitions="${REPETITIONS:-5}"
warmups="${WARMUPS:-1}"
play_seconds="${PLAY_SECONDS:-10}"
max_height="${MAX_VIDEO_HEIGHT:-1080}"
target_codec="${TARGET_CODEC:-avc}"
hls_extraction_retries="${HLS_EXTRACTION_RETRIES:-5}"
replace_player_cache="${REPLACE_PLAYER_CACHE:-false}"
output="${OUTPUT:-youtube-playback-benchmark-$(date +%Y%m%d-%H%M%S).log}"
jsonl="${JSONL_OUTPUT:-${output%.log}.jsonl}"
adb="${ADB:-adb}"

case "${replace_player_cache,,}" in
  1|true|yes) replace_player_cache=true ;;
  0|false|no) replace_player_cache=false ;;
  *) echo "REPLACE_PLAYER_CACHE must be true or false" >&2; exit 2 ;;
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
instrument_args=(
  -e class org.schabi.newpipe.player.YoutubePlaybackBenchmarkTest
  -e url "$url"
  -e repetitions "$repetitions"
  -e warmups "$warmups"
  -e playSeconds "$play_seconds"
  -e maxVideoHeight "$max_height"
  -e hlsExtractionRetries "$hls_extraction_retries"
  -e replacePlayerCache "$replace_player_cache"
)
if [[ -n "$target_codec" ]]; then
  instrument_args+=(-e targetCodec "$target_codec")
fi
$adb shell am instrument -w -r "${instrument_args[@]}" \
  "$test_id/androidx.test.runner.AndroidJUnitRunner" | tee "$output"

$adb logcat -d -v brief \
  | rg 'YoutubePlayerCache|PIPEPIPE_BENCHMARK_' \
  | tee -a "$output"

rg --no-filename 'PIPEPIPE_BENCHMARK_' "$output" \
  | sed -E 's/^.*PIPEPIPE_BENCHMARK_[A-Z_]+ (\{.*\})$/\1/' | tee "$jsonl"
