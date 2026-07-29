# 巡检对话 2、3 并行隔离执行 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不修改、不重启、不占用对话 1 验收工作树和手机环境的前提下，让现有对话 2、3 分别在独立 Git 工作树启动后端数据轨和移动端视觉轨。

**Architecture:** 两个工作树都从 `codex/inspection-service-flow` 的已提交检查点 `6b13cf0e3b003e10e3e713c25ebc4c67da029258` 创建，不复制该分支当前未提交文件。对话 2 独占后端、数据库和契约；对话 3 独占新增 presentation 组件和静态素材；共享路由、真实 HTTP 客户端、Docker 验收环境和物理手机继续由对话 1 独占。

**Tech Stack:** Git worktree、Codex 现有任务、Java 21、Spring Boot 3.5、PostgreSQL/PostGIS、Redis、MinIO、uni-app、Vue 3、TypeScript、Vitest。

## Global Constraints

- 对话 1 工作树固定为 `.worktrees/inspection-service-flow`，不得由对话 2、3 读取后写回、暂存、提交、清理或重启。
- 对话 2 分支固定为 `codex/inspection-backend-data`，工作树固定为 `.worktrees/inspection-backend-data`。
- 对话 3 分支固定为 `codex/inspection-ui-pages`，工作树固定为 `.worktrees/inspection-ui-pages`。
- 两个新分支的起点固定为已提交检查点 `6b13cf0e3b003e10e3e713c25ebc4c67da029258`，不得复制对话 1 的脏文件。
- 对话 2 只允许修改 `backend/**`、`contracts/**` 及对应后端测试。
- 对话 3 只允许修改 `apps/mobile/src/features/inspection/presentation/**`、`apps/mobile/src/static/**` 及页面、组件专项测试。
- 对话 2 在对话 1 建立新检查点前不得修改 `backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/security/SecurityConfiguration.java` 和 `backend/bootstrap/src/test/java/com/lowaltitude/inspection/bootstrap/AuthenticationSecurityIntegrationTest.java`。
- 对话 3 不得修改 `apps/mobile/src/pages.json`、`apps/mobile/src/manifest.json`、`apps/mobile/src/features/inspection/navigation/inspection-routes.ts`、`apps/mobile/src/features/inspection/data/service-flow/uni-booking-http-client.ts`、现有 booking/fulfillment/delivery 页面。
- 对话 2 独立 Compose 项目名为 `inspection-backend-data`，端口为后端 `18080`、PostgreSQL `25432`、Redis `26379`、MinIO `29000/29001`。
- 对话 3 只允许启动 H5 `5183`；不得操作 HBuilderX、DCloud 云打包、ADB 或物理手机。
- 不实施支付或退款；不创建支付记录；页面中的金额仅作为报价或应付快照。
- 两个并行分支不得直接合并到 `main`，不得自动推送，不得删除其他工作树。
- 每个任务先写失败测试，再做最小实现，再运行专项回归，最后形成独立提交。
- 对话 1 当前手机验收环境的后端 `8080`、MinIO `19000/19001` 以及 `inspection-local-*` 容器不得被新任务占用或重启。

---

## File Structure

### 协调产物

- Create: `.worktrees/inspection-backend-data/`，只承载 `codex/inspection-backend-data`。
- Create: `.worktrees/inspection-ui-pages/`，只承载 `codex/inspection-ui-pages`。
- Preserve: `.worktrees/inspection-service-flow/`，包括全部已提交和未提交状态。

### 对话 2 文件所有权

- Modify/Create: `backend/**`
- Modify/Create: `contracts/**`
- Test: `backend/**/src/test/**`
- Frozen: `apps/mobile/**`
- Frozen until 对话 1 checkpoint: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/security/SecurityConfiguration.java`
- Frozen until 对话 1 checkpoint: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/bootstrap/AuthenticationSecurityIntegrationTest.java`

### 对话 3 文件所有权

- Create/Modify: `apps/mobile/src/features/inspection/presentation/platform/**`
- Create/Modify: `apps/mobile/src/static/images/inspection/platform/**`
- Create/Modify: `apps/mobile/src/features/inspection/tests/platform-*.test.ts`
- Frozen: `apps/mobile/src/pages.json`
- Frozen: `apps/mobile/src/manifest.json`
- Frozen: `apps/mobile/src/features/inspection/navigation/inspection-routes.ts`
- Frozen: `apps/mobile/src/features/inspection/data/service-flow/uni-booking-http-client.ts`
- Frozen: `apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingPage.vue`
- Frozen: `apps/mobile/src/features/inspection/presentation/fulfillment/pages/InspectionFulfillmentPage.vue`
- Frozen: `apps/mobile/src/features/inspection/presentation/delivery/pages/InspectionDeliveryPage.vue`

