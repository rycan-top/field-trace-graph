#!/usr/bin/env bash
# 作用：验证发布归档解压后可以正常运行。
# 使用场景：归档构建完成后做冒烟测试。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ARCHIVE="${1:-}"

if [ -z "$ARCHIVE" ]; then
  ARCHIVE="$(find "$ROOT/distribution/build" -maxdepth 1 -type f \
    \( -name '*.tar.gz' -o -name '*.zip' \) | sort | tail -n 1)"
fi
if [ -z "$ARCHIVE" ] || [ ! -f "$ARCHIVE" ]; then
  echo "release smoke test failed: archive not found" >&2
  exit 2
fi

TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/ftg-release-smoke.XXXXXX")"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

case "$ARCHIVE" in
  *.zip)
    command -v unzip >/dev/null 2>&1 || {
      echo "release smoke test failed: unzip is required for .zip archives" >&2
      exit 2
    }
    unzip -q "$ARCHIVE" -d "$TMP_DIR"
    ;;
  *.tar.gz)
    LC_ALL=C tar -xzf "$ARCHIVE" -C "$TMP_DIR"
    ;;
  *)
    echo "release smoke test failed: unsupported archive type: $ARCHIVE" >&2
    exit 2
    ;;
esac

APP_ROOT="$TMP_DIR"
BIN="$APP_ROOT/bin/field-trace-graph"
PROJECT="$TMP_DIR/sample-project"
JAVA_SOURCE="$PROJECT/src/main/java/demo/Demo.java"

[ -x "$BIN" ] || {
  echo "release smoke test failed: launcher is missing: $BIN" >&2
  exit 1
}
[ -x "$APP_ROOT/runtime/bin/java" ] || {
  echo "release smoke test failed: bundled runtime is missing" >&2
  exit 1
}

mkdir -p "$PROJECT/src/main/java/demo"
cat > "$JAVA_SOURCE" <<'JAVA'
package demo;

public class Demo {
    public Result copy(Source source) {
        Result result = new Result();
        result.value = source.value;
        return result;
    }

    public static class Source {
        public String value;
    }

    public static class Result {
        public String value;
    }
}
JAVA

echo "== version =="
"$BIN" --version

echo "== doctor =="
"$BIN" doctor --project "$PROJECT" --format json

echo "== init =="
INIT_JSON="$("$BIN" init --project "$PROJECT" --format json)"
printf '%s\n' "$INIT_JSON"
printf '%s\n' "$INIT_JSON" | grep -Eq '"command"[[:space:]]*:[[:space:]]*"init"' || {
  echo "release smoke test failed: init did not return the init command result" >&2
  exit 1
}

echo "== trace =="
TRACE_JSON="$("$BIN" trace \
  --project "$PROJECT" \
  --method 'demo.Demo#copy(Source)' \
  --target 'result.value' \
  --direction both \
  --no-sync \
  --allow-stale \
  --format json)"
printf '%s\n' "$TRACE_JSON"
printf '%s\n' "$TRACE_JSON" | grep -Eq '"status"[[:space:]]*:[[:space:]]*"OK"' || {
  echo "release smoke test failed: trace did not return OK" >&2
  exit 1
}

echo "release smoke test passed: $ARCHIVE"
