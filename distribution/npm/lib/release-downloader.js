// 作用：按发布清单下载平台归档。
// 使用场景：首次安装或升级时获取对应产物。
'use strict';

const crypto = require('crypto');
const fs = require('fs');
const https = require('https');
const path = require('path');
const { URL } = require('url');

const MAX_DOWNLOAD_BYTES = 512 * 1024 * 1024;

function download(manifest, entry, destination) {
  const baseUrl = manifest.baseUrl;
  const normalizedBase = baseUrl.endsWith('/') ? baseUrl : `${baseUrl}/`;
  const url = new URL(entry.archive, normalizedBase);
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
  return new Promise((resolve, reject) => {
    const fail = (error) => {
      fs.rmSync(partial, { force: true });
      reject(error);
    };
    const request = https.get(url, (response) => {
      if (response.statusCode >= 300 && response.statusCode < 400 && response.headers.location) {
        response.resume();
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
          resolve(destination);
        } catch (error) {
          if (verifyArchive(entry, destination)) {
            fs.rmSync(partial, { force: true });
            resolve(destination);
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
