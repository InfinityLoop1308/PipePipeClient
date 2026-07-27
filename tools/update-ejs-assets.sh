#!/usr/bin/env bash
set -euo pipefail

CLIENT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EJS_DIR="${EJS_DIR:-${CLIENT_DIR}/../../ejs}"

if [[ ! -f "${EJS_DIR}/package.json" ]]; then
    echo "EJS source directory not found: ${EJS_DIR}" >&2
    echo "Set EJS_DIR to the yt-dlp/ejs checkout." >&2
    exit 1
fi

(
    cd "${EJS_DIR}"
    npm run bundle:legacy
)

cp "${EJS_DIR}/dist/yt.solver.core.es5.min.js" \
    "${CLIENT_DIR}/app/src/main/assets/ejs/yt.solver.core.es5.min.js"
cp "${EJS_DIR}/dist/yt.solver.lib.es5.min.js" \
    "${CLIENT_DIR}/app/src/main/assets/ejs/yt.solver.lib.es5.min.js"
cp "${EJS_DIR}/dist/yt.solver.polyfills.es5.js" \
    "${CLIENT_DIR}/app/src/main/assets/ejs/yt.solver.polyfills.es5.js"

echo "Updated legacy EJS assets from ${EJS_DIR}"
