# 巡检项目对话 2、3 并行开发隔离设计

## 1. 目标与当前基线

目标是在不干扰对话 1“局域网独立 Android 验收包”的前提下，让对话 2、3
并行产出可独立测试、可独立提交的成果，最后由单一整合者接入
`codex/inspection-service-flow`。

当前基线：

- 对话 1 正在 `.worktrees/inspection-service-flow` 修改局域网访问、Docker
  绑定、Android 构建和真机验收相关文件。
- 对话 2 已完成“手机目录数据不完整”的只读诊断，尚未实施修复。
- 对话 3 已完成 11 张页面与巡检业务扩展计划，尚未实施。
- 三个对话不得共同编辑同一工作树、共同重启同一 Docker 项目或共同操作同一
  手机验收包。

## 2. 分轨设计

### 对话 2：后端、数据库与契约轨

独立分支与工作树：

```text
branch: codex/inspection-backend-data
worktree: .worktrees/inspection-backend-data
```

职责：

- 补齐东营巡检目录开发数据，并验证城市、分类和可见性规则。
- 实现 11 张页面所需的报价、需求确认、订单聚合和评价后端能力。
- 负责数据库迁移、服务端权限、幂等、MinIO 文件模型及后端测试。
- 更新 `contracts/` 中的 OpenAPI，提供稳定请求/响应示例。

文件所有权：

```text
允许：
backend/**
contracts/**
后端和数据库专项测试

禁止：
apps/mobile/**
主工作树及对话 1 工作树
```

独立运行环境：

```text
Compose project: inspection-backend-data
backend: 18080
PostgreSQL: 25432，仅回环
Redis: 26379，仅回环
MinIO: 29000/29001
```

对话 2 不得修改现有验收环境的 `.env`，不得重启
`inspection-local-*` 容器，不得向当前手机安装应用。

### 对话 3：移动端页面与视觉轨

独立分支与工作树：

```text
branch: codex/inspection-ui-pages
worktree: .worktrees/inspection-ui-pages
```

职责：

- 实现首页、服务页、“我的”及交易流程页面的页面级组件。
- 负责视觉素材、响应式适配、加载/空/错状态、组件测试和截图对照。
- 使用冻结的 OpenAPI 示例或仅开发环境可用的页面适配器构造数据。
- 所有页面先形成可测试的 presentation 组件，不提前注册到正式路由。

文件所有权：

```text
允许：
apps/mobile/src/features/inspection/presentation/**
apps/mobile/src/static/**
页面和组件专项测试

禁止：
backend/**
contracts/**
infra/**
apps/mobile/src/pages.json
apps/mobile/src/manifest.json
apps/mobile/src/features/inspection/navigation/inspection-routes.ts
apps/mobile/src/features/inspection/data/service-flow/uni-booking-http-client.ts
登录、预约和验收环境配置
```

独立运行环境：

```text
H5: 5183
不启动独立后端时使用开发适配器
不操作物理手机，不覆盖对话 1 的 APK
```

## 3. 共享冻结区与接口约束

在对话 1 完成并建立 Git 检查点前，以下内容由对话 1 独占：

- 路由注册、`pages.json`、`manifest.json`。
- `inspection-routes.ts`、真实 HTTP 客户端及登录上下文。
- `infra/compose.yaml`、验收环境变量和局域网地址。
- 预约、履约、交付页面中对话 1 已有的未提交修改。
- HBuilderX、DCloud 云打包、无线 ADB 和物理手机。

对话 2 是 API 契约的唯一写入者。对话 3 只能消费已提交的 OpenAPI 示例，不得
自行修改字段名、状态枚举或错误码。发现契约缺口时只提交问题清单，由对话 2
修订并发布新的契约提交。

## 4. 提交与整合流程

1. 对话 1继续完成验收包；对话 2、3只在各自工作树工作。
2. 对话 2先提交 OpenAPI/数据库/后端，每个可验收阶段一个提交。
3. 对话 3提交纯移动端 presentation 成果，不包含公共路由和真实 API 接线。
4. 对话 1 完成后，在 `codex/inspection-service-flow` 建立明确检查点。
5. 指定一个整合者，按“后端契约提交 → UI提交”的顺序合并。
6. 整合者独占修改冻结文件，完成真实 API 接线、路由注册和状态恢复。
7. 合并后统一运行全量前后端测试、数据库迁移、E2E、视觉回归和 Android 验收。

任一并行分支不得直接合并到 `main`，不得自动推送，未经批准不得删除或覆盖
其他工作树的未提交内容。

## 5. 验收与失败处理

对话 2 交付条件：

- OpenAPI、迁移、空库执行和后端测试通过。
- 独立数据库能返回完整东营目录数据。
- 不存在 `apps/mobile/**` 变更。

对话 3 交付条件：

- 页面组件、专项测试和 H5 同视口截图通过。
- 开发适配器不会进入生产构建。
- 不存在后端、契约、公共路由和真实 HTTP 客户端变更。

整合阶段若出现冲突，不允许并行分支自行修改对方代码；由整合者依据最终
OpenAPI 和对话 1检查点裁决。数据库迁移失败、状态枚举不一致或物理手机包
被覆盖时立即停止整合，恢复到检查点后再逐提交定位。

## 6. 明确假设

- 对话 1在整合开始前会完成或形成可复验的 Git 检查点。
- 对话 2、3均从 `codex/inspection-service-flow` 的已提交基线创建工作树，
  不复制对话 1的脏文件。
- 对话 3暂不要求页面在物理手机形成最终闭环；真机闭环只在整合阶段执行。
- 本设计只解决并行隔离和整合秩序，不扩大已确认的产品范围。