### Worker Interfaces

- 对话 2 Produces: 已提交 OpenAPI 字段、状态枚举、错误码和示例，以及独立后端验证结果。
- 对话 3 Consumes: 对话 2 已提交的 OpenAPI 示例；在契约未发布前只使用页面内 development adapter，不自行发明正式 API。
- 整合者 Consumes: 对话 1 检查点、对话 2 后端提交、对话 3 presentation 提交。

---

### Task 1: 创建并核验两个隔离工作树

**Files:**

- Create: `.worktrees/inspection-backend-data/`
- Create: `.worktrees/inspection-ui-pages/`
- Preserve: `.worktrees/inspection-service-flow/`

**Interfaces:**

- Consumes: Git 检查点 `6b13cf0e3b003e10e3e713c25ebc4c67da029258`
- Produces: 两个干净、互不共享分支的工作目录

- [ ] **Step 1: 保存对话 1 状态指纹**

Run:

```powershell
git -C ".worktrees/inspection-service-flow" status --short |
  Set-Variable -Name dialogue1StatusBefore
git -C ".worktrees/inspection-service-flow" rev-parse HEAD
```

Expected:

```text
6b13cf0e3b003e10e3e713c25ebc4c67da029258
```

`dialogue1StatusBefore` 只保存在当前 PowerShell 进程中，不写文件。

- [ ] **Step 2: 验证目标分支和目录尚不存在**

Run:

```powershell
Test-Path ".worktrees/inspection-backend-data"
Test-Path ".worktrees/inspection-ui-pages"
git show-ref --verify --quiet refs/heads/codex/inspection-backend-data
$backendBranchExit = $LASTEXITCODE
git show-ref --verify --quiet refs/heads/codex/inspection-ui-pages
$uiBranchExit = $LASTEXITCODE
"backendBranchExit=$backendBranchExit uiBranchExit=$uiBranchExit"
```

Expected:

```text
False
False
backendBranchExit=1 uiBranchExit=1
```

- [ ] **Step 3: 从固定检查点创建工作树**

Run:

```powershell
git worktree add -b codex/inspection-backend-data ".worktrees/inspection-backend-data" 6b13cf0e3b003e10e3e713c25ebc4c67da029258
git worktree add -b codex/inspection-ui-pages ".worktrees/inspection-ui-pages" 6b13cf0e3b003e10e3e713c25ebc4c67da029258
```

Expected:

```text
Preparing worktree (new branch 'codex/inspection-backend-data')
HEAD is now at 6b13cf0 test: record Android device acceptance
Preparing worktree (new branch 'codex/inspection-ui-pages')
HEAD is now at 6b13cf0 test: record Android device acceptance
```

- [ ] **Step 4: 核验分支、基线和清洁状态**

Run:

```powershell
git -C ".worktrees/inspection-backend-data" branch --show-current
git -C ".worktrees/inspection-backend-data" rev-parse HEAD
git -C ".worktrees/inspection-backend-data" status --porcelain
git -C ".worktrees/inspection-ui-pages" branch --show-current
git -C ".worktrees/inspection-ui-pages" rev-parse HEAD
git -C ".worktrees/inspection-ui-pages" status --porcelain
```

Expected:

```text
codex/inspection-backend-data
6b13cf0e3b003e10e3e713c25ebc4c67da029258
codex/inspection-ui-pages
6b13cf0e3b003e10e3e713c25ebc4c67da029258
```

两次 `status --porcelain` 均无输出。

- [ ] **Step 5: 证明对话 1 状态未变化**

Run:

```powershell
$dialogue1StatusAfter = git -C ".worktrees/inspection-service-flow" status --short
Compare-Object $dialogue1StatusBefore $dialogue1StatusAfter
git -C ".worktrees/inspection-service-flow" rev-parse HEAD
```

Expected:

```text
6b13cf0e3b003e10e3e713c25ebc4c67da029258
```

`Compare-Object` 无输出。

---

### Task 2: 向对话 2 下发后端数据轨第一阶段

**Files:**

- Modify/Create: `backend/**`
- Modify/Create: `contracts/**`
- Test: `backend/**/src/test/**`
- Frozen: `apps/mobile/**`

**Interfaces:**

- Consumes: 对话 2 已完成的“手机运行库只有一条冒烟服务”诊断
- Produces: 独立东营目录数据、真实接口验证、后端与契约提交

- [ ] **Step 1: 下发固定工作目录和禁止项**

Send to task `019fac15-ac5a-7db0-8662-1529fc9a393a`:

