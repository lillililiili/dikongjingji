#!/usr/bin/env node
'use strict';

// S6 场景 7 的备份恢复演练，照 infra/prod/backup/pg-backup.sh、pg-restore.sh --check 的做法：
//   1. 在开发库容器里 pg_dump -Fc -Z 6，经标准输出写到宿主机（.runtime/s6-rehearsal/，被 git 忽略），pg_restore -l 校验能读、记 sha256；
//   2. 另起一个临时 PostGIS 容器（名字 s6-restore-<时间>，同一个 postgis/postgis:16-3.5 镜像，不映射端口），
//      在里面 CREATE DATABASE 后 pg_restore --no-owner --exit-on-error；
//   3. 逐表核对行数（开发库在备份前后各数一次，两次一样的表才比，备份期间有人写的表单独列出），记下每一步耗时；
//   4. 删掉临时容器和备份文件（只删本脚本建的）。
//
//   PG_CONTAINER=homerev-postgres-1 BASE_URL=http://127.0.0.1:18420 node infra/scripts/s6-rehearsal/07-backup-restore.cjs

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawnSync, execFileSync } = require('node:child_process');
const { REPO, step, note, tableCounts, done } = require('./lib.cjs');

const SOURCE = process.env.PG_CONTAINER;
const IMAGE = 'postgis/postgis:16-3.5';
const STAMP = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 19).replace(/[-:T]/g, '');
const RESTORE = `s6-restore-${STAMP}`;
const DUMP = path.join(REPO, '.runtime', 's6-rehearsal', `s6-backup-${STAMP}.dump`);
const RESTORE_DB = 'restore_check';
let containerStarted = false;

const timed = (fn) => { const t = Date.now(); const value = fn(); return [value, (Date.now() - t) / 1000]; };
const docker = (args, options = {}) => spawnSync('docker', args, { encoding: 'utf8', maxBuffer: 256 * 1024 * 1024, ...options });

function restoredCounts() {
  const out = docker(['exec', '-i', RESTORE, 'psql', '-v', 'ON_ERROR_STOP=1', '-At', '-U', 'postgres', '-d', RESTORE_DB], { input: `
    SELECT string_agg(t || '=' || n, ' ' ORDER BY t) FROM (
      SELECT c.relname AS t, (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I', c.relname), false, true, '')))[1]::text::bigint AS n
        FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace
       WHERE s.nspname = 'public' AND c.relkind = 'r' AND c.relname NOT IN ('spatial_ref_sys', 'flyway_schema_history')) x;` });
  if (out.status !== 0) throw new Error(`读恢复库失败：${out.stderr}`);
  return Object.fromEntries(out.stdout.trim().split(' ').filter(Boolean).map((kv) => { const [t, n] = kv.split('='); return [t, Number(n)]; }));
}

