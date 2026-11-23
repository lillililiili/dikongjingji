'use strict';

// S6 验收演练（docs/redesign/08-后续开发计划.md S6；建设方案 §11.1 七个场景）共用的工具。
// 在 ../trade-smoke/lib.cjs 上加三样东西：
//   1. step()：每一步记“请求、预期、实际、通过与否”，打印一行，同时写进 .runtime/s6-rehearsal/<脚本>-<时间>.jsonl；
//   2. 运营端登录（dev-operator 的口令只从环境变量 DEV_OPERATOR_PASSWORD 读，不写进任何文件）；
//   3. purge()/residue()：按“本次演练建的 ID”做闭包清理和残留检查（见下）。
//
// 只对本机后端和本机模拟微信运行（trade-smoke/lib.cjs 里检查）。环境变量：
//   BASE_URL、FAKE_WECHAT_URL、PG_CONTAINER（必填）、DEV_OPERATOR_PASSWORD（运营端几步要用）。
//
// 清理的办法：脚本把自己建的对象 ID（机构、账号、商品、订单、报名……）和自己登录的会话 ID 记进 created；结束时在一个事务里
// 从这些 ID 出发，先只读地收齐闭包——所有表里“某个 uuid / 文本列等于集合里的值、并且 created_at 不早于本次演练开始时间”的行，
// 它们的 id（和交易的商户订单号、退款单号、订单号）再加进集合，直到不再变大；再把这些行反复删到删不动（外键挡住的下一轮再删）。
// created_at 的条件保证不会碰到演练开始前就有的数据（开发种子、别人的数据）。清完按同一个集合把所有表查一遍（jsonb 列按文本
// 包含查），残留必须是 0；再和脚本开始时比一次各表行数，不能多出行（兜住“注册时顺带建的上级行”这类闭包顺不到的）。
// 中断过的一次可以用 S6_SINCE=<那次开始时的数据库时间> 补清；S6_DRY_RUN=1 只看会删什么、不提交。

const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const base = require('../trade-smoke/lib.cjs');

const { REPO, psql, call, login } = base;
const SCRIPT = path.basename(process.argv[1] || 'script', '.cjs');
const LOG_DIR = path.join(REPO, '.runtime', 's6-rehearsal');
fs.mkdirSync(LOG_DIR, { recursive: true });
const STAMP = new Date(Date.now() + 8 * 3600_000).toISOString().slice(0, 19).replace(/[-:T]/g, '');
const LOG_FILE = path.join(LOG_DIR, `${SCRIPT}-${STAMP}.jsonl`);
/** 演练开始时数据库的时间：清理只删这之后建的行。S6_SINCE 只用于补清一次中断的演练（填那次开始的时间）。 */
const SINCE = process.env.S6_SINCE || psql('SELECT now()');
/** 脚本开始时各表的行数，清理后再数一次对比。 */
const START_COUNTS = tableCounts();

let steps = 0;
let failed = 0;
const short = (value) => {
  if (value === undefined) return undefined;
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  return text && text.length > 600 ? `${text.slice(0, 600)}…` : text;
};

/**
 * 记一步。req：做了什么（方法和路径，或一句话）；expect：预期；ok：是否符合；actual：实际（状态码、关键字段）。
 * 打印“ok / FAIL  标签”，写一行 JSONL。返回 ok，方便脚本按结果决定后面的步骤。
 */
function step(label, { req, expect }, ok, actual) {
  steps += 1;
  const pass = Boolean(ok);
  if (!pass) failed += 1;
  base.check(label, pass, actual);
  fs.appendFileSync(LOG_FILE, `${JSON.stringify({ n: steps, label, req, expect, pass, actual: short(actual) })}\n`);
  return pass;
}

/**
 * 已知缺陷：按产品预期写 expect，实际不符合时打印 KNOWN（问题编号），不算这一轮的失败，但单独计数并写进记录；
 * 哪天实际符合了打印 FIXED，提醒去关问题。只用于已经写进 docs/delivery/问题关闭记录.md 的问题。
 */
let knownCount = 0;
function known(issue, label, { req, expect }, ok, actual) {
  steps += 1;
  const state = ok ? 'FIXED' : 'KNOWN';
  if (!ok) knownCount += 1;
  console.log(`${state} ${label}（${issue}）${ok ? '' : ` — ${short(actual) ?? ''}`}`);
  fs.appendFileSync(LOG_FILE, `${JSON.stringify({ n: steps, label, issue, req, expect, pass: Boolean(ok), known: !ok, actual: short(actual) })}\n`);
  return ok;
}

