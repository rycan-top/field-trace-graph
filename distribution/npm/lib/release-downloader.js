// 作用：按发布清单下载平台归档。
// 使用场景：首次安装或升级时获取对应产物。
'use strict';

const crypto = require('crypto');
const fs = require('fs');
const https = require('https');
const path = require('path');
const { URL } = require('url');

const MAX_DOWNLOAD_BYTES = 512 * 1024 * 1024;
const STALE_PARTIAL_MILLIS = 60 * 60 * 1000;
const PROGRESS_INTERVAL_MILLIS = 500;
const partialFiles = new Set();

// 作用：把启动器自身的进度写到 stderr；逻辑：stdout 只留给被转发的 CLI 输出。
function note(message) {
  process.stderr.write(`field-trace-graph: ${message}\n`);
}

// 作用：人类可读的字节数；逻辑：按 1024 进制取最大合适单位。
function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) {
    return null;
  }
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
}

// 作用：清理未完成的下载；逻辑：尽力删除本次进程登记过的残片。
function removePartialFiles() {
  for (const file of partialFiles) {
    try {
      fs.rmSync(file, { force: true });
    } catch (error) {
      // 清理失败不应覆盖原始错误。
    }
  }
  partialFiles.clear();
}

// 作用：进程退出或被中断时不留残片；逻辑：只在确实有未完成下载时接管信号，
// 避免影响启动器向已启动的 CLI 子进程转发信号。
process.on('exit', removePartialFiles);
for (const signal of ['SIGINT', 'SIGTERM', 'SIGHUP']) {
  process.on(signal, () => {
    if (partialFiles.size === 0) {
      return;
    }
    removePartialFiles();
    process.exit(signal === 'SIGINT' ? 130 : 143);
  });
}

// 作用：清掉上次异常退出留下的残片；逻辑：只删超过一小时的，避免误伤并发下载。
function removeStalePartialFiles(destination) {
  const directory = path.dirname(destination);
  const prefix = `${path.basename(destination)}.part-`;
  let entries;
  try {
    entries = fs.readdirSync(directory);
  } catch (error) {
    return;
  }
  for (const name of entries) {
    if (!name.startsWith(prefix)) {
      continue;
    }
    const file = path.join(directory, name);
    if (partialFiles.has(file)) {
      continue;
    }
    try {
      if (Date.now() - fs.statSync(file).mtimeMs < STALE_PARTIAL_MILLIS) {
        continue;
      }
      fs.rmSync(file, { force: true });
      note(`removed stale partial download ${name}`);
    } catch (error) {
      // 残片清理是尽力而为。
    }
  }
}

function download(manifest, entry, destination) {
  const baseUrl = manifest.baseUrl;
  const normalizedBase = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  const url = new URL(entry.archive, normalizedBase);
  removeStalePartialFiles(destination);
  return fetch(url, entry, destination, 0);
}

function fetch(url, entry, destination, redirects) {
  if (url.protocol !== 'https:') {
    throw new Error(`Refusing non-HTTPS download source: ${url.href}`);
  }
  if (redirects > 5) {
    throw new Error(`Too many redirects while downloading ${entry.archive}`);
  }

  const partial = `${destination}.part-${process.pid}`;
  partialFiles.add(partial);
  return new Promise((resolve, reject) => {
    let progressShown = false;
    const clearProgress = () => {
      if (progressShown && process.stderr.isTTY) {
        process.stderr.write('\r\x1b[K');
      }
      progressShown = false;
    };
    const fail = (error) => {
      clearProgress();
      partialFiles.delete(partial);
      fs.rmSync(partial, { force: true });
      reject(error);
    };
    const succeed = (value) => {
      clearProgress();
      partialFiles.delete(partial);
      resolve(value);
    };
    const request = https.get(url, (response) => {
      if (response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
        response.resume();
        partialFiles.delete(partial);
        try {
          resolve(fetch(new URL(response.headers.location, url), entry, destination, redirects + 1));
        } catch (error) {
          fail(error);
        }
        return;
      }
      if (response.statusCode !== 200) {
        response.resume();
        fail(new Error(`Download failed with status ${response.statusCode}`));
        return;
      }

      const expectedBytes = Number(response.headers['content-length']) || 0;
      const expectedText = formatBytes(expectedBytes);
      const startedAt = Date.now();
      let lastReportAt = startedAt;
      let progressWidth = 0;
      note(`downloading ${entry.archive}${expectedText ? ` (${expectedText})` : ''}...`);
      progressShown = true;

      const digest = crypto.createHash('sha256');
      const output = fs.createWriteStream(partial, { flags: 'wx' });
      let totalBytes = 0;
      response.on('data', (chunk) => {
        totalBytes += chunk.length;
        if (totalBytes > MAX_DOWNLOAD_BYTES) {
          request.destroy(new Error('Download exceeds the size limit'));
          return;
        }
        digest.update(chunk);
        if (process.stderr.isTTY && expectedBytes > 0
            && Date.now() - lastReportAt >= PROGRESS_INTERVAL_MILLIS) {
          lastReportAt = Date.now();
          const percent = Math.min(100, Math.floor((totalBytes / expectedBytes) * 100));
          const line = `  ${percent}% (${formatBytes(totalBytes)} / ${expectedText})`;
          const padding = ' '.repeat(Math.max(0, progressWidth - line.length));
          progressWidth = line.length;
          process.stderr.write(`\r${line}${padding}`);
        }
      });
      response.pipe(output);

      output.on('close', () => {
        const actual = digest.digest('hex');
        if (actual !== entry.sha256) {
          fail(new Error(`Checksum mismatch for ${entry.archive}`));
          return;
        }
        try {
          fs.renameSync(partial, destination);
          clearProgress();
          note(`downloaded ${entry.archive} (${formatBytes(totalBytes)}) `
            + `in ${((Date.now() - startedAt) / 1000).toFixed(1)}s`);
          succeed(destination);
        } catch (error) {
          if (verifyArchive(entry, destination)) {
            fs.rmSync(partial, { force: true });
            succeed(destination);
            return;
          }
          fail(error);
        }
      });
      output.on('error', fail);
    });

    request.on('error', fail);
    request.setTimeout(120000, () => {
      request.destroy(new Error('Download timed out'));
    });
  });
}

function verifyArchive(entry, archive) {
  try {
    const digest = crypto.createHash('sha256').update(fs.readFileSync(archive)).digest('hex');
    return digest === entry.sha256;
  } catch (error) {
    return false;
  }
}

module.exports = { download, verifyArchive };