async function main() {
  // 1. 备份
  const before = tableCounts();
  const fd = fs.openSync(DUMP, 'w', 0o600);
  const [dumped, dumpSeconds] = timed(() => spawnSync('docker', ['exec', SOURCE, 'sh', '-c', 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -Z 6'], { stdio: ['ignore', fd, 'pipe'] }));
  fs.closeSync(fd);
  const after = tableCounts();
  const size = fs.statSync(DUMP).size;
  step('pg_dump 自定义格式备份写到宿主机', { req: 'docker exec <开发库> pg_dump -Fc -Z 6 > 文件', expect: '退出码 0，文件非空' }, dumped.status === 0 && size > 0,
    { exit: dumped.status, bytes: size, seconds: dumpSeconds, stderr: String(dumped.stderr || '').slice(0, 300) });
  const listed = spawnSync('docker', ['exec', '-i', SOURCE, 'pg_restore', '-l'], { input: fs.readFileSync(DUMP), maxBuffer: 64 * 1024 * 1024 });
  const sha = crypto.createHash('sha256').update(fs.readFileSync(DUMP)).digest('hex');
  step('备份能读（pg_restore -l 列目录），记 sha256', { req: 'pg_restore -l < 文件', expect: '退出码 0' }, listed.status === 0,
    { exit: listed.status, entries: String(listed.stdout).split('\n').filter((l) => l && !l.startsWith(';')).length, sha256: sha });

  // 2. 临时容器
  const password = crypto.randomBytes(18).toString('base64url');
  const [run, runSeconds] = timed(() => docker(['run', '-d', '--name', RESTORE, '-e', `POSTGRES_PASSWORD=${password}`, IMAGE]));
  containerStarted = run.status === 0;
  step(`另起临时 PostGIS 容器 ${RESTORE}（不映射端口）`, { req: `docker run -d --name ${RESTORE} ${IMAGE}`, expect: '启动' }, containerStarted, { exit: run.status, seconds: runSeconds, stderr: run.stderr.slice(0, 300) });
  const readyStart = Date.now();
  let ready = false;
  // 镜像第一次启动要 initdb、装 PostGIS 再重启一次：等到能连上并且初始化日志说完成。
  for (let i = 0; i < 120 && !ready; i += 1) {
    const logs = docker(['logs', RESTORE]);
    const okLog = /PostgreSQL init process complete; ready for start up/.test(`${logs.stdout}${logs.stderr}`);
    const probe = docker(['exec', RESTORE, 'psql', '-U', 'postgres', '-d', 'postgres', '-Atc', 'SELECT 1']);
    ready = okLog && probe.status === 0 && probe.stdout.trim() === '1';
    if (!ready) await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  const readySeconds = (Date.now() - readyStart) / 1000;
  step('临时库可以连接', { req: 'docker exec … psql SELECT 1', expect: '1' }, ready, { seconds: readySeconds });

  // 3. 恢复
  const created = docker(['exec', RESTORE, 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres', '-d', 'postgres', '-c', `CREATE DATABASE ${RESTORE_DB}`]);
  const [restored, restoreSeconds] = timed(() => spawnSync('docker', ['exec', '-i', RESTORE, 'pg_restore', '-U', 'postgres', '-d', RESTORE_DB, '--no-owner', '--exit-on-error'],
    { input: fs.readFileSync(DUMP), encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 }));
  step('pg_restore --no-owner --exit-on-error 恢复到新库', { req: `CREATE DATABASE ${RESTORE_DB}; pg_restore … < 文件`, expect: '退出码 0' },
    created.status === 0 && restored.status === 0, { exit: restored.status, seconds: restoreSeconds, stderr: String(restored.stderr || '').slice(0, 600) });

  // 4. 核对行数
  const copy = restoredCounts();
  const changedDuringDump = Object.keys(before).filter((t) => before[t] !== after[t]);
  const compared = Object.keys(before).filter((t) => !changedDuringDump.includes(t));
  const mismatched = compared.filter((t) => copy[t] !== before[t]);
  const missing = Object.keys(before).filter((t) => !(t in copy));
  const total = (counts, tables) => tables.reduce((sum, t) => sum + (counts[t] || 0), 0);
  step('恢复库和开发库逐表行数一致', { req: `比较 ${compared.length} 张表（备份期间有变化的另列）`, expect: '没有不一致、没有缺表' },
    restored.status === 0 && mismatched.length === 0 && missing.length === 0,
    { tables: Object.keys(before).length, compared: compared.length, rows: total(before, compared), restoredRows: total(copy, compared),
      mismatched: mismatched.map((t) => `${t}:${before[t]}→${copy[t]}`), missing, changedDuringDump });
  const keyTables = ['identity_user', 'identity_organization', 'mall_product', 'mall_offer', 'activity', 'activity_session', 'venue', 'venue_session',
    'inspection_service', 'pro_service', 'trade_merchant_account', 'trade_reconciliation', 'notification_message', 'platform_city'];
  note(`主要表：${keyTables.map((t) => `${t} ${before[t]}/${copy[t]}`).join('，')}`);
  // 每个模块一张迁移记录表（flyway_history_<模块>），行数已经在上面逐表比过；这里再看一眼表数。
  const flyway = docker(['exec', RESTORE, 'psql', '-At', '-U', 'postgres', '-d', RESTORE_DB, '-c', "SELECT count(*) || ' 张迁移记录表' FROM pg_tables WHERE schemaname = 'public' AND tablename LIKE 'flyway_history_%'"]);
  const flywaySource = Object.keys(before).filter((t) => t.startsWith('flyway_history_'));
  const postgis = docker(['exec', RESTORE, 'psql', '-At', '-U', 'postgres', '-d', RESTORE_DB, '-c', 'SELECT postgis_full_version() IS NOT NULL']);
  step('恢复库带迁移记录和 PostGIS 扩展（后端连上来不会重跑迁移）', { req: 'flyway_schema_history、postgis_full_version()', expect: '有记录，PostGIS 可用' },
    flyway.status === 0 && flyway.stdout.trim() === `${flywaySource.length} 张迁移记录表` && flywaySource.length > 0 && postgis.stdout.trim() === 't',
    { flyway: flyway.stdout.trim(), sourceTables: flywaySource.length, migrationRows: flywaySource.reduce((n, t) => n + before[t], 0), postgis: postgis.stdout.trim() });
  note(`耗时：备份 ${dumpSeconds}s（${(size / 1024 / 1024).toFixed(1)} MB），起临时库 ${(runSeconds + readySeconds).toFixed(1)}s，恢复 ${restoreSeconds}s`);
}

function cleanup() {
  if (containerStarted) {
    const removed = docker(['rm', '-f', '-v', RESTORE]);
    step(`删除临时容器 ${RESTORE}（连同它的匿名数据卷）`, { req: `docker rm -f -v ${RESTORE}`, expect: '已删除' }, removed.status === 0 && docker(['inspect', RESTORE]).status !== 0, removed.status);
  }
  if (fs.existsSync(DUMP)) fs.unlinkSync(DUMP);
  step('删除备份文件', { req: `rm ${path.relative(REPO, DUMP)}`, expect: '不存在' }, !fs.existsSync(DUMP), path.relative(REPO, DUMP));
  void execFileSync;
}

main().then(() => done(cleanup)(), (error) => done(cleanup)(error));