function note(text) {
  console.log(`--  ${text}`);
  fs.appendFileSync(LOG_FILE, `${JSON.stringify({ note: text })}\n`);
}

const created = new Set();
/** 记下本次演练建的对象 ID（uuid 或交易单号），清理从这里出发。返回原值。 */
function track(...ids) {
  for (const id of ids.flat()) if (id) created.add(String(id));
  return ids[0];
}

const sqlText = (value) => `'${String(value).replace(/'/g, "''")}'`;
const idArray = (ids) => `ARRAY[${[...ids].map(sqlText).join(',') || "''"}]::text[]`;

// 删除时顺带记下的“自然键”：交易的回调、对账差异等按这些文本关联，不是按 uuid。
const NATURAL_KEYS = {
  trade_payment: ['out_trade_no'],
  trade_refund: ['out_refund_no'],
  trade_order: ['order_no'],
};

function purgeSql(ids) {
  const naturals = Object.entries(NATURAL_KEYS)
    .map(([table, cols]) => `WHEN ${sqlText(table)} THEN ${sqlText(cols.map((c) => `${c}::text`).join(','))}`).join(' ');
  // 两步：先只读地把闭包收齐（被外键挡住删不掉的父行，它的 id 也要先进集合，子行才找得到），再反复删到删不动为止。
  return `
    CREATE TEMP TABLE s6_ids (v text PRIMARY KEY) ON COMMIT DROP;
    INSERT INTO s6_ids SELECT DISTINCT unnest(${idArray(ids)}) ON CONFLICT DO NOTHING;
    CREATE TEMP TABLE s6_cols ON COMMIT DROP AS
      SELECT c.table_name::text AS table_name, c.column_name::text AS column_name,
             concat_ws(',', CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns x WHERE x.table_schema = c.table_schema AND x.table_name = c.table_name AND x.column_name = 'id') THEN 'id::text' END,
                       CASE c.table_name ${naturals} END) AS ret,
             CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns x WHERE x.table_schema = c.table_schema AND x.table_name = c.table_name AND x.column_name = 'created_at')
                  THEN ' AND created_at >= $1' ELSE '' END AS guard
        FROM information_schema.columns c
        JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
       WHERE c.table_schema = 'public' AND c.data_type IN ('uuid', 'character varying', 'text', 'character')
         AND c.table_name NOT IN ('spatial_ref_sys', 'flyway_schema_history');
    DO $purge$
    DECLARE
      since timestamptz := ${sqlText(SINCE)}::timestamptz;
      r record; got text[]; progress boolean; rounds int := 0; total int := 0; n int; per jsonb := '{}'::jsonb;
    BEGIN
      -- 1. 收闭包（只读）
      LOOP
        progress := false; rounds := rounds + 1;
        FOR r IN SELECT * FROM s6_cols WHERE ret <> '' ORDER BY table_name, column_name LOOP
          EXECUTE format('SELECT coalesce(array_agg(k), ARRAY[]::text[]) FROM (SELECT %s FROM %I WHERE %I::text IN (SELECT v FROM s6_ids)%s) d, '
                         'LATERAL unnest(ARRAY[%s]) k WHERE k IS NOT NULL AND k NOT IN (SELECT v FROM s6_ids)',
                         r.ret, r.table_name, r.column_name, r.guard, r.ret)
            INTO got USING since;
          IF coalesce(array_length(got, 1), 0) > 0 THEN
            progress := true;
            INSERT INTO s6_ids SELECT unnest(got) ON CONFLICT DO NOTHING;
          END IF;
        END LOOP;
        EXIT WHEN NOT progress OR rounds > 40;
      END LOOP;
      -- 2. 删（外键挡住的下一轮再删）
      rounds := 0;
      LOOP
        progress := false; rounds := rounds + 1;
        FOR r IN SELECT * FROM s6_cols ORDER BY table_name, column_name LOOP
          BEGIN
            EXECUTE format('DELETE FROM %I WHERE %I::text IN (SELECT v FROM s6_ids)%s', r.table_name, r.column_name, r.guard) USING since;
            GET DIAGNOSTICS n = ROW_COUNT;
            IF n > 0 THEN
              progress := true; total := total + n;
              per := jsonb_set(per, ARRAY[r.table_name], to_jsonb(coalesce((per ->> r.table_name)::int, 0) + n));
            END IF;
          EXCEPTION WHEN foreign_key_violation THEN NULL;
          END;
        END LOOP;
        EXIT WHEN NOT progress OR rounds > 40;
      END LOOP;
      RAISE NOTICE 's6 purge: % rows in % rounds', total, rounds;
      RAISE NOTICE 's6 tables: %', per::text;
    END
    $purge$;`;
}