```text
用户已批准按隔离规格执行。你只在绝对路径
C:\Users\Admin（无密码）\Documents\低空经济\.worktrees\inspection-backend-data
工作，分支必须是 codex/inspection-backend-data，基线必须是
6b13cf0e3b003e10e3e713c25ebc4c67da029258。

第一阶段只处理你已诊断的 A：为独立开发环境补齐东营巡检目录数据，并验证
GET /api/v1/inspection/services?cityCode=370500&page=1&size=20
返回完整的多分类服务。先用测试复现“只有一条”的问题，再做最小实现。

允许修改：backend/**、contracts/**、后端测试。
禁止修改：apps/mobile/**、infra/**、根目录 .env、对话1工作树。
在对话1建立新检查点前，禁止修改 SecurityConfiguration.java 和
AuthenticationSecurityIntegrationTest.java。

独立环境固定：
COMPOSE_PROJECT_NAME=inspection-backend-data
INSPECTION_APP_PORT=18080
INSPECTION_POSTGRES_PORT=25432
INSPECTION_REDIS_PORT=26379
INSPECTION_MINIO_API_PORT=29000
INSPECTION_MINIO_CONSOLE_PORT=29001
后端、MinIO可绑定局域网；PostgreSQL、Redis必须仅绑定127.0.0.1。
不得重启或停止 inspection-local-*，不得操作手机、ADB、HBuilderX。

本阶段不做支付、退款，不扩大到前端。完成测试、空库迁移、接口返回验证和
git diff 边界检查后，形成独立提交并停止，等待验收；不得合并、推送。
```

- [ ] **Step 2: 等待任务确认边界**

Expected task commentary must include all of:

```text
codex/inspection-backend-data
.worktrees/inspection-backend-data
只处理后端数据与契约
不修改 apps/mobile
不操作 inspection-local
```

- [ ] **Step 3: 核验任务没有越界启动**

Run:

```powershell
git -C ".worktrees/inspection-backend-data" status --short
git -C ".worktrees/inspection-ui-pages" status --short
git -C ".worktrees/inspection-service-flow" rev-parse HEAD
```

Expected: UI 工作树仍无修改；对话 1 HEAD 仍为 `6b13cf0e3b003e10e3e713c25ebc4c67da029258`。

---

### Task 3: 向对话 3 下发移动端视觉轨第一阶段

**Files:**

- Create/Modify: `apps/mobile/src/features/inspection/presentation/platform/**`
- Create/Modify: `apps/mobile/src/static/images/inspection/platform/**`
- Create/Modify: `apps/mobile/src/features/inspection/tests/platform-*.test.ts`
- Frozen: 后端、契约、公共路由、现有预约/履约/交付页面

**Interfaces:**

- Consumes: 对话 3 已确认的 11 张参考图和“巡检主线接通”方案
- Produces: 首页、服务页、“我的”三个纯 presentation 页面组件及专项测试

- [ ] **Step 1: 下发固定工作目录和第一阶段范围**

Send to task `019fa29b-23b6-7d52-8105-bf5f6cee257d`:

```text
用户已批准按隔离规格执行。你只在绝对路径
C:\Users\Admin（无密码）\Documents\低空经济\.worktrees\inspection-ui-pages
工作，分支必须是 codex/inspection-ui-pages，基线必须是
6b13cf0e3b003e10e3e713c25ebc4c67da029258。

第一阶段只实现你已确认的三张公共页视觉：平台首页、综合服务页、“我的”页。
先创建可独立测试的 presentation 组件，不注册正式路由，不接真实 HTTP，不修改
现有页面壳。建议目录固定为：
apps/mobile/src/features/inspection/presentation/platform/home/**
apps/mobile/src/features/inspection/presentation/platform/service/**
apps/mobile/src/features/inspection/presentation/platform/mine/**
专项测试固定为：
apps/mobile/src/features/inspection/tests/platform-home.test.ts
apps/mobile/src/features/inspection/tests/platform-service.test.ts
apps/mobile/src/features/inspection/tests/platform-mine.test.ts

允许修改：上述 presentation/platform/**、static/images/inspection/platform/**、
三个 platform-*.test.ts。
禁止修改：backend/**、contracts/**、infra/**、pages.json、manifest.json、
inspection-routes.ts、uni-booking-http-client.ts、现有 booking/fulfillment/delivery
页面、登录与验收环境配置。

只允许启动 H5 5183；不得操作手机、ADB、HBuilderX、DCloud 云打包，不得占用
8080、19000、19001，不得重启 inspection-local-*。

使用冻结 OpenAPI 示例或仅开发环境可用的 presentation fixture；生产组件不能
导入 fixture。没有真实来源的天气、消息数、销量、认证数字必须隐藏。非巡检入口
展示“暂未开放”状态。支付、退款不实施，金额只显示“应付”或报价。

按 TDD 完成三个页面组件、375/390/430 宽度专项检查、加载/空/错状态测试和 H5
同视口截图；完成 git diff 边界检查后形成独立提交并停止，等待验收。不得合并、
推送，不得继续改 8 张交易流程页。
```

