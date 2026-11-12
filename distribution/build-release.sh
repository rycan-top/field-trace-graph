#!/usr/bin/env bash
# 作用：构建单个平台的发布归档。
# 使用场景：本地或 CI 为指定操作系统与 CPU 打包时。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${VERSION:-0.1.0}"
OS="${OS:-$(uname -s | tr '[:upper:]' '[:lower:]')}"
case "$OS" in
  darwin|linux|win32) ;;
  windows) OS=win32 ;;
  *) echo "Unsupported OS for local release build: $OS" >&2; exit 1 ;;
esac

CPU="${CPU:-$(uname -m)}"
case "$CPU" in
  x86_64|amd64) CPU=x64 ;;
  arm64|aarch64) CPU=arm64 ;;
  *) echo "Unsupported CPU for local release build: $CPU" >&2; exit 1 ;;
esac

if [ "$OS" = "win32" ]; then
  ARCHIVE="field-trace-graph-${VERSION}-${OS}-${CPU}.zip"
else
  ARCHIVE="field-trace-graph-${VERSION}-${OS}-${CPU}.tar.gz"
fi
STAGE="$ROOT/distribution/build/stage-${OS}-${CPU}"
OUT="$ROOT/distribution/build/$ARCHIVE"
BUNDLE_RUNTIME="${FTG_BUNDLE_RUNTIME:-1}"
MANIFEST_VERSION="$(node -p 'require(process.argv[1]).version' "$ROOT/distribution/release-manifest.json")"
PACKAGE_VERSION="$(node -p 'require(process.argv[1]).version' "$ROOT/distribution/npm/package.json")"
if [ "$VERSION" != "$MANIFEST_VERSION" ] || [ "$VERSION" != "$PACKAGE_VERSION" ]; then
  echo "VERSION must match release-manifest.json and npm package.json." >&2
  exit 1
fi
case "$BUNDLE_RUNTIME" in
  0|1) ;;
  *) echo "FTG_BUNDLE_RUNTIME must be 0 or 1." >&2; exit 1 ;;
esac
if [ "${FTG_STRICT_RELEASE:-0}" = "1" ] && [ "$BUNDLE_RUNTIME" != "1" ]; then
  echo "Strict release builds require a bundled runtime." >&2
  exit 1
fi

cd "$ROOT"
is_supported_jdk() {
  [ -n "${1:-}" ] || return 1
  [ -x "$1/bin/javac" ] || return 1
  "$1/bin/javac" -version 2>&1 | grep -Eq '^javac (17|21)(\.|$)'
}

JAVA_HOME="${FTG_JAVA_HOME:-}"
if [ -n "$JAVA_HOME" ] && ! is_supported_jdk "$JAVA_HOME"; then
  echo "FTG_JAVA_HOME must point to a JDK 17 or 21 with javac." >&2
  exit 1
fi
if [ -z "$JAVA_HOME" ] && command -v javac >/dev/null 2>&1; then
  JAVAC_PATH="$(command -v javac)"
  if command -v realpath >/dev/null 2>&1; then
    JAVAC_PATH="$(realpath "$JAVAC_PATH")"
  elif readlink -f "$JAVAC_PATH" >/dev/null 2>&1; then
    JAVAC_PATH="$(readlink -f "$JAVAC_PATH")"
  fi
  CANDIDATE="$(cd "$(dirname "$JAVAC_PATH")/.." && pwd)"
  if is_supported_jdk "$CANDIDATE"; then
    JAVA_HOME="$CANDIDATE"
  fi
fi
if [ -z "$JAVA_HOME" ] && [ -x /usr/libexec/java_home ]; then
  for version in 21 17; do
    CANDIDATE="$(/usr/libexec/java_home -v "$version" 2>/dev/null || true)"
    if is_supported_jdk "$CANDIDATE"; then
      JAVA_HOME="$CANDIDATE"
      break
    fi
  done
fi
if [ -z "$JAVA_HOME" ]; then
  for candidate in \
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
    /opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home \
    /usr/lib/jvm/java-21-openjdk \
    /usr/lib/jvm/java-17-openjdk; do
    if is_supported_jdk "$candidate"; then
      JAVA_HOME="$candidate"
      break
    fi
  done
fi
if [ -z "$JAVA_HOME" ]; then
  echo "A JDK 17 or 21 with javac is required; set FTG_JAVA_HOME." >&2
  exit 1
fi
export PATH="$JAVA_HOME/bin:$PATH"
RUNTIME_HOME="${FTG_RUNTIME_HOME:-$JAVA_HOME}"
if [ "${FTG_SKIP_BUILD:-0}" != "1" ]; then
  mvn -q clean package
