#!/usr/bin/env bash
# 作用：一次性更新发布版本并校验所有版本来源。
# 使用场景：发版前执行 `scripts/bump-version.sh 0.2.0`。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NEW_VERSION="${1:-}"

usage() {
  cat >&2 <<'USAGE'
usage: scripts/bump-version.sh <version>

Updates the single Maven revision, both manifests, and the npm package, then
regenerates the npm manifest copy and validates every version source.

example: scripts/bump-version.sh 0.2.0
USAGE
}

if [ -z "$NEW_VERSION" ]; then
  usage
  exit 2
fi
NEW_VERSION="${NEW_VERSION#v}"
if ! printf '%s' "$NEW_VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([0-9A-Za-z.+-]*)$'; then
  echo "invalid version: $NEW_VERSION (expected semantic version, e.g. 0.2.0)" >&2
  exit 2
fi

for tool in node sed grep; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "required tool not found: $tool" >&2
    exit 2
  }
done

cd "$ROOT"

OLD_VERSION="$(sed -n 's:.*<revision>\(.*\)</revision>.*:\1:p' pom.xml | head -1)"
if [ -z "$OLD_VERSION" ]; then
  echo "root pom.xml has no <revision> property" >&2
  exit 1
fi
SAME_VERSION=0
if [ "$OLD_VERSION" = "$NEW_VERSION" ]; then
  # 不早退：POM 可能已经提升而 manifest / npm 包仍停在旧版本（一次只做了一半的
  # 版本升级），继续执行才能把其余版本来源补齐并通过一致性校验。
  echo "pom.xml revision is already $NEW_VERSION; re-syncing dependent sources"
  SAME_VERSION=1
fi

node - "$ROOT/pom.xml" "$NEW_VERSION" <<'NODE'
const fs = require('fs');
const [file, version] = process.argv.slice(2);
const xml = fs.readFileSync(file, 'utf8');
if (!/<revision>[^<]+<\/revision>/.test(xml)) {
  throw new Error('root pom.xml has no <revision> property');
}
fs.writeFileSync(
  file,
  xml.replace(/<revision>[^<]+<\/revision>/, `<revision>${version}</revision>`)
);
NODE

node - "$ROOT/distribution/release-manifest.json" "$NEW_VERSION" <<'NODE'
const fs = require('fs');
const [file, version] = process.argv.slice(2);
const manifest = JSON.parse(fs.readFileSync(file, 'utf8'));
const previousBase = manifest.baseUrl || '';
const baseUrl = /\/v[^/]+$/.test(previousBase)
  ? previousBase.replace(/\/v[^/]+$/, `/v${version}`)
  : `https://github.com/rycan-top/field-trace-graph/releases/download/v${version}`;
manifest.version = version;
manifest.baseUrl = baseUrl;
for (const platform of manifest.platforms) {
  const extension = platform.os === 'win32' ? 'zip' : 'tar.gz';
  platform.archive = `field-trace-graph-${version}-${platform.os}-${platform.cpu}.${extension}`;
  platform.sha256 = '0'.repeat(64);
}
fs.writeFileSync(file, `${JSON.stringify(manifest, null, 2)}\n`);
NODE

node - "$ROOT/distribution/npm/package.json" "$NEW_VERSION" <<'NODE'
const fs = require('fs');
const [file, version] = process.argv.slice(2);
const pkg = JSON.parse(fs.readFileSync(file, 'utf8'));
pkg.version = version;
fs.writeFileSync(file, `${JSON.stringify(pkg, null, 2)}\n`);
NODE

node "$ROOT/distribution/npm/scripts/sync-manifest.js"

node - "$ROOT" "$NEW_VERSION" <<'NODE'
const fs = require('fs');
const path = require('path');
const [root, version] = process.argv.slice(2);
const failures = [];
const read = (relative) => fs.readFileSync(path.join(root, relative), 'utf8');
const json = (relative) => JSON.parse(read(relative));

const pom = read('pom.xml');
const revision = (pom.match(/<revision>([^<]+)<\/revision>/) || [])[1];
if (revision !== version) {
  failures.push(`pom.xml revision=${revision || '<missing>'}`);
}
for (const module of ['lineage-core', 'lineage-java', 'lineage-storage-sqlite', 'lineage-cli']) {
  const modulePom = read(`${module}/pom.xml`);
  if (!/<artifactId>field-trace-graph-parent<\/artifactId>\s*<version>\$\{revision\}<\/version>/.test(modulePom)) {
    failures.push(`${module}/pom.xml parent version is not \${revision}`);
  }
}

const manifest = json('distribution/release-manifest.json');
if (manifest.version !== version) {
  failures.push(`release-manifest version=${manifest.version}`);
}
if (!manifest.baseUrl.endsWith(`/v${version}`)) {
  failures.push(`release-manifest baseUrl=${manifest.baseUrl}`);
}
for (const platform of manifest.platforms) {
  const extension = platform.os === 'win32' ? 'zip' : 'tar.gz';
  const expected = `field-trace-graph-${version}-${platform.os}-${platform.cpu}.${extension}`;
  if (platform.archive !== expected) {
    failures.push(`${platform.os}-${platform.cpu} archive=${platform.archive}`);
  }
  if (!/^0{64}$/.test(platform.sha256)) {
    failures.push(`${platform.os}-${platform.cpu} sha256 was not reset`);
  }
}

const npm = json('distribution/npm/package.json');
if (npm.version !== version) {
  failures.push(`npm package version=${npm.version}`);
}
const npmManifest = json('distribution/npm/release-manifest.json');
if (npmManifest.version !== version || npmManifest.baseUrl !== manifest.baseUrl) {
  failures.push('npm release-manifest copy is out of sync');
}

if (failures.length > 0) {
  throw new Error(`version validation failed:\n- ${failures.join('\n- ')}`);
}
console.log(`version sources are consistent at ${version}`);
NODE

if git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  git diff --check
fi

if [ "$SAME_VERSION" = 1 ]; then
  echo "version sources synced at $NEW_VERSION"
else
  echo "bumped $OLD_VERSION -> $NEW_VERSION"
fi
git diff --stat -- pom.xml distribution/release-manifest.json distribution/npm/package.json distribution/npm/release-manifest.json
