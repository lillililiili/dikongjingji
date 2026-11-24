# 巡检服务两模块 Codex 开发包 V1.0

> **第一次看这个项目？先读 [交接文档.md](交接文档.md)**：一张图看懂系统、代码在哪、怎么跑、现在到哪一步。
> 本文件下面是最早的开发包说明，范围已过时，仅作历史记录。

> 当前批准范围已扩展为 M03 至 INS-M-008 的用户侧交易闭环；范围、运行和验收事实源见
> [`docs/12-inspection-service-flow-baseline.md`](docs/12-inspection-service-flow-baseline.md)、
> [`docs/13-inspection-service-flow-task-plan.md`](docs/13-inspection-service-flow-task-plan.md) 和
> [`docs/15-inspection-service-flow-runbook.md`](docs/15-inspection-service-flow-runbook.md)。
> 下文保留为最初 M01/M02 开发包说明，不代表当前已批准交付范围。

本开发包用于指导 Codex 以模块化方式开发：

```text
服务
└── 我要巡检（M01，INS-M-002）
    └── 巡检服务（M02，INS-M-003，重点）
```

当前只实现两个移动端页面及其真实目录读取能力，不扩展巡检详情、需求发布、报价、订单或后台页面。

## 使用方式

1. 将本开发包内容放到目标项目根目录。
2. 保留 `references/` 中两张 UI 参考图。
3. 如果项目根目录已有 `AGENTS.md`，先人工比较，不要直接覆盖；应把本文件中的“本期两模块范围”合并为更具体约束。
4. 在 Codex 中发送 `START-CODEX.md` 里的启动指令。
5. Codex 第一次只执行 T01 项目盘点，不允许直接写大量代码。
6. 每到阶段验收点，由用户明确回复“验收通过”后再继续。

## 文档阅读顺序

| 顺序 | 文件 | 用途 |
| --- | --- | --- |
| 1 | `AGENTS.md` | Codex 最高优先级约束 |
| 2 | `START-CODEX.md` | 第一次启动指令 |
| 3 | `docs/00-scope-baseline.md` | 范围和完成口径 |
| 4 | `docs/01-module-architecture.md` | 模块边界和目录 |
| 5 | `docs/02-screen-spec.md` | 两页 UI 规格 |
| 6 | `docs/03-routing-interaction.md` | 路由和点击逻辑 |
| 7 | `docs/04-api-contract.md` | API 说明 |
| 8 | `docs/05-data-model.md` | PostgreSQL/PostGIS 模型 |
| 9 | `docs/06-testing-acceptance.md` | 测试和验收 |
| 10 | `docs/07-codex-task-plan.md` | 逐任务执行计划 |
| 11 | `docs/08-decisions-and-open-items.md` | 已确定事项与待确认项 |
| 12 | `docs/09-design-tokens.md` | 视觉令牌与适配规则 |
| 13 | `docs/10-asset-manifest.md` | 图片和资产规则 |
| 14 | `contracts/` | OpenAPI 与示例数据 |

## 本期交付边界

本期开发完成后应得到：

- 可真实点击进入的“我要巡检”入口页。
- 可真实查询的“巡检服务”列表页。
- 搜索、分类、排序、城市、筛选、刷新和分页。
- 真实后端读取 API。
- PostgreSQL/PostGIS 目录数据模型。
- Android、iOS 真机截图和测试结果。

本期不会得到：

- 巡检详情和发布需求。
- 咨询、预约、报价、订单和支付。
- 服务商/飞手工作台。
- 后台管理页面。
- 微信小程序。

因此本期是正式产品的一个受控增量，不是完整产品上线包。

## 重要提醒

- 两张参考图是视觉依据，不是可直接当作页面背景的图片。
- M01 示例城市为东营，M02 示例为济南；真实实现必须保持同一当前城市。
- 参考图中的天气、角标、评分和服务次数不得作为生产假数据。
- 本期未实现的入口必须由功能开关隐藏或阻止公开发布。
