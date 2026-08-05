#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || -z "$1" ]]; then
  echo "Usage: $0 <youtube-url>" >&2
  exit 2
fi

readonly url="$1"
readonly script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cd "$script_dir/.."

probe_status=0
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=org.schabi.newpipe.player.datasource.SabrPlaybackSmokeTest#extractorToSabrFetchesAudioAfter65Seconds \
  -Pandroid.testInstrumentationRunnerArguments.url="$url" || probe_status=$?

readonly result_dir="app/build/outputs/androidTest-results/connected/debug"
if [[ -d "$result_dir" ]]; then
  while IFS= read -r log_file; do
    rg 'SABR_AUDIO_PROBE(_TRACE)?' "$log_file" || true
  done < <(find "$result_dir" -type f \
    -name '*SabrPlaybackSmokeTest-extractorToSabrFetchesAudioAfter65Seconds.txt' -print)
fi

exit "$probe_status"
