// 作用：解压并安装平台归档。
// 使用场景：下载完成后把运行时落到本地安装目录。
'use strict';

const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

const MAX_ENTRIES = 20000;
const MAX_ARCHIVE_BYTES = 512 * 1024 * 1024;
const MAX_TOTAL_BYTES = 1024 * 1024 * 1024;
const LOCK_TIMEOUT_MILLIS = 60000;

function install(archive, version, installRoot) {
  const staging = `${installRoot}.staging-${crypto.randomBytes(6).toString('hex')}`;
  const lock = `${installRoot}.lock`;

  acquireLock(lock);
  try {
    if (isInstalled(installRoot, version)) {
      return installRoot;
    }
    fs.rmSync(staging, { recursive: true, force: true });
    fs.mkdirSync(staging, { recursive: true });
    extract(archive, staging);
    if (!isInstalled(staging, version)) {
      throw new Error('Archive does not contain a complete application installation');
    }
    fs.rmSync(installRoot, { recursive: true, force: true });
    fs.renameSync(staging, installRoot);
    return installRoot;
  } catch (error) {
    fs.rmSync(staging, { recursive: true, force: true });
    throw error;
  } finally {
    releaseLock(lock);
  }
}

function extract(archive, destination) {
  const archiveBytes = fs.statSync(archive).size;
  if (archiveBytes > MAX_ARCHIVE_BYTES) {
    throw new Error('Archive size exceeds the limit');
  }
  validateArchiveEntries(listEntries(archive));

  const isZip = archive.endsWith('.zip');
  if (!isZip) {
    validateTarLinkTypes(archive);
  }
  const extraction = isZip ? extractZip(archive, destination) : extractTar(archive, destination);
  if (extraction.error || extraction.status !== 0) {
    throw new Error(`Unable to extract ${archive}`);
  }
  validateTree(destination);
}

function validateTarLinkTypes(archive) {
  const result = spawnSync('tar', ['-tvzf', archive], { encoding: 'utf8' });
  if (result.error || result.status !== 0) {
    throw new Error(`Unable to inspect ${archive}`);
  }
  for (const line of result.stdout.split(/\r?\n/)) {
    if (line.startsWith('h')) {
      throw new Error('Archive contains a hard link');
    }
  }
}

function listEntries(archive) {
  const result = archive.endsWith('.zip') ? listZipEntries(archive) : listTarEntries(archive);
  if (result.error || result.status !== 0) {
    throw new Error(`Unable to inspect ${archive}`);
  }
  return result.stdout.split(/\r?\n/).filter(Boolean);
}

function validateArchiveEntries(entries) {
  if (entries.length > MAX_ENTRIES) {
    throw new Error('Archive entry count exceeds the limit');
  }
  for (const entry of entries) {
    const normalized = entry.replace(/\\/g, '/');
    const segments = normalized.split('/').filter((segment) => segment && segment !== '.');
    if (normalized.startsWith('/') || /^[A-Za-z]:/.test(normalized) || segments.includes('..')) {
      throw new Error(`Archive entry escapes the install directory: ${entry}`);
    }
  }
}

function listTarEntries(archive) {
  return spawnSync('tar', ['-tzf', archive], { encoding: 'utf8' });
}

function extractTar(archive, destination) {
  return spawnSync('tar', ['-xzf', archive, '-C', destination], { stdio: 'ignore' });
}

function listZipEntries(archive) {
  if (process.platform !== 'win32') {
    return spawnSync('unzip', ['-Z1', archive], { encoding: 'utf8' });
  }
  return spawnSync('powershell.exe', [
    '-NoProfile',
    '-NonInteractive',
    '-Command',
    'Add-Type -AssemblyName System.IO.Compression.FileSystem; ' +
      '$zip=[IO.Compression.ZipFile]::OpenRead($env:FTG_ARCHIVE); ' +
      'try { $zip.Entries | ForEach-Object { $_.FullName } } finally { $zip.Dispose() }'
  ], {
    encoding: 'utf8',
    env: { ...process.env, FTG_ARCHIVE: archive }
  });
}

