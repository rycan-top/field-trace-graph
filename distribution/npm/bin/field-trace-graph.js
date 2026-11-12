#!/usr/bin/env node
// 作用：npm 安装后的命令入口，转交本地启动器。
// 使用场景：执行 ftg 或 field-trace-graph 命令时。
'use strict';

const fs = require('fs');
const os = require('os');
const path = require('path');
const { spawn } = require('child_process');

const { match } = require('../lib/platform-resolver');
const { download, verifyArchive } = require('../lib/release-downloader');
const { install, isInstalled } = require('../lib/archive-installer');

function manifestPath() {
  return path.join(__dirname, '..', 'release-manifest.json');
}

function cacheRoot() {
  return path.join(os.homedir(), '.field-trace-graph', 'dist');
}

function readManifest() {
  const manifest = JSON.parse(fs.readFileSync(manifestPath(), 'utf8'));
  if (manifest.schemaVersion !== 1) {
    throw new Error('Unsupported release manifest schema');
  }
  if (typeof manifest.version !== 'string'
      || !/^[0-9A-Za-z][0-9A-Za-z._-]*$/.test(manifest.version)) {
    throw new Error('Release manifest has an invalid version');
  }
  if (typeof manifest.baseUrl !== 'string' || !Array.isArray(manifest.platforms)) {
    throw new Error('Release manifest is incomplete');
  }
  for (const entry of manifest.platforms) {
    if (typeof entry.archive !== 'string' || !/^[0-9A-Za-z._-]+$/.test(entry.archive)) {
      throw new Error(`Release manifest has an invalid archive name: ${entry.archive}`);
    }
    if (typeof entry.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(entry.sha256)) {
      throw new Error(`Release manifest has an invalid sha256: ${entry.archive}`);
    }
  }
  return manifest;
}

async function ensureRelease(manifest) {
  const entry = match(manifest.platforms);
  const versionRoot = path.join(cacheRoot(), manifest.version);

  if (isInstalled(versionRoot, manifest.version)) {
    return versionRoot;
  }

  fs.mkdirSync(cacheRoot(), { recursive: true });
  const archive = path.join(cacheRoot(), entry.archive);
  if (fs.existsSync(archive) && !verifyArchive(entry, archive)) {
    fs.rmSync(archive, { force: true });
  }
  if (!fs.existsSync(archive)) {
    await download(manifest, entry, archive);
  }
  install(archive, manifest.version, versionRoot);
  return versionRoot;
}

function launch(versionRoot) {
  const executable = path.join(
    versionRoot,
    'bin',
    process.platform === 'win32' ? 'field-trace-graph.bat' : 'field-trace-graph'
  );
  if (!fs.existsSync(executable)) {
    throw new Error(`Release does not contain the launcher: ${executable}`);
  }

  const child = spawn(executable, process.argv.slice(2), {
    stdio: 'inherit',
    // .bat files require cmd.exe on Windows; shell mode provides that bridge.
    shell: process.platform === 'win32'
  });
  for (const signal of ['SIGINT', 'SIGTERM']) {
    process.on(signal, () => child.kill(signal));
  }
  child.on('exit', (code, signal) => {
    if (signal) {
      process.kill(process.pid, signal);
      return;
    }
    process.exit(code === null ? 1 : code);
  });
}

async function main() {
  const manifest = readManifest();
  const versionRoot = await ensureRelease(manifest);
  launch(versionRoot);
}

main().catch((error) => {
  process.stderr.write(`field-trace-graph: ${error.message}\n`);
  process.exit(1);
});