function residueSql() {
  return `
    DO $res$
    DECLARE r record; n bigint; found text := '';
    BEGIN
      FOR r IN
        SELECT c.table_name, c.column_name, c.data_type
          FROM information_schema.columns c
          JOIN information_schema.tables t ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE'
         WHERE c.table_schema = 'public' AND c.data_type IN ('uuid', 'character varying', 'text', 'character', 'jsonb')
           AND c.table_name NOT IN ('spatial_ref_sys', 'flyway_schema_history')
      LOOP
        IF r.data_type = 'jsonb' THEN
          EXECUTE format('SELECT count(*) FROM %I x WHERE EXISTS (SELECT 1 FROM s6_ids i WHERE length(i.v) >= 12 AND x.%I::text LIKE ''%%'' || i.v || ''%%'')', r.table_name, r.column_name) INTO n;
        ELSE
          EXECUTE format('SELECT count(*) FROM %I WHERE %I::text IN (SELECT v FROM s6_ids)', r.table_name, r.column_name) INTO n;
        END IF;
        IF n > 0 THEN found := found || r.table_name || '.' || r.column_name || '=' || n || ' '; END IF;
      END LOOP;
      RAISE NOTICE 's6 residue: %', coalesce(nullif(found, ''), 'none');
    END
    $res$;`;
}

/** 闭包清理 + 残留检查，在一个事务里。返回 { deleted, ids, residue }，residue 为 'none' 表示干净。 */
function purge(extraIds = []) {
  const ids = new Set([...created, ...extraIds]);
  if (ids.size === 0) return { deleted: 0, ids: 0, residue: 'none' };
  const out = require('node:child_process').execFileSync('docker', [
    'exec', '-i', process.env.PG_CONTAINER, 'sh', '-c',
    'psql -v ON_ERROR_STOP=1 -At -U "$POSTGRES_USER" -d "$POSTGRES_DB" 2>&1',
  ], { input: `BEGIN;\n${purgeSql(ids)}\nSELECT 's6 ids: ' || count(*) FROM s6_ids;\n${residueSql()}\n${process.env.S6_DRY_RUN ? 'ROLLBACK' : 'COMMIT'};\n`, encoding: 'utf8' });
  const deleted = Number((/s6 purge: (\d+) rows/.exec(out) || [])[1] || 0);
  const idCount = Number((/s6 ids: (\d+)/.exec(out) || [])[1] || 0);
  const residue = ((/s6 residue: (.*)/.exec(out) || [])[1] || 'unknown').trim();
  let tables = {};
  try { tables = JSON.parse((/s6 tables: (.*)/.exec(out) || [])[1] || '{}'); } catch { tables = {}; }
  return { deleted, ids: idCount, residue, tables, dryRun: Boolean(process.env.S6_DRY_RUN) };
}

// 开发环境本机存储（application-dev.yml：资质附件、运营素材、商品图片、售后凭证、服务订单交付文件）放在后端的
// java.io.tmpdir 下；和这个脚本同一个用户跑时就是 os.tmpdir()，不同时用 S6_JAVA_TMPDIR 指定。
const STORE_DIRS = ['qualification-files', 'ops-media', 'mall-media', 'aftersale-images', 'service-order-files', 'data-exports']
  .map((name) => path.join(process.env.S6_JAVA_TMPDIR || require('node:os').tmpdir(), `lowaltitude-dev-${name}`));

/** 删掉本机存储里路径含本次演练 ID 的文件（机构目录、素材 ID 命名的文件），返回删了几个、还剩几个。 */
function removeLocalFiles(ids) {
  const wanted = [...ids].filter((id) => id.length >= 32);
  let removed = 0;
  let remaining = 0;
  const walk = (dir) => {
    if (!fs.existsSync(dir)) return;
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        walk(full);
        if (wanted.some((id) => full.includes(id)) && fs.readdirSync(full).length === 0) fs.rmdirSync(full);
      } else if (wanted.some((id) => full.includes(id))) {
        fs.unlinkSync(full);
        removed += 1;
        if (fs.existsSync(full)) remaining += 1;
      }
    }
  };
  STORE_DIRS.forEach(walk);
  return { removed, remaining };
}

