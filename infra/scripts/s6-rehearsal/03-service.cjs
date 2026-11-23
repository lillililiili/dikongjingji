#!/usr/bin/env node
'use strict';

// S6 场景 3 服务询价：需求发布 → 服务商报价 → 客户选定 → 通用服务订单 → 方案确认 → 付款 → 交付 → 退回整改 → 再交付 → 验收；
// 巡检：确认方案 → 首付 30% → 履约 → 验收 → 按次付款 → 改期、取消与退款。
// 这两条链路已经有逐步核对的冒烟脚本（trade-smoke/service-order.cjs、trade-smoke/inspection.cjs，各自清理自己的数据），
// 演练按顺序跑这两个脚本，把结果和它们前后各表行数的变化记下来。后端要开 INSPECTION_PAYMENT_NODES_ENABLED=true。
//
//   set -a; . .planning/stack/dev-operator.env; set +a
//   PG_CONTAINER=homerev-postgres-1 BASE_URL=http://127.0.0.1:18420 FAKE_WECHAT_URL=http://127.0.0.1:17460 \
//     node infra/scripts/s6-rehearsal/03-service.cjs

// inspection.cjs 按它的说明会留下真实的预约、方案和服务单（巡检没有删除接口）；演练结束时从“本次演练开始后 dev-customer 建的预约”
// 出发做闭包清理（方案、服务单、履约、交付、验收、付款节点、站内信、发件箱……），再查残留。交付文件的对象存储内容（MinIO）不在
// 数据库里，记下对象数，见测试报告。

const { BUYER, SINCE, psql, sqlText, runSmoke, track, note, purgeAndReport, done } = require('./lib.cjs');

async function main() {
  runSmoke('service-order', { label: '需求 → 报价 → 选定 → 通用服务订单 → 方案 → 付款 → 交付 → 整改 → 验收（service-order.cjs）' });
  runSmoke('inspection', { label: '巡检：确认方案 → 首付 30% → 履约 → 验收 → 按次付款 → 改期 / 取消退款（inspection.cjs）' });
}

function cleanup() {
  const reservations = psql(`SELECT id FROM inspection_reservation WHERE customer_id='${BUYER}' AND created_at >= ${sqlText(SINCE)}::timestamptz`).split('\n').filter(Boolean);
  reservations.forEach((id) => track(id));
  const objects = psql(`SELECT count(*) FROM inspection_file_object f WHERE f.created_at >= ${sqlText(SINCE)}::timestamptz`);
  note(`inspection.cjs 留下的预约 ${reservations.length} 张，从这里清理；本次新增的对象存储文件记录 ${objects} 条（MinIO 里的对象不删）`);
  purgeAndReport();
}

main().then(() => done(cleanup)(), (error) => done(cleanup)(error));
