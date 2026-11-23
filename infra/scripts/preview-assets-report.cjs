#!/usr/bin/env node
'use strict';

// 预览图片和视频清单（S2-2，说明见 docs/ops/授权图片替换.md）。只读仓库里的文件，不连任何服务。
//
//   node infra/scripts/preview-assets-report.cjs                 输出 Markdown 到标准输出
//   node infra/scripts/preview-assets-report.cjs --out <文件>    写到文件
//   node infra/scripts/preview-assets-report.cjs --json          输出 JSON（给以后的运营端或检查脚本用）
//
// 列出 apps/mobile/src/static/images/preview/ 和 static/videos/preview/ 的每个文件：
//   - 哪个示例图模块（apps/mobile/src/features/*/data/preview/*.ts）按哪个键用到它，键是开发种子 ID 时给出种子文件和名称；
//   - 哪些页面（src/pages/**）经 import 链用到那个模块；
//   - 后端代码或开发种子里直接写了它的地方；
// 再列出服务端可以填受控 HTTPS 地址、让示例图让位的字段（迁移里的 *_url 列），以及没有被引用的文件。

const fs = require('node:fs');
const path = require('node:path');

const REPO = path.resolve(__dirname, '../..');
const MOBILE = path.join(REPO, 'apps/mobile');
const SRC = path.join(MOBILE, 'src');
const ASSET_DIRS = ['static/images/preview', 'static/videos/preview'];
const SEED_DIR = path.join(REPO, 'backend/bootstrap/src/main/resources/db/development');
const MIGRATIONS = path.join(REPO, 'backend/modules');
const BACKEND_MAIN = path.join(REPO, 'backend');
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const rel = (file) => path.relative(REPO, file).split(path.sep).join('/');

function walk(dir, filter, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === 'target' || entry.name === 'dist') continue;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) walk(full, filter, out);
    else if (filter(full)) out.push(full);
  }
  return out;
}

// ---- 资源 ----

function listAssets() {
  return ASSET_DIRS.flatMap((dir) => walk(path.join(SRC, dir), () => true).map((file) => ({
    file,
    publicPath: '/' + path.relative(SRC, file).split(path.sep).join('/'),
    name: path.basename(file),
    stem: path.basename(file).replace(/\.[^.]+$/, ''),
    bytes: fs.statSync(file).size,
    uses: [],
    pages: new Set(),
    backend: [],
  })));
}

// ---- 示例图模块里的映射 ----

/** 每一行里出现的资源：按文件名、去掉扩展名的名字，以及视频模块的 slug（<slug>.mp4、video-<slug>.jpg）。 */
function assetsOnLine(line, assets, isVideoModule) {
  const quoted = [...line.matchAll(/'([^']+)'/g)].map((m) => m[1]);
  const found = new Set();
  for (const value of quoted) {
    for (const asset of assets) {
      if (value === asset.name || value === asset.stem) found.add(asset);
      if (isVideoModule && (asset.name === `${value}.mp4` || asset.name === `video-${value}.jpg`)) found.add(asset);
    }
  }
  return [...found];
}

function readModuleUses(assets) {
  const modules = walk(SRC, (file) => /[\\/]data[\\/]preview[\\/][^\\/]+\.ts$/.test(file) && !file.endsWith('.test.ts'));
  const result = [];
  for (const file of modules) {
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
    const isVideoModule = /videos/.test(path.basename(file));
    let mapName = null;
    let used = false;
    lines.forEach((line, index) => {
      const declared = /^\s*const ([A-Z_]+)\b/.exec(line);
      if (declared) mapName = declared[1];
      const key = /^\s*(?:'([^']+)'|([^\s'":]+))\s*:/.exec(line);
      const keyText = key ? key[1] || key[2] : null;
      for (const asset of assetsOnLine(line, assets, isVideoModule)) {
        used = true;
        asset.uses.push({ module: file, map: mapName, key: keyText, line: index + 1 });
      }
      // 路径直接写在代码里的
      for (const asset of assets) {
        if (line.includes(asset.publicPath) && !asset.uses.some((use) => use.module === file && use.line === index + 1)) {
          used = true;
          asset.uses.push({ module: file, map: mapName, key: null, line: index + 1 });
        }
      }
    });
    if (used) result.push(file);
  }
  // 示例图模块以外直接写路径的地方（例如首页轮播）
  for (const file of walk(SRC, (f) => /\.(ts|vue)$/.test(f) && !/\.test\.ts$/.test(f) && !/[\\/]data[\\/]preview[\\/]/.test(f))) {
    const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/);
    lines.forEach((line, index) => {
      for (const asset of assets) {
        if (line.includes(`preview/${asset.name}`) || line.includes(`preview/${asset.stem}'`)) {
          asset.uses.push({ module: file, map: null, key: null, line: index + 1 });
        }
      }
    });
  }
  return result;
}

