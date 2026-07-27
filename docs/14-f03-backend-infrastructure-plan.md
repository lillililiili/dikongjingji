# 14｜F03 后端基础设施实施计划

## Global Constraints

- Java 21、Spring Boot 3.5.x、MyBatis-Plus、PostgreSQL/PostGIS、Redis、MinIO。
- 只在 `backend/`、`infra/` 和必要的根配置中新增代码，不修改任何现有移动端页面。
- 沿用 `contracts/openapi-inspection-service-flow.yaml` 的直接 DTO 和 `ApiError`。
- 登录之外的业务 API 默认需要 Bearer JWT；权限必须同时校验 RBAC 和资源归属。
- 密钥只从环境变量读取，不写入源码、客户端包、日志或提交记录。
- 文件桶保持私有；客户端永不获取 MinIO 服务账号。
- 生产代码必须测试先行；配置和生成式构建文件可直接创建。
- 不使用生产 Mock、硬编码成功结果或绕过鉴权来通过测试。

## Task 1｜可构建的模块化后端与健康检查

目标：

- 创建 Maven 多模块：`bootstrap`、`modules/identity`、`modules/inspection`。
- 创建 Spring Boot 启动类、统一 `ApiError`、全局异常处理和 `/actuator/health`。
- 让两个已验收迁移目录作为构建资源进入固定 Flyway location。
- 先写上下文和错误响应测试，确认 RED，再写最小实现变为 GREEN。
- 提供 Maven Wrapper 或等效的项目内可重复构建入口。

验证：

```text
./mvnw -pl bootstrap -am test
```

## Task 2｜JWT 登录、RBAC 与资源授权基础

目标：

- 实现用户名/密码登录，使用 BCrypt。
- JWT 使用环境变量提供的 HMAC 密钥，令牌包含主体 ID、角色、组织和权限。
- 除登录和健康检查外默认需要认证。
- 提供权限编码常量、当前主体和值对象、RBAC 权限判断器。
- 提供资源归属授权接口，供后续预约/服务单应用服务实现。
- 覆盖无令牌、错误令牌、无权限、具有权限和令牌版本失效测试。

验证：

```text
./mvnw -pl bootstrap -am -Dtest=*Security*,*Authentication* test
```

## Task 3｜PostgreSQL、Redis 与 MinIO 适配器

目标：

- 配置 PostgreSQL/PostGIS 数据源、Flyway 和 MyBatis-Plus。
- 配置 Redis 连接、命名空间和幂等记录端口，不把 Redis 作为主数据源。
- 配置 MinIO 客户端、quarantine/private 桶和预签名上传/下载端口。
- MinIO 适配器校验用途、对象键、MIME、大小、状态和时效。
- 健康检查不得输出密码、令牌、对象密钥或预签名 URL。
- 覆盖配置绑定、对象键生成、预签名时效和 Redis key 隔离测试。

验证：

```text
./mvnw -pl bootstrap -am test
```

## Task 4｜Docker Compose 与基础设施冒烟

目标：

- `postgis/postgis:16-3.5`
- `redis:7.2.4-alpine`
- 官方 MinIO 镜像的固定发布标签
- 后端多阶段 Dockerfile
- `.env.example` 只提供非敏感占位符
- 健康检查、持久化卷、隔离网络和最小端口暴露
- 初始化 quarantine/private 桶，禁止匿名访问

验证：

```text
docker compose config
docker compose up -d --build
docker compose ps
GET /actuator/health -> 200
PostgreSQL 空库迁移成功
Redis ping 成功
MinIO 两个私有桶存在且匿名访问失败
```

## Task 5｜F03 审查与验收

- 针对每个任务执行规格与代码质量审查。
- 查看完整分支差异，确认没有移动端页面改动。
- 运行后端单元测试、集成测试、构建和容器冒烟。
- 提交 F03 独立变更集，等待用户验收后进入预约业务实现。

## Dependency Decisions

| 依赖 | 选择 | 必要性 | 许可证/替代 |
| --- | --- | --- | --- |
| Spring Boot | 3.5.16 | 项目锁定 3.5 线 | Apache-2.0；不升级 Boot 4 |
| MyBatis-Plus Boot 3 Starter | 3.5.17 | 项目锁定持久层 | Apache-2.0；可退回原生 MyBatis，但会偏离规范 |
| PostgreSQL JDBC、Flyway | Spring Boot BOM 管理 | 数据库和空库迁移 | PostgreSQL BSD、Flyway Community Apache-2.0 |
| MinIO Java SDK | 9.0.3 | 私有对象与预签名 URL | Apache-2.0；兼容 S3 SDK，但会增加迁移成本 |
| Testcontainers | Spring Boot BOM 管理 | 真实基础设施集成测试 | MIT；无 Docker 时先运行纯单元测试 |
| Redis Server | 7.2.4 | 缓存、限流和短期幂等 | BSD-3-Clause；不采用许可变化后的 7.4+ |

