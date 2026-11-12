#!/usr/bin/env bash
# 作用：运行 gitleaks 与 syft 安全扫描。
# 使用场景：发布门禁以及本地安全自查时。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
REPORT_DIR="$ROOT/security-reports"

require() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "security scan tool is missing: $1" >&2
    exit 1
  }
}

require gitleaks
require syft

mkdir -p "$REPORT_DIR"

if command -v rg >/dev/null 2>&1; then
  if rg -n --hidden -S \
    -g '!.git/**' \
    -g '!distribution/build/**' \
    -g '!**/target/**' \
    -g '!security-reports/**' \
    -g '!distribution/npm/node_modules/**' \
    '/Users/[A-Za-z0-9._-]+|[A-Za-z]:\\\\Users\\\\[A-Za-z0-9._-]+' .; then
    echo "private absolute user path found in release sources" >&2
    exit 1
  fi
fi

[ -f "$ROOT/LICENSE" ] || {
  echo "LICENSE is missing" >&2
  exit 1
}
grep -q '"license"[[:space:]]*:[[:space:]]*"UNLICENSED"' "$ROOT/distribution/npm/package.json" \
  && {
    echo "npm package license is still UNLICENSED" >&2
    exit 1
  }

gitleaks detect \
  --no-git \
  --source "$ROOT" \
  --config "$ROOT/.gitleaks.toml" \
  --redact \
  --report-format json \
  --report-path "$REPORT_DIR/gitleaks-source.json"

syft dir:"$ROOT" \
  --config "$ROOT/.syft.yaml" \
  -o "spdx-json=$REPORT_DIR/sbom-source.spdx.json"

if command -v npm >/dev/null 2>&1; then
  (cd "$ROOT/distribution/npm" && npm pack --pack-destination "$REPORT_DIR" >/dev/null)
  for archive in "$REPORT_DIR"/field-trace-graph-*.tgz; do
    [ -f "$archive" ] || continue
    syft "file:$archive" -o "spdx-json=$REPORT_DIR/$(basename "$archive").sbom.spdx.json"
  done
fi

for archive in "$ROOT"/distribution/build/*.tar.gz "$ROOT"/distribution/build/*.zip; do
  [ -f "$archive" ] || continue
  syft "file:$archive" -o "spdx-json=$REPORT_DIR/$(basename "$archive").sbom.spdx.json"
done

if [ -f "$ROOT/target/field-trace-graph-sbom.json" ]; then
  cp "$ROOT/target/field-trace-graph-sbom.json" "$REPORT_DIR/field-trace-graph-sbom.json"
fi

echo "source, npm package, and release archive security scans completed"