function extractZip(archive, destination) {
  if (process.platform !== 'win32') {
    return spawnSync('unzip', ['-q', archive, '-d', destination], { stdio: 'ignore' });
  }
  return spawnSync('powershell.exe', [
    '-NoProfile',
    '-NonInteractive',
    '-Command',
    'Add-Type -AssemblyName System.IO.Compression.FileSystem; ' +
      '[IO.Compression.ZipFile]::ExtractToDirectory($env:FTG_ARCHIVE, $env:FTG_DEST)'
  ], {
    stdio: 'ignore',
    env: { ...process.env, FTG_ARCHIVE: archive, FTG_DEST: destination }
  });
}

function validateTree(root) {
  let entries = 0;
  let totalBytes = 0;
  const visited = new Set();
  const canonicalRoot = path.resolve(root);

  function walk(directory) {
    const real = fs.realpathSync(directory);
    if (visited.has(real)) {
      throw new Error('Archive contains a symlink cycle');
    }
    visited.add(real);
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const absolute = path.join(directory, entry.name);
      entries += 1;
      if (entries > MAX_ENTRIES) {
        throw new Error('Archive entry count exceeds the limit');
      }
      if (entry.isSymbolicLink()) {
        const target = fs.readlinkSync(absolute);
        const resolved = path.resolve(path.dirname(absolute), target);
        if (!resolved.startsWith(canonicalRoot + path.sep) && resolved !== canonicalRoot) {
          throw new Error('Archive symlink escapes the install directory');
        }
      } else if (entry.isDirectory()) {
        walk(absolute);
      } else {
        totalBytes += fs.statSync(absolute).size;
        if (totalBytes > MAX_TOTAL_BYTES) {
          throw new Error('Archive total size exceeds the limit');
        }
      }
    }
  }

  walk(root);
}

function acquireLock(lock) {
  fs.mkdirSync(path.dirname(lock), { recursive: true });
  const start = Date.now();
  while (true) {
    try {
      fs.writeFileSync(lock, String(process.pid), { flag: 'wx' });
      return;
    } catch (error) {
      if (error.code !== 'EEXIST') {
        throw error;
      }
      if (removeStaleLock(lock)) {
        continue;
      }
      if (Date.now() - start > LOCK_TIMEOUT_MILLIS) {
        throw new Error('Timed out waiting for the install lock');
      }
      sleep(100);
    }
  }
}

function releaseLock(lock) {
  try {
    fs.unlinkSync(lock);
  } catch (error) {
    if (error.code !== 'ENOENT') {
      throw error;
    }
  }
}

function isInstalled(installRoot, version) {
  const marker = path.join(installRoot, '.version');
  const launcher = path.join(
    installRoot,
    'bin',
    process.platform === 'win32' ? 'field-trace-graph.bat' : 'field-trace-graph'
  );
  const jar = path.join(installRoot, 'lib', 'field-trace-graph.jar');
  const java = path.join(
    installRoot,
    'runtime',
    'bin',
    process.platform === 'win32' ? 'java.exe' : 'java'
  );
  try {
    return fs.readFileSync(marker, 'utf8').trim() === version
      && fs.statSync(launcher).isFile()
      && fs.statSync(jar).isFile()
      && fs.statSync(java).isFile();
  } catch (error) {
    return false;
  }
}

function removeStaleLock(lock) {
  try {
    const owner = Number.parseInt(fs.readFileSync(lock, 'utf8').trim(), 10);
    if (!Number.isInteger(owner) || owner <= 0 || !isProcessAlive(owner)) {
      fs.unlinkSync(lock);
      return true;
    }
  } catch (error) {
    if (error.code === 'ENOENT') {
      return true;
    }
  }
  return false;
}

function isProcessAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error.code === 'EPERM';
  }
}

function sleep(milliseconds) {
  const buffer = new Int32Array(new SharedArrayBuffer(4));
  Atomics.wait(buffer, 0, 0, milliseconds);
}

module.exports = { install, isInstalled, validateArchiveEntries };
