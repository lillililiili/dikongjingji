# 11｜空仓库项目映射（T02）

## 1. 结论

当前仓库无既有业务代码，按开发包默认技术栈建立单仓库：

```text
apps/mobile/                 # uni-app + Vue 3 + TypeScript + Pinia
backend/                     # Java 21 + Spring Boot 3.5，T09 前不创建业务实现
infra/                       # PostgreSQL/PostGIS、Redis，T09 再创建
docs/                        # 规范、ADR 与验收记录
```

T03 只创建 `apps/mobile` 和真实入口骨架，不提前创建后端、数据库或业务 UI。

## 2. 移动端映射

```text
apps/mobile/src/
├── app-shell/               # 最小公共宿主壳
├── shared/city/             # 唯一 currentCity 状态
├── features/inspection/     # 巡检限界上下文
└── pages/                   # uni-app 路由生命周期桥接
```

- 公共根 Tab：`HOME | SERVICE | DISCOVER | MALL | MINE`。
- INS-M-002：`/pages/service/inspection/index`。
- INS-M-003：`/pages/service/inspection/catalog`。
- 页面路由壳只装载功能模块页面，不承载请求、价格或筛选逻辑。
- `features/inspection/public.ts` 只导出路由注册、`InspectionHostActionPort` 和必要公共类型。
- 本阶段不引入 UI 组件库；T04 根据参考图再决定是否需要 Wot UI。

## 3. 公共能力处理

- 城市：建立最小 `currentCity` 公共 Pinia store，默认无城市，不硬编码东营。
- 导航：建立五项根 Tab 和“服务 → 我要巡检”的真实点击路径。
- 天气、消息、客服、定位、媒体、鉴权、日志：仅保留宿主端口，不在 T03 伪造数据或实现。
- API：沿用 OpenAPI 的直接响应语义与 `ApiError`；真实网络层到 T10/T11 再实现。
- 功能开关：T03 只注册 `inspection.catalog.enabled` 语义，不开放详情、需求、咨询或预约。

## 4. 环境决定

- 复用现有 Git 和 Node.js，不重复安装。
- 使用 pnpm 11.9.0，Corepack 与 pnpm store 放在 `D:\DevCaches`。
- Java、Docker、Android Studio、HBuilderX 按任务需要分别在 T09、T13 前安装。
- Windows 只完成 H5/Android 开发；iOS 真机验收需 macOS 或云打包。