// ---- import 链：哪些页面用到某个模块 ----

function resolveImport(from, spec) {
  let base;
  if (spec.startsWith('@/')) base = path.join(SRC, spec.slice(2));
  else if (spec.startsWith('.')) base = path.resolve(path.dirname(from), spec);
  else return null;
  base = base.replace(/\?.*$/, '');
  for (const candidate of [base, `${base}.ts`, `${base}.vue`, path.join(base, 'index.ts')]) {
    if (fs.existsSync(candidate) && fs.statSync(candidate).isFile()) return candidate;
  }
  return null;
}

function importGraph() {
  const importers = new Map();
  for (const file of walk(SRC, (f) => /\.(ts|vue)$/.test(f) && !/\.test\.ts$/.test(f) && !/[\\/]tests[\\/]/.test(f))) {
    const text = fs.readFileSync(file, 'utf8');
    for (const match of text.matchAll(/(?:from\s+|import\s*\(\s*|import\s+)'([^']+)'/g)) {
      const target = resolveImport(file, match[1]);
      if (!target) continue;
      if (!importers.has(target)) importers.set(target, new Set());
      importers.get(target).add(file);
    }
  }
  return importers;
}

function pagesUsing(file, importers) {
  const pages = new Set();
  const seen = new Set([file]);
  const queue = [file];
  while (queue.length) {
    const current = queue.shift();
    if (current.startsWith(path.join(SRC, 'pages') + path.sep) && current.endsWith('.vue')) {
      pages.add('/' + path.relative(SRC, current).split(path.sep).join('/').replace(/\.vue$/, ''));
    }
    for (const next of importers.get(current) || []) {
      if (!seen.has(next)) { seen.add(next); queue.push(next); }
    }
  }
  return pages;
}

// ---- 开发种子 ----

function seedIndex() {
  const files = walk(SEED_DIR, (f) => f.endsWith('.sql')).map((file) => ({ file, lines: fs.readFileSync(file, 'utf8').split(/\r?\n/) }));
  const cache = new Map();
  return (id) => {
    if (!UUID.test(id)) return null;
    if (cache.has(id)) return cache.get(id);
    let found = null;
    for (const { file, lines } of files) {
      const index = lines.findIndex((line) => line.includes(`('${id}'`));
      if (index < 0) continue;
      let title = null;
      for (let i = index; i < Math.min(lines.length, index + 6) && !title; i += 1) {
        const text = [...lines[i].matchAll(/'([^']*[一-鿿][^']*)'/g)].map((m) => m[1]);
        if (text.length) title = text[0];
      }
      found = { file: rel(file), title };
      break;
    }
    if (!found) {
      const anywhere = files.find(({ lines }) => lines.some((line) => line.includes(id)));
      found = anywhere ? { file: rel(anywhere.file), title: null } : { file: null, title: null };
    }
    cache.set(id, found);
    return found;
  };
}

// ---- 后端直接引用 ----

function backendRefs(assets) {
  const files = walk(BACKEND_MAIN, (f) => /\.(java|sql|yml|yaml)$/.test(f) && !/[\\/]src[\\/]test[\\/]/.test(f));
  for (const file of files) {
    const text = fs.readFileSync(file, 'utf8');
    for (const asset of assets) {
      if (text.includes(asset.publicPath)) asset.backend.push(rel(file));
    }
  }
}

// ---- 服务端可以填地址的字段 ----

function urlColumns() {
  const columns = [];
  for (const file of walk(MIGRATIONS, (f) => /[\\/]migrations[\\/]V\d+__.*\.sql$/.test(f))) {
    let table = null;
    for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
      const create = /(?:CREATE TABLE(?: IF NOT EXISTS)?|ALTER TABLE)\s+([a-z_]+)/i.exec(line);
      if (create) table = create[1];
      const column = /^\s*(?:ADD COLUMN\s+)?([a-z_]*(?:cover|image|poster|replay|play|video)_url[a-z_]*)\s+varchar/i.exec(line);
      if (column && table) {
        columns.push({ table, column: column[1], httpsOnly: /LIKE 'https:\/\/%'/.test(line), migration: rel(file) });
      }
    }
  }
  return columns;
}

