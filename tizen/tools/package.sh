#!/usr/bin/env bash
# Build a signed UgisF1.wgt with the Tizen CLI and (optionally) install it on a TV.
#
#   tools/package.sh                       # package with the active certificate profile
#   PROFILE=mytv tools/package.sh          # package with a named certificate profile
#   TV=192.168.1.50 tools/package.sh       # package, connect over sdb and install
#
# Requires Tizen Studio (or the standalone CLI) with `tizen` and `sdb` on PATH and a
# Samsung certificate profile created in Certificate Manager (see tizen/README.md).
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$HERE/dist"
PROFILE="${PROFILE:-}"

command -v tizen >/dev/null || { echo "tizen CLI not found — install Tizen Studio and add tools/ide/bin to PATH" >&2; exit 1; }

rm -rf "$DIST"
mkdir -p "$DIST"
# Ship only what the app needs (no tools/, no README, no mock data).
cp "$HERE/config.xml" "$HERE/index.html" "$HERE/icon.png" "$DIST/"
cp -r "$HERE/css" "$DIST/"
mkdir -p "$DIST/js/lib"
cp "$HERE"/js/{config,storage,api,nav,ui,player,app}.js "$DIST/js/"
cp "$HERE/js/lib/shaka-player.compiled.js" "$DIST/js/lib/"
if [ -f "$HERE/js/local.js" ]; then
  cp "$HERE/js/local.js" "$DIST/js/"
else
  echo "window.UGISF1_LOCAL = {};" > "$DIST/js/local.js"
fi

cd "$DIST"
if [ -n "$PROFILE" ]; then
  tizen package -t wgt -s "$PROFILE" -- "$DIST"
else
  tizen package -t wgt -- "$DIST"
fi

WGT="$(ls "$DIST"/*.wgt | head -n1)"
mv "$WGT" "$DIST/UgisF1.wgt"
echo "Built $DIST/UgisF1.wgt"

if [ -n "${TV:-}" ]; then
  sdb connect "$TV:26101"
  tizen install -n UgisF1.wgt -s "$TV:26101" -- "$DIST"
  echo "Installed on $TV — launch UgisF1 from Apps."
fi
