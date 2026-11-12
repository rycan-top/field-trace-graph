#!/usr/bin/env bash
# 作用：执行发布门禁的总校验。
# 使用场景：正式发布前的最后一道检查。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MANIFEST="$ROOT/distribution/release-manifest.json"
PLATFORM_FILTER="${FTG_VERIFY_PLATFORM_ONLY:-}"

fail() {
  echo "release verification failed: $*" >&2
  exit 1
}

[ -f "$ROOT/LICENSE" ] || fail "LICENSE is missing"
grep -q '"license"[[:space:]]*:[[:space:]]*"UNLICENSED"' "$ROOT/distribution/npm/package.json" \
  && fail "npm package license is still UNLICENSED"
[ -f "$ROOT/target/field-trace-graph-sbom.json" ] || fail "SBOM is missing; run mvn clean package"

BASE_URL="$(node -e 'const m=require(process.argv[1]);process.stdout.write(m.baseUrl)' "$MANIFEST")"
case "$BASE_URL" in
  https://example.invalid/*) fail "release baseUrl is still a placeholder" ;;
  https://*) ;;
  *) fail "release baseUrl must be HTTPS: $BASE_URL" ;;
esac

node - "$MANIFEST" "$PLATFORM_FILTER" <<'NODE'
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { execFileSync } = require('child_process');

const [manifestPath, filter] = process.argv.slice(2);
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const root = path.dirname(path.dirname(manifestPath));
const buildDirectory = path.join(root, 'distribution/build');

for (const platform of manifest.platforms) {
  const key = `${platform.os}-${platform.cpu}`;
  if (filter && filter !== key) {
    continue;
  }
  if (!/^[a-f0-9]{64}$/.test(platform.sha256) || /^0{64}$/.test(platform.sha256)) {
    throw new Error(`${key}: sha256 is not populated`);
  }
  const archive = path.join(buildDirectory, platform.archive);
  if (!fs.existsSync(archive)) {
    throw new Error(`${key}: archive is missing: ${archive}`);
  }
  const actual = crypto.createHash('sha256').update(fs.readFileSync(archive)).digest('hex');
  if (actual !== platform.sha256) {
    throw new Error(`${key}: sha256 mismatch`);
  }
  const listing = platform.archive.endsWith('.zip')
    ? execFileSync('unzip', ['-Z1', archive], { encoding: 'utf8' })
    : execFileSync('tar', ['-tzf', archive], { encoding: 'utf8', env: { ...process.env, LC_ALL: 'C' } });
  const entries = listing.split('\n').filter(Boolean);
  const normalizedEntries = entries.map((entry) => entry.replace(/^\.\//, '').replace(/\/$/, ''));
  for (const entry of normalizedEntries) {
    const segments = entry.replace(/\\/g, '/').split('/').filter((segment) => segment && segment !== '.');
    if (entry.startsWith('/') || /^[A-Za-z]:/.test(entry) || segments.includes('..')) {
      throw new Error(`${key}: archive contains an unsafe path: ${entry}`);
    }
  }
  if (normalizedEntries.some((entry) => entry === 'stage-' || entry.startsWith('stage-'))) {
    throw new Error(`${key}: archive has a stage-* top-level directory`);
  }
  if (!normalizedEntries.includes('.version')) {
    throw new Error(`${key}: archive is missing .version at the root`);
  }
  const runtime = platform.os === 'win32' ? 'runtime/bin/java.exe' : 'runtime/bin/java';
  const compiler = platform.os === 'win32' ? 'runtime/bin/javac.exe' : 'runtime/bin/javac';
  if (!normalizedEntries.includes(runtime)) {
    throw new Error(`${key}: archive is missing ${runtime}`);
  }
  if (!normalizedEntries.includes(compiler)) {
    throw new Error(`${key}: archive is missing ${compiler}`);
  }
  if (!normalizedEntries.includes('bin/field-trace-graph')) {
    throw new Error(`${key}: archive is missing the launcher`);
  }
  if (!normalizedEntries.includes('bin/ftg')) {
    throw new Error(`${key}: archive is missing the ftg launcher`);
  }
  if (!normalizedEntries.includes('lib/field-trace-graph.jar')) {
    throw new Error(`${key}: archive is missing the application jar`);
  }
  if (!normalizedEntries.includes('licenses/runtime-legal')) {
    throw new Error(`${key}: archive is missing runtime licenses`);
  }
  if (!normalizedEntries.includes('licenses/runtime-release')) {
    throw new Error(`${key}: archive is missing runtime release metadata`);
  }
  if (!normalizedEntries.includes('LICENSE')) {
    throw new Error(`${key}: archive is missing LICENSE`);
  }
  if (!normalizedEntries.includes('licenses/THIRD-PARTY-NOTICES')) {
    throw new Error(`${key}: archive is missing third-party notices`);
  }
  if (!normalizedEntries.includes('licenses/third-party/Apache-2.0.txt')) {
    throw new Error(`${key}: archive is missing third-party license texts`);
  }
  if (!normalizedEntries.includes('field-trace-graph-sbom.json')) {
    throw new Error(`${key}: archive is missing the CycloneDX SBOM`);
  }
  console.log(`${key}: verified ${platform.sha256}`);
}
NODE

if [ "${FTG_SKIP_SECURITY_SCAN:-0}" != "1" ]; then
  "$ROOT/distribution/security-scan.sh"
fi