- [ ] **Step 2: 等待任务确认边界**

Expected task commentary must include all of:

```text
codex/inspection-ui-pages
.worktrees/inspection-ui-pages
只做三个 presentation 页面组件
不修改正式路由
不操作手机
```

- [ ] **Step 3: 核验任务没有越界启动**

Run:

```powershell
git -C ".worktrees/inspection-ui-pages" status --short
git -C ".worktrees/inspection-backend-data" status --short
git -C ".worktrees/inspection-service-flow" rev-parse HEAD
```

Expected: 后端工作树只出现 `backend/**` 或 `contracts/**` 修改；对话 1 HEAD 不变。

---

### Task 4: 并行期间保护手机验收环境

**Files:**

- Preserve: `.worktrees/inspection-service-flow/**`
- Preserve runtime: `inspection-local-*`
- Preserve ports: `8080`, `19000`, `19001`

**Interfaces:**

- Consumes: 两个任务的进度快照和 Git 状态
- Produces: 对话 1 可持续构建、安装和真机验收的稳定环境

- [ ] **Step 1: 获取对话 1 即时状态**

Use `wait_threads` with `timeoutMs: 0` for task:

```text
019fa66a-48c4-70b1-9154-f381e9df0ccb
```

Expected: 只读取状态，不向对话 1 发送消息。

- [ ] **Step 2: 检查三个工作树的变更边界**

Run:

```powershell
git -C ".worktrees/inspection-backend-data" status --short
git -C ".worktrees/inspection-ui-pages" status --short
git -C ".worktrees/inspection-service-flow" status --short
```

Expected:

- 对话 2 只出现 `backend/**` 或 `contracts/**`。
- 对话 3 只出现 `apps/mobile/src/features/inspection/presentation/platform/**`、`apps/mobile/src/static/images/inspection/platform/**`、`apps/mobile/src/features/inspection/tests/platform-*.test.ts`。
- 对话 1 原有变更不被删除、暂存或清理。

- [ ] **Step 3: 检查端口和容器所有权**

Run:

```powershell
docker ps --format "{{.Names}} {{.Ports}}" |
  Select-String "inspection-local|inspection-backend-data"
Get-NetTCPConnection -State Listen |
  Where-Object LocalPort -In 8080,18080,19000,19001,25432,26379,29000,29001 |
  Sort-Object LocalPort |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

Expected:

- `inspection-local-*` 保持运行且端口不变。
- 新后端环境只使用 `18080/25432/26379/29000/29001`。
- 对话 3 不占用上述后端端口。

- [ ] **Step 4: 发现越界时停止对应任务**

Stop only the task that violated the boundary. Do not clean or reset its worktree. Preserve evidence with:

```powershell
git -C "<violating-worktree>" status --short
git -C "<violating-worktree>" diff --stat
```

Report the exact path and resource conflict before any recovery action.

---

### Task 5: 并行成果验收与后续整合门禁

**Files:**

- Inspect only: `.worktrees/inspection-backend-data/**`
- Inspect only: `.worktrees/inspection-ui-pages/**`
- Preserve: `.worktrees/inspection-service-flow/**`

**Interfaces:**

- Consumes: 对话 2、3各自独立提交
- Produces: 可供单一整合者按顺序接入的提交哈希

- [ ] **Step 1: 验证对话 2 提交边界**

Run:

```powershell
git -C ".worktrees/inspection-backend-data" diff --name-only 6b13cf0e3b003e10e3e713c25ebc4c67da029258..HEAD
```

Expected: 每个路径均以 `backend/` 或 `contracts/` 开头。

- [ ] **Step 2: 验证对话 3 提交边界**

Run:

```powershell
git -C ".worktrees/inspection-ui-pages" diff --name-only 6b13cf0e3b003e10e3e713c25ebc4c67da029258..HEAD
```

Expected: 每个路径均属于 Global Constraints 中的对话 3 允许范围。

- [ ] **Step 3: 等待对话 1 建立可复验检查点**

Required evidence:

```text
对话1提交哈希
移动端测试通过数
后端测试通过数
Android构建结果
8080健康检查
手机验收入口
```

没有上述证据时不得开始整合。

- [ ] **Step 4: 按固定顺序交给单一整合者**

Integration order:

```text
对话1检查点
→ 对话2后端/契约提交
→ 对话3 presentation 提交
→ 整合者独占修改路由、真实HTTP客户端和页面壳
→ 全量测试、E2E、视觉回归、Android真机验收
```

并行任务本身不得执行 merge、rebase、cherry-pick 或 push。
