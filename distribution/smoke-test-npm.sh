#!/usr/bin/env bash
# 作用：验证 npm 安装包能够安装并运行。
# 使用场景：发布前检查 npm 启动器与下载清单。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARCHIVE="${1:-}"

if [ -z "$ARCHIVE" ]; then
  ARCHIVE="$(find "$ROOT/distribution/build" -maxdepth 1 -type f \
    \( -name '*.tar.gz' -o -name '*.zip' \) | sort | tail -n 1)"
fi
if [ -z "$ARCHIVE" ] || [ ! -f "$ARCHIVE" ]; then
  echo "npm smoke test failed: archive not found" >&2
  exit 2
fi
if ! command -v npm >/dev/null 2>&1; then
  echo "npm smoke test failed: npm is required" >&2
  exit 2
fi

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ftg-npm-smoke.XXXXXX")"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mkdir -p "$TMP_DIR/pack" "$TMP_DIR/install" "$TMP_DIR/home" "$TMP_DIR/project"
(
  cd "$ROOT/distribution/npm"
  npm pack --pack-destination "$TMP_DIR/pack" --silent >/dev/null
)
TARBALL="$(find "$TMP_DIR/pack" -maxdepth 1 -type f -name '*.tgz' | sort | tail -n 1)"
[ -n "$TARBALL" ] || {
  echo "npm smoke test failed: npm pack produced no tarball" >&2
  exit 1
}

npm install --prefix "$TMP_DIR/install" --no-audit --no-fund --silent "$TARBALL"
VERSION="$(node -p 'require(process.argv[1]).version' "$ROOT/distribution/release-manifest.json")"
INSTALL_ROOT="$TMP_DIR/home/.field-trace-graph/dist/$VERSION"
mkdir -p "$INSTALL_ROOT"
case "$ARCHIVE" in
  *.zip)
    command -v unzip >/dev/null 2>&1 || {
      echo "npm smoke test failed: unzip is required for .zip archives" >&2
      exit 2
    }
    unzip -q "$ARCHIVE" -d "$INSTALL_ROOT"
    ;;
  *.tar.gz)
    LC_ALL=C tar -xzf "$ARCHIVE" -C "$INSTALL_ROOT"
    ;;
  *)
    echo "npm smoke test failed: unsupported archive type: $ARCHIVE" >&2
    exit 2
    ;;
esac

LAUNCHER="$TMP_DIR/install/node_modules/field-trace-graph/bin/field-trace-graph.js"
[ -f "$LAUNCHER" ] || {
  echo "npm smoke test failed: npm package launcher is missing" >&2
  exit 1
}
FTG_LAUNCHER="$TMP_DIR/install/node_modules/.bin/ftg"
[ -x "$FTG_LAUNCHER" ] || {
  echo "npm smoke test failed: npm ftg bin link is missing" >&2
  exit 1
}

echo "== npm launcher version =="
HOME="$TMP_DIR/home" node "$LAUNCHER" --version

echo "== npm ftg alias version =="
HOME="$TMP_DIR/home" "$FTG_LAUNCHER" --version

echo "== npm launcher doctor =="
HOME="$TMP_DIR/home" node "$LAUNCHER" doctor --project "$TMP_DIR/project" --format json

echo "npm smoke test passed: $TARBALL"
