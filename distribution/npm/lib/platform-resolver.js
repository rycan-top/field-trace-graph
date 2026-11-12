// 作用：识别操作系统与 CPU 架构。
// 使用场景：为当前机器选择正确的发布归档。
'use strict';

const os = require('os');

const SUPPORTED = new Set([
  'darwin-arm64',
  'darwin-x64',
  'linux-x64',
  'win32-x64'
]);

function current() {
  const platform = os.platform();
  const arch = os.arch();
  const key = `${platform}-${arch}`;
  if (!SUPPORTED.has(key)) {
    throw new Error(`Unsupported platform: ${key}`);
  }
  return { os: platform, cpu: arch };
}

function match(platforms) {
  const target = current();
  const entry = platforms.find(
    (candidate) => candidate.os === target.os && candidate.cpu === target.cpu
  );
  if (!entry) {
    throw new Error(`No release for ${target.os}-${target.cpu}`);
  }
  return entry;
}

module.exports = { current, match, SUPPORTED };
