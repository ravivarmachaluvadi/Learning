#!/bin/sh
# Headless validation of visualization.html.
# There is no node/deno/bun on this machine, so we run the page's own JavaScript
# through JavaScriptCore via `osascript -l JavaScript`, with a tiny DOM shim.
#
#   ./validate-visualization.sh
#
# Suite 1 (engine): the MVCC rules, the horizon, HOT, freezing/wraparound, and
#                   every scenario's expected end state.
# Suite 2 (DOM):    every scenario x every dial combination, stepped forward AND
#                   backward, with every control exercised and every panel checked.
set -e
cd "$(dirname "$0")"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

awk '/=== ENGINE START ===/,/=== ENGINE END ===/'       visualization.html > "$TMP/engine.js"
awk '/=== SCENARIOS START ===/,/=== SCENARIOS END ===/' visualization.html > "$TMP/scen.js"
awk '/=== VIEW START ===/,/=== VIEW END ===/'           visualization.html > "$TMP/view.js"

echo "--- suite 1: engine ---"
cat "$TMP/engine.js" "$TMP/scen.js" tests/engine-tests.js > "$TMP/run1.js"
osascript -l JavaScript "$TMP/run1.js"

echo "--- suite 2: render + controls ---"
cat tests/dom-shim.js "$TMP/engine.js" "$TMP/scen.js" "$TMP/view.js" tests/dom-tests.js > "$TMP/run2.js"
osascript -l JavaScript "$TMP/run2.js"
