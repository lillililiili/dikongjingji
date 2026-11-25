#!/usr/bin/env node
'use strict';

const fs = require('node:fs');
const path = require('node:path');

const repo = path.resolve(__dirname, '../..');
const staticRoot = path.join(repo, 'apps/mobile/src/static');
const seedRoot = path.join(repo, 'backend/bootstrap/src/main/resources/db/development');
const directories = ['images/home', 'images/inspection', 'images/preview', 'videos/preview'];
const prefix = '/api/v1/dev-media/';
const compare = (a, b) => a < b ? -1 : a > b ? 1 : 0;
const relative = file => path.relative(repo, file).split(path.sep).join('/');

function walk(dir, include) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry => {
    const file = path.join(dir, entry.name);
    return entry.isDirectory() ? walk(file, include) : include(file) ? [file] : [];
  }).sort(compare);
}

const assets = new Map();
for (const dir of directories) {
  const folder = path.join(staticRoot, dir);
  for (const file of walk(folder, file => path.dirname(file) === folder && /\.(?:jpg|png|webp|mp4)$/i.test(file))) {
    const key = path.relative(staticRoot, file).split(path.sep).join('/');
    assets.set(key, { path: key, bytes: fs.statSync(file).size, seeds: [] });
  }
}

// Read both literal media references and the prefix + VALUES/CASE expressions in the repeatable seeds.
// Historical live-room INSERTs are upgraded to API paths by the subsequent live seed.
for (const file of walk(seedRoot, file => file.endsWith('.sql'))) {
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
  let dir = null;
  let table = '';
  let field = '';
  let record = '';
  lines.forEach((line, i) => {
    const sql = line.trim();
    if (!sql || sql.startsWith('--')) return;
    const statement = /^(?:UPDATE|INSERT\s+INTO)\s+(\w+)/i.exec(sql);
    if (statement) {
      table = statement[1];
      field = '';
      dir = null;
      record = '';
    }
    const set = /\bSET\s+(\w+)\s*=/i.exec(sql);
    if (set) field = set[1];
    const folder = /'\/api\/v1\/dev-media\/(images\/(?:home|preview|inspection)|videos\/preview)\/'\s*\|\|/.exec(sql);
    if (folder) dir = folder[1];
    const id = /^\(\s*'([0-9a-f-]{36})'/.exec(sql);
    if (id) record = id[1];
    for (const match of sql.matchAll(/'([^']+\.(?:jpg|png|webp|mp4))'/gi)) {
      const literal = match[1];
      let key;
      if (literal.startsWith(prefix)) key = literal.slice(prefix.length);
      else if (literal.startsWith('/static/videos/preview/')) key = literal.slice('/static/'.length);
      else if (dir && /^[a-z0-9_-]+\.[a-z0-9]+$/i.test(literal)) key = dir + '/' + literal;
      else continue;
      const asset = assets.get(key);
      if (!asset) throw new Error(relative(file) + ':' + (i + 1) + ' 引用了不存在的文件 ' + key);
      asset.seeds.push({ file: relative(file), line: i + 1, table, field, record: record || null });
    }
    if (sql.endsWith(';')) { dir = null; record = ''; }
  });
}

const rows = [...assets.values()].sort((a, b) => compare(a.path, b.path));
const used = rows.filter(row => row.seeds.length);
const args = process.argv.slice(2);
let output;
if (args.includes('--json')) {
  output = JSON.stringify(rows, null, 2) + '\n';
} else {
  const lines = ['# 开发媒体与数据库引用清单', '',
    '由 `node infra/scripts/preview-assets-report.cjs --out docs/ops/授权图片替换-清单.md` 生成。种子或文件变化后重新运行。',
    '', '共有 ' + rows.length + ' 个文件，其中 ' + used.length + ' 个有开发 SQL 引用，' +
      (rows.length - used.length) + ' 个没有引用。下表记录仓库里的开发种子。当前数据库内容可以由运营更新。',
    '', '| 文件 | 大小 | 数据库与种子位置 |', '| --- | ---: | --- |'];
  for (const row of rows) {
    const references = row.seeds.map(ref => {
      const label = ref.table + (ref.field ? '.' + ref.field : '') + (ref.record ? ' · ' + ref.record : '');
      return '`' + label + '` [' + ref.file.replace('backend/bootstrap/src/main/resources/db/development/', '') +
        ':' + ref.line + '](../../' + ref.file + '#L' + ref.line + ')';
    });
    lines.push('| `' + row.path + '` | ' + Math.ceil(row.bytes / 1024) + ' KiB | ' + (references.join('<br>') || '无开发种子引用') + ' |');
  }
  output = lines.join('\n') + '\n';
}
const out = args.indexOf('--out');
if (out === -1) process.stdout.write(output);
else if (args[out + 1]) fs.writeFileSync(path.resolve(args[out + 1]), output);
else throw new Error('--out 需要文件路径');