fi

rm -rf "$STAGE"
mkdir -p "$STAGE/bin" "$STAGE/lib" "$STAGE/licenses"

cp lineage-cli/target/field-trace-graph.jar "$STAGE/lib/field-trace-graph.jar"
cp distribution/launcher/field-trace-graph "$STAGE/bin/field-trace-graph"
cp distribution/launcher/field-trace-graph.bat "$STAGE/bin/field-trace-graph.bat"
cp distribution/launcher/field-trace-graph "$STAGE/bin/ftg"
cp distribution/launcher/field-trace-graph.bat "$STAGE/bin/ftg.bat"
cp THIRD-PARTY-NOTICES "$STAGE/licenses/THIRD-PARTY-NOTICES"
cp -R distribution/licenses/third-party "$STAGE/licenses/third-party"
if [ -f "$ROOT/LICENSE" ]; then
  cp "$ROOT/LICENSE" "$STAGE/LICENSE"
elif [ "${FTG_STRICT_RELEASE:-0}" = "1" ]; then
  echo "LICENSE is required for a release build." >&2
  exit 1
else
  echo "warning: LICENSE is missing; this archive is not publishable." >&2
fi
if [ -f "$ROOT/NOTICE" ]; then
  cp "$ROOT/NOTICE" "$STAGE/NOTICE"
fi
cp README.md "$STAGE/README.md"
if [ -f "$ROOT/target/field-trace-graph-sbom.json" ]; then
  cp "$ROOT/target/field-trace-graph-sbom.json" "$STAGE/field-trace-graph-sbom.json"
elif [ "${FTG_STRICT_RELEASE:-0}" = "1" ]; then
  echo "CycloneDX SBOM is required for a release build." >&2
  exit 1
else
  echo "warning: SBOM is missing; this archive is not publishable." >&2
fi
printf '%s\n' "$VERSION" > "$STAGE/.version"
chmod +x "$STAGE/bin/field-trace-graph"
chmod +x "$STAGE/bin/ftg"

if [ "$BUNDLE_RUNTIME" = "1" ]; then
  if [ -z "$RUNTIME_HOME" ] || [ ! -x "$RUNTIME_HOME/bin/java" ]; then
    echo "A runtime is required; set FTG_RUNTIME_HOME or FTG_BUNDLE_RUNTIME=0." >&2
    exit 1
  fi
  mkdir -p "$STAGE/runtime"
  cp -R "$RUNTIME_HOME/." "$STAGE/runtime/"
  if [ -d "$RUNTIME_HOME/legal" ]; then
    mkdir -p "$STAGE/licenses/runtime-legal"
    cp -R "$RUNTIME_HOME/legal/." "$STAGE/licenses/runtime-legal/"
  fi
  if [ -f "$RUNTIME_HOME/release" ]; then
    cp "$RUNTIME_HOME/release" "$STAGE/licenses/runtime-release"
  fi
  if [ "$OS" = "win32" ]; then
    if [ ! -f "$STAGE/runtime/bin/java.exe" ] || [ ! -f "$STAGE/runtime/bin/javac.exe" ]; then
      echo "Bundled runtime must contain java.exe and javac.exe." >&2
      exit 1
    fi
  elif [ ! -x "$STAGE/runtime/bin/java" ] || [ ! -x "$STAGE/runtime/bin/javac" ]; then
    echo "Bundled runtime must contain java and javac." >&2
    exit 1
  fi
fi

mkdir -p "$ROOT/distribution/build"
if [ "$OS" = "win32" ]; then
  if command -v zip >/dev/null 2>&1; then
    (cd "$STAGE" && zip -qr "$OUT" .)
  elif command -v jar >/dev/null 2>&1; then
    # Windows runners do not always provide zip; the required JDK always
    # provides a deterministic ZIP implementation.
    jar --create --file "$OUT" -C "$STAGE" .
  else
    echo "zip or a JDK jar tool is required to build a Windows archive." >&2
    exit 1
  fi
else
  LC_ALL=C tar -czf "$OUT" -C "$STAGE" .
fi
if command -v shasum >/dev/null 2>&1; then
  SHA256="$(LC_ALL=C shasum -a 256 "$OUT" | awk '{print $1}')"
else
  SHA256="$(LC_ALL=C sha256sum "$OUT" | awk '{print $1}')"
fi
node "$ROOT/distribution/update-release-manifest.js" "$OS" "$CPU" "$ARCHIVE" "$SHA256"

echo "release archive: $OUT"
echo "sha256: $SHA256"