// ---- 输出 ----

function describeKey(use, seed) {
  if (!use.key) return '（路径写在代码里）';
  if (UUID.test(use.key)) {
    const found = seed(use.key);
    const where = found && found.file ? `${path.basename(found.file)}${found.title ? `「${found.title}」` : ''}` : '种子里没找到';
    return `\`${use.key}\` ${where}`;
  }
  return `按 \`${use.key}\``;
}

function main() {
  const args = process.argv.slice(2);
  const assets = listAssets();
  readModuleUses(assets);
  const importers = importGraph();
  for (const asset of assets) {
    for (const use of asset.uses) for (const page of pagesUsing(use.module, importers)) asset.pages.add(page);
  }
  backendRefs(assets);
  const seed = seedIndex();
  const columns = urlColumns();

  if (args.includes('--json')) {
    const json = assets.map((asset) => ({
      path: asset.publicPath,
      bytes: asset.bytes,
      uses: asset.uses.map((use) => ({
        module: rel(use.module), map: use.map, key: use.key, line: use.line,
        seed: use.key && UUID.test(use.key) ? seed(use.key) : null,
      })),
      pages: [...asset.pages].sort(),
      backend: asset.backend,
    }));
    process.stdout.write(JSON.stringify({ assets: json, urlColumns: columns }, null, 2) + '\n');
    return;
  }

  const out = [];
  const total = assets.reduce((sum, asset) => sum + asset.bytes, 0);
  out.push('# 预览图片与视频清单（生成）');
  out.push('');
  out.push('> 由 `node infra/scripts/preview-assets-report.cjs --out docs/ops/授权图片替换-清单.md` 生成，不要手改；改了图片或示例图模块后重新生成。');
  out.push('> 规则和替换步骤见 `docs/ops/授权图片替换.md`。“经 import 可能用到它的页面”按 import 链算，比实际显示的页面多。');
  out.push('');
  out.push(`共 ${assets.length} 个文件，${(total / 1024 / 1024).toFixed(1)} MB。`);
  out.push('');
  out.push('### 每个文件');
  out.push('');
  out.push('| 文件 | 大小 | 示例图模块 · 键（开发种子） | 经 import 可能用到它的页面 | 后端直接引用 |');
  out.push('| --- | --- | --- | --- | --- |');
  for (const asset of assets.sort((a, b) => a.publicPath.localeCompare(b.publicPath))) {
    const uses = asset.uses.map((use) => `${path.basename(use.module)}${use.map ? ` ${use.map}` : ''} · ${describeKey(use, seed)}`);
    out.push(`| \`${asset.publicPath.replace('/static/', '')}\` | ${(asset.bytes / 1024).toFixed(0)} KB | ${uses.join('<br>') || '—'} | ${[...asset.pages].sort().map((p) => `\`${p}\``).join('<br>') || '—'} | ${asset.backend.map((f) => `\`${path.basename(f)}\``).join('<br>') || '—'} |`);
  }
  out.push('');
  out.push('### 服务端可以填受控 HTTPS 地址的字段');
  out.push('');
  out.push('| 表 | 字段 | 库里只收 https | 迁移 |');
  out.push('| --- | --- | --- | --- |');
  for (const column of columns.sort((a, b) => `${a.table}.${a.column}`.localeCompare(`${b.table}.${b.column}`))) {
    out.push(`| \`${column.table}\` | \`${column.column}\` | ${column.httpsOnly ? '是（CHECK）' : '否（服务端读出时和 App 都只放行 https）'} | \`${column.migration.replace('backend/modules/', '')}\` |`);
  }
  out.push('');
  const unused = assets.filter((asset) => asset.uses.length === 0 && asset.backend.length === 0);
  out.push('### 没有被引用的文件');
  out.push('');
  out.push(unused.length ? unused.map((asset) => `- \`${asset.publicPath}\``).join('\n') : '无。');
  out.push('');

  const text = out.join('\n');
  const target = args.indexOf('--out');
  if (target >= 0 && args[target + 1]) fs.writeFileSync(path.resolve(args[target + 1]), text);
  else process.stdout.write(text);
}

main();