/** 清理并把结果记成最后一步（残留必须为 0）。脚本在 finally 里调用。 */
function purgeAndReport(extraIds = []) {
  const files = removeLocalFiles(new Set([...created, ...extraIds]));
  step('清理：本机存储里本次演练上传的文件删除', { req: '按 ID 删本机存储文件', expect: 'remaining 0' }, files.remaining === 0, files);
  const result = purge(extraIds);
  step('清理：本次演练写入的数据全部删除，残留为 0', { req: `闭包清理 ${created.size} 个起点 ID`, expect: 'residue none' },
    result.residue === 'none', result);
  // 再按表行数和脚本开始时比一次：闭包只顺着“引用了演练对象”的行往下找，这一步兜住漏掉的（例如注册时顺带建的上级行）。
  // 冒烟脚本（runSmoke）自己登录留下的登录会话不是本脚本能删的（不知道它们的 sid），单独列出。
  const diff = countDiff(START_COUNTS, tableCounts());
  const smokeSessions = ['identity_login_session', 'identity_refresh_token'];
  const unexpected = Object.fromEntries(Object.entries(diff).filter(([t, n]) => n > 0 && !(smokesRan && smokeSessions.includes(t))));
  step('清理：各表行数回到脚本开始时（不多出行）', { req: '开始和结束各数一次每张表的行数', expect: '没有多出来的行' + (smokesRan ? '（冒烟脚本的登录会话除外）' : '') },
    Object.keys(unexpected).length === 0, { diff });
  console.log(`--  清理：删除 ${result.deleted} 行，闭包 ID ${result.ids} 个，残留 ${result.residue}`);
  return result;
}

/** 每张表的行数（只读）。冒烟脚本前后各取一次，差出来的就是它留下的数据。 */
function tableCounts() {
  const out = psql(`
    SELECT string_agg(t || '=' || n, ' ' ORDER BY t) FROM (
      SELECT c.relname AS t, (xpath('/row/n/text()', query_to_xml(format('SELECT count(*) AS n FROM %I', c.relname), false, true, '')))[1]::text::bigint AS n
        FROM pg_class c JOIN pg_namespace s ON s.oid = c.relnamespace
       WHERE s.nspname = 'public' AND c.relkind = 'r' AND c.relname NOT IN ('spatial_ref_sys', 'flyway_schema_history')) x`);
  return Object.fromEntries(out.split(' ').filter(Boolean).map((kv) => { const [t, n] = kv.split('='); return [t, Number(n)]; }));
}

function countDiff(before, after) {
  const diff = {};
  for (const t of new Set([...Object.keys(before), ...Object.keys(after)])) {
    const d = (after[t] || 0) - (before[t] || 0);
    if (d !== 0) diff[t] = d;
  }
  return diff;
}

/**
 * 跑一个 ../trade-smoke/<name>.cjs（或 ../<name>.cjs），把它的 ok / FAIL 行数和最后结果记成一步，并记下它前后各表行数的差
 * （冒烟脚本自己的清理留下了什么）。返回 { ok, passed, failedLines, diff, seconds }。
 */
let smokesRan = false;
function runSmoke(name, { env = {}, args = [], label } = {}) {
  smokesRan = true;
  const { spawnSync } = require('node:child_process');
  const file = fs.existsSync(path.join(__dirname, '../trade-smoke', `${name}.cjs`))
    ? path.join(__dirname, '../trade-smoke', `${name}.cjs`) : path.join(__dirname, '..', `${name}.cjs`);
  const before = tableCounts();
  const started = Date.now();
  const result = spawnSync(process.execPath, [file, ...args], {
    cwd: REPO, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024,
    env: { ...process.env, OPERATOR_PASSWORD: OPERATOR_PASSWORD, ...env },
  });
  const seconds = Math.round((Date.now() - started) / 1000);
  const output = `${result.stdout || ''}${result.stderr || ''}`;
  fs.writeFileSync(path.join(LOG_DIR, `${SCRIPT}-${name}-${STAMP}.log`), output);
  const lines = output.split('\n');
  const passed = lines.filter((l) => l.startsWith('ok ')).length;
  const failedLines = lines.filter((l) => l.startsWith('FAIL'));
  const allOk = result.status === 0 && /\nALL OK\s*$/.test(output);
  const diff = countDiff(before, tableCounts());
  step(label || `冒烟 ${name}`, { req: `node ${path.relative(REPO, file)} ${args.join(' ')}`.trim(), expect: 'ALL OK' }, allOk,
    { passed, failed: failedLines.length, seconds, exit: result.status, failedLines: failedLines.slice(0, 5) });
  note(`${name}：${passed} 条 ok，${failedLines.length} 条 FAIL，${seconds} 秒；前后行数变化 ${JSON.stringify(diff)}`);
  return { ok: allOk, passed, failedLines, diff, seconds };
}

