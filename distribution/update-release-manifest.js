// 作用：更新发布清单中的下载地址与哈希。
// 使用场景：归档构建完成后写入版本信息。
'use strict';

const fs = require('fs');
const path = require('path');

const [os, cpu, archive, sha256] = process.argv.slice(2);
if (!os || !cpu || !archive || !sha256) {
  process.stderr.write('usage: update-release-manifest.js <os> <cpu> <archive> <sha256>\n');
  process.exit(2);
}

const manifestPath = path.join(__dirname, 'release-manifest.json');
const manifest = JSON.parse(fs.readFileSync(manifestPath, 'utf8'));
const entry = manifest.platforms.find((platform) => platform.os === os && platform.cpu === cpu);
if (!entry) {
  process.stderr.write(`No manifest entry for ${os}/${cpu}\n`);
  process.exit(2);
}

entry.archive = archive;
entry.sha256 = sha256;
fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
