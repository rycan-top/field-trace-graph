// 作用：打包前同步 npm 包内的发布清单。
// 使用场景：执行 npm pack 之前。
'use strict';

const fs = require('fs');
const path = require('path');

const source = path.join(__dirname, '..', '..', 'release-manifest.json');
const destination = path.join(__dirname, '..', 'release-manifest.json');

fs.copyFileSync(source, destination);