/** 登录，并把这次登录的会话 ID（令牌里的 sid）记进清理集合：演练结束时连登录会话、刷新令牌一起删掉。 */
async function trackedLogin(name, pw) {
  const token = await base.login(name, pw);
  try {
    const claims = JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString('utf8'));
    if (claims.sid) track(claims.sid);
  } catch { /* 令牌里没有 sid 时不记 */ }
  return token;
}

const OPERATOR_PASSWORD = process.env.DEV_OPERATOR_PASSWORD || '';
async function loginOperator() {
  if (!OPERATOR_PASSWORD) throw new Error('要设置 DEV_OPERATOR_PASSWORD（和后端启动时一样，source .planning/stack/dev-operator.env）');
  return trackedLogin('dev-operator', OPERATOR_PASSWORD);
}

// 最小的 1×1 PNG 和一份一页的 PDF（资质附件）。
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64');
const PDF = Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj 3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 200]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF\n');

async function upload(route, token, bytes, type, name, fields = {}) {
  const form = new FormData();
  for (const [k, v] of Object.entries(fields)) form.append(k, v);
  form.append('file', new Blob([bytes], { type }), name);
  const response = await fetch(base.BASE + route, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, body: form });
  const text = await response.text();
  let json = null;
  try { json = JSON.parse(text); } catch { json = text; }
  return { status: response.status, json, type: response.headers.get('content-type') };
}

const idem = () => ({ 'Idempotency-Key': crypto.randomUUID() });
/** 北京时间的日期 yyyy-mm-dd，offsetDays 天后。 */
const beijingDate = (offsetDays = 0) => new Date(Date.now() + 8 * 3600_000 + offsetDays * 86_400_000).toISOString().slice(0, 10);
const randomDigits = (n) => String(crypto.randomInt(0, 10 ** n)).padStart(n, '0');

/** 一个合格的统一社会信用代码格式（18 位，不含 I O Z S V）；只校验格式，不是真实代码。 */
const fakeCreditCode = () => `91${randomDigits(6)}MA${randomDigits(6)}X${'ABCDEFGHJKLMNPQRTUWXY'[crypto.randomInt(0, 21)]}`;

/** 结束：清理在 finally 里做，然后打印汇总并退出。 */
function done(cleanup) {
  return async (error) => {
    if (error) {
      console.error(error);
      step('脚本没有异常退出', { req: SCRIPT, expect: '无异常' }, false, String(error && error.stack || error).slice(0, 600));
    }
    try {
      if (!process.argv.includes('--keep')) await cleanup();
    } catch (cleanupError) {
      console.error('清理失败', cleanupError);
      step('清理没有出错', { req: 'cleanup', expect: '无异常' }, false, String(cleanupError).slice(0, 600));
    }
    const knownText = knownCount ? `，${knownCount} 步是已知缺陷（KNOWN）` : '';
    console.log(`\n${SCRIPT}: ${steps - failed - knownCount}/${steps} 步通过${knownText}${failed === 0 ? ' — ALL OK' : ` — ${failed} FAILED`}`);
    console.log(`记录：${path.relative(REPO, LOG_FILE)}`);
    process.exit(failed === 0 ? 0 : 1);
  };
}

module.exports = {
  ...base, SINCE, LOG_FILE, step, known, note, track, created, purge, purgeAndReport, removeLocalFiles, tableCounts, countDiff, runSmoke, loginOperator, upload, idem, PNG, PDF,
  beijingDate, randomDigits, fakeCreditCode, sqlText, done, call, psql,
  login: trackedLogin,
};
