# Inspection Order Center Entry Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立真实“我的订单”中心，使客户可从“我的”恢复到订单已提交、服务方案确认、履约和报告页面。

**Architecture:** 后端在现有预约聚合之上增加只读订单投影，以当前令牌中的 `subjectId` 限定数据范围，并聚合预约、当前方案和服务单状态。移动端新增订单中心领域模型、API、落点解析和页面；现有“我的”、提交结果及后续流程页面只负责调用统一导航，不复制订单状态判断。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis、PostgreSQL、OpenAPI 3.1、uni-app、Vue 3、TypeScript 4.9、Vitest。

## Global Constraints

- 只使用后端真实预约、方案和服务单数据；禁止 Fixture、本地最近订单、截图价格或硬编码成功状态进入生产路径。
- 列表接口不接受 `customerId`；服务端只使用认证令牌中的 `subjectId` 查询本人订单。
- 本阶段不增加支付、退款、评价、售后、取消订单、消息推送中心或商家工作台页面。
- 未发布方案时价格必须显示“待报价”；金额继续使用最小货币单位整数和 `CNY`。
- `CANCELLED` 只在“全部”中展示，不增加独立筛选页签。
- 订单中心必须覆盖加载、空数据、网络错误、登录恢复、下拉刷新、上拉分页和“没有更多”状态。
- Android/iOS 安全区和 375–430 CSS 逻辑像素宽度不得发生遮挡、横向溢出或单字竖排。
- 保留当前工作树内所有无关未提交改动；禁止批量格式化、重命名或清理其他页面。
- 用户明确要求确认界面无误后再云打包；本计划只构建 H5 和连接现有调试运行，不生成 APK。
- 未收到用户明确提交指令，不执行 `git add`、`git commit` 或 `git push`；每个任务以差异审查点代替自动提交。

## File Structure

### Backend

- Create `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/CustomerReservationQueryService.java`
  - 定义客户订单筛选、稳定阶段、分页结果和查询端口；完成分页校验、本人查询及地址解密。
- Create `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/PostgresCustomerReservationQueryStore.java`
  - 将 MyBatis 查询行转换为应用层只读投影。
- Modify `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationConfiguration.java`
  - 注册新的客户订单查询服务。
- Modify `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapper.java`
  - 新增本人订单分页查询和各阶段计数 SQL。
- Modify `backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionReservationController.java`
  - 在现有 `/api/v1/inspection/reservations` 集合资源上增加 GET。
- Create `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/CustomerReservationQueryServiceTest.java`
  - 覆盖分页、阶段总数、解密和参数边界。
- Modify `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapperSqlTest.java`
  - 锁定本人范围、阶段 CASE、稳定排序、LIMIT/OFFSET 和不读取敏感联系人字段。
- Modify `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationControllerTest.java`
  - 覆盖客户权限、本人数据、筛选和错误参数。
- Modify `contracts/openapi-inspection-service-flow.yaml`
  - 声明集合 GET、分页响应、阶段筛选和权限。
- Modify `contracts/inspection-service-flow.examples.json`
  - 增加测试环境订单分页响应示例。

### Mobile

- Create `apps/mobile/src/features/inspection/domain/service-flow/order-center.ts`
  - 定义订单筛选、阶段、摘要、分页和阶段计数类型。
- Create `apps/mobile/src/features/inspection/application/service-flow/inspection-order-navigation.ts`
  - 将真实阶段与稳定 ID 解析为现有页面 URL；ID 不一致时失败关闭。
- Create `apps/mobile/src/features/inspection/data/service-flow/inspection-order-api.ts`
  - 调用真实本人订单分页接口。
- Modify `apps/mobile/src/features/inspection/data/service-flow/uni-booking-http-client.ts`
  - 导出使用现有登录恢复机制的 `inspectionOrderApi`。
- Create `apps/mobile/src/features/inspection/presentation/orders/order-center-view.ts`
  - 集中管理页签、状态文案、按钮文案、金额和“我的”近期订单映射。
- Create `apps/mobile/src/features/inspection/presentation/orders/components/InspectionOrderCard.vue`
  - 渲染一个真实订单摘要，不包含导航或请求。
- Create `apps/mobile/src/features/inspection/presentation/orders/pages/InspectionOrderCenterPage.vue`
  - 组合筛选、分页、刷新、空错状态和订单落点。
- Create `apps/mobile/src/pages/mine/inspection-orders.vue`
  - uni-app 路由壳。
- Modify `apps/mobile/src/pages.json`
  - 注册自定义导航栏的“我的订单”页面。
- Modify `apps/mobile/src/features/inspection/navigation/inspection-routes.ts`
  - 增加订单中心 URL 构建器和登录恢复白名单。
- Modify `apps/mobile/src/pages/mine/index.vue`
  - 登录后加载真实近期订单及阶段计数，接通全部订单、状态和卡片入口。
- Modify `apps/mobile/src/features/inspection/presentation/platform/mine/platform-mine.ts`
  - 允许近期订单展示“已取消”，但保持顶部只有五个可筛选状态。
- Modify `apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingSubmittedPage.vue`
  - 返回真实订单中心，并在重新显示时刷新方案状态。
- Modify `apps/mobile/src/features/inspection/presentation/plan/pages/InspectionPlanConfirmationPage.vue`
  - 无历史栈或异常状态时回到订单中心。
- Modify `apps/mobile/src/features/inspection/presentation/fulfillment/pages/InspectionFulfillmentPage.vue`
  - 从订单中心进入后可可靠返回订单中心。
- Modify `apps/mobile/src/features/inspection/presentation/delivery/pages/InspectionDeliveryPage.vue`
  - 直接从订单中心进入报告页时，返回动作不再强制绕到履约页。
- Create `apps/mobile/src/features/inspection/tests/order-center.test.ts`
  - 覆盖 API、阶段落点、页面注册、展示映射和生产边界。
- Modify `apps/mobile/src/features/inspection/tests/platform-mine.test.ts`
  - 覆盖真实近期订单与状态入口接线。
- Modify `apps/mobile/src/features/inspection/tests/booking-flow.test.ts`
  - 锁定“返回订单”和提交结果刷新行为。
- Modify `apps/mobile/src/features/inspection/tests/plan-flow.test.ts`
  - 锁定方案页异常返回订单中心。
- Modify `apps/mobile/src/features/inspection/tests/fulfillment-flow.test.ts`
  - 锁定履约页返回订单中心。
- Modify `apps/mobile/src/features/inspection/tests/delivery-flow.test.ts`
  - 锁定报告页按来源返回。

---

### Task 1: Backend Customer Order Query Model

**Files:**
- Create: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/CustomerReservationQueryService.java`
- Test: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/CustomerReservationQueryServiceTest.java`

**Interfaces:**
- Consumes: `SensitiveDataCipher.decrypt(String)` 和认证层提供的 `UUID subjectId`。
- Produces:
  - `CustomerReservationFilter.parse(String)`
  - `CustomerReservationQueryService.listOwn(UUID, CustomerReservationFilter, int, int)`
  - `CustomerReservationQueryStore.findOwn(UUID, CustomerReservationFilter, int, int)`
  - `CustomerReservationQueryStore.countOwn(UUID)`

- [ ] **Step 1: Write the failing application-service tests**

```java
@Test
void returnsOnlyTheRequestedCustomersPageAndDecryptsAddress() {
    var store = new InMemoryCustomerReservationQueryStore();
    store.rows = List.of(stored(
            CustomerReservationStage.AWAITING_CONFIRMATION,
            "x:东营市东营区黄河路 88 号",
            PLAN_ID,
            null,
            34900L));
    store.counts = new CustomerReservationCountSnapshot(1, 0, 1, 0, 0, 0);
    var service = new CustomerReservationQueryService(store, new ReversibleCipher());

    CustomerReservationPage page = service.listOwn(
            CUSTOMER_ID,
            CustomerReservationFilter.AWAITING_CONFIRMATION,
            1,
            20);

    assertThat(store.customerId).isEqualTo(CUSTOMER_ID);
    assertThat(page.items()).singleElement().satisfies(order -> {
        assertThat(order.stage()).isEqualTo(CustomerReservationStage.AWAITING_CONFIRMATION);
        assertThat(order.locationSummary()).isEqualTo("东营市东营区黄河路 88 号");
        assertThat(order.currentPlanId()).isEqualTo(PLAN_ID);
        assertThat(order.quoteAmountMinor()).isEqualTo(34900L);
    });
    assertThat(page.total()).isEqualTo(1);
    assertThat(page.hasMore()).isFalse();
}

@Test
void validatesFiltersAndPagingBeforeCallingPersistence() {
    var service = new CustomerReservationQueryService(
            new InMemoryCustomerReservationQueryStore(),
            new ReversibleCipher());

    assertThatThrownBy(() -> service.listOwn(null, CustomerReservationFilter.ALL, 1, 20))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.listOwn(CUSTOMER_ID, CustomerReservationFilter.ALL, 0, 20))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.listOwn(CUSTOMER_ID, CustomerReservationFilter.ALL, 1, 51))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> CustomerReservationFilter.parse("CANCELLED"))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Run the focused test and confirm the red state**

Run from `backend`:

```powershell
.\mvnw.cmd -B -pl :inspection "-Dtest=CustomerReservationQueryServiceTest" test
```

Expected: FAIL because `CustomerReservationQueryService` and its order projection types do not exist.

- [ ] **Step 3: Add the stable query types and service**

Create the following package-level model in `CustomerReservationQueryService.java`:

```java
public final class CustomerReservationQueryService {
    private static final int MAX_PAGE_SIZE = 50;
    private final CustomerReservationQueryStore store;
    private final SensitiveDataCipher cipher;

    public CustomerReservationQueryService(
            CustomerReservationQueryStore store,
            SensitiveDataCipher cipher
    ) {
        this.store = store;
        this.cipher = cipher;
    }

    public CustomerReservationPage listOwn(
            UUID customerId,
            CustomerReservationFilter filter,
            int page,
            int size
    ) {
        if (customerId == null || filter == null || page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("customer order query is invalid");
        }
        int offset = Math.multiplyExact(page - 1, size);
        CustomerReservationCountSnapshot counts = store.countOwn(customerId);
        long total = counts.totalFor(filter);
        List<CustomerReservationSummary> items = store.findOwn(customerId, filter, size, offset)
                .stream()
                .map(row -> new CustomerReservationSummary(
                        row.reservationId(),
                        row.reservationNo(),
                        row.serviceId(),
                        row.serviceTitle(),
                        row.serviceCoverUrl(),
                        row.cityCode(),
                        cipher.decrypt(row.locationCiphertext()),
                        row.preferredServiceDate(),
                        row.submittedAt(),
                        row.stage(),
                        row.reservationStatus(),
                        row.currentPlanId(),
                        row.serviceOrderId(),
                        row.serviceOrderStatus(),
                        row.quoteAmountMinor(),
                        "CNY"))
                .toList();
        return new CustomerReservationPage(
                items,
                page,
                size,
                total,
                (long) page * size < total,
                counts.visibleStageCounts());
    }
}

enum CustomerReservationFilter {
    ALL,
    AWAITING_PLAN,
    AWAITING_CONFIRMATION,
    IN_PROGRESS,
    AWAITING_ACCEPTANCE,
    COMPLETED;

    static CustomerReservationFilter parse(String value) {
        try {
            return value == null || value.isBlank()
                    ? ALL
                    : valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("order stage is invalid", exception);
        }
    }
}

enum CustomerReservationStage {
    AWAITING_PLAN,
    AWAITING_CONFIRMATION,
    IN_PROGRESS,
    AWAITING_ACCEPTANCE,
    COMPLETED,
    CANCELLED
}
```

Define the query records and port exactly once in the same file:

```java
record CustomerReservationSummary(
        UUID reservationId,
        String reservationNo,
        UUID serviceId,
        String serviceTitle,
        String serviceCoverUrl,
        String cityCode,
        String locationSummary,
        LocalDate preferredServiceDate,
        Instant submittedAt,
        CustomerReservationStage stage,
        String reservationStatus,
        UUID currentPlanId,
        UUID serviceOrderId,
        String serviceOrderStatus,
        Long quoteAmountMinor,
        String currency
) { }

record StoredCustomerReservationSummary(
        UUID reservationId,
        String reservationNo,
        UUID serviceId,
        String serviceTitle,
        String serviceCoverUrl,
        String cityCode,
        String locationCiphertext,
        LocalDate preferredServiceDate,
        Instant submittedAt,
        CustomerReservationStage stage,
        String reservationStatus,
        UUID currentPlanId,
        UUID serviceOrderId,
        String serviceOrderStatus,
        Long quoteAmountMinor
) { }

record CustomerReservationStageCounts(
        long awaitingPlan,
        long awaitingConfirmation,
        long inProgress,
        long awaitingAcceptance,
        long completed
) { }

record CustomerReservationCountSnapshot(
        long allCount,
        long awaitingPlan,
        long awaitingConfirmation,
        long inProgress,
        long awaitingAcceptance,
        long completed
) {
    long totalFor(CustomerReservationFilter filter) {
        return switch (filter) {
            case ALL -> allCount;
            case AWAITING_PLAN -> awaitingPlan;
            case AWAITING_CONFIRMATION -> awaitingConfirmation;
            case IN_PROGRESS -> inProgress;
            case AWAITING_ACCEPTANCE -> awaitingAcceptance;
            case COMPLETED -> completed;
        };
    }

    CustomerReservationStageCounts visibleStageCounts() {
        return new CustomerReservationStageCounts(
                awaitingPlan,
                awaitingConfirmation,
                inProgress,
                awaitingAcceptance,
                completed);
    }
}

record CustomerReservationPage(
        List<CustomerReservationSummary> items,
        int page,
        int size,
        long total,
        boolean hasMore,
        CustomerReservationStageCounts stageCounts
) { }

interface CustomerReservationQueryStore {
    List<StoredCustomerReservationSummary> findOwn(
            UUID customerId,
            CustomerReservationFilter filter,
            int limit,
            int offset);

    CustomerReservationCountSnapshot countOwn(UUID customerId);
}
```

- [ ] **Step 4: Run the focused test and confirm the green state**

```powershell
.\mvnw.cmd -B -pl :inspection "-Dtest=CustomerReservationQueryServiceTest" test
```

Expected: PASS with both application-service tests green.

- [ ] **Step 5: Review the task diff without staging**

```powershell
git diff --check -- backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/CustomerReservationQueryService.java backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/CustomerReservationQueryServiceTest.java
git diff --stat -- backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/CustomerReservationQueryService.java backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/CustomerReservationQueryServiceTest.java
```

Expected: no whitespace errors; only the two approved files appear. Do not stage or commit.

### Task 2: PostgreSQL Order Projection

**Files:**
- Create: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/PostgresCustomerReservationQueryStore.java`
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationConfiguration.java`
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapper.java`
- Modify: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapperSqlTest.java`

**Interfaces:**
- Consumes: Task 1 `CustomerReservationQueryStore` and `CustomerReservationFilter`.
- Produces: a PostgreSQL-backed implementation and Spring bean for `CustomerReservationQueryService`.

- [ ] **Step 1: Add failing SQL-contract tests**

```java
@Test
void customerOrderProjectionIsScopedAndUsesStableLifecycleMapping() throws Exception {
    Method list = ReservationPersistenceMapper.class.getMethod(
            "findCustomerReservations",
            UUID.class,
            String.class,
            int.class,
            int.class);
    String sql = String.join(" ", list.getAnnotation(Select.class).value());

    assertThat(sql)
            .contains("r.customer_id = #{customerId}")
            .contains("WHEN r.status = 'CANCELLED' THEN 'CANCELLED'")
            .contains("WHEN o.status = 'ACCEPTED' THEN 'COMPLETED'")
            .contains("WHEN o.status = 'DELIVERED' THEN 'AWAITING_ACCEPTANCE'")
            .contains("WHEN o.status IN ('ARRANGING', 'IN_PROGRESS') THEN 'IN_PROGRESS'")
            .contains("WHEN p.status = 'PUBLISHED' THEN 'AWAITING_CONFIRMATION'")
            .contains("ELSE 'AWAITING_PLAN'")
            .contains("ORDER BY lifecycle_updated_at DESC, r.id DESC")
            .contains("LIMIT #{limit} OFFSET #{offset}")
            .doesNotContain("contact_mobile_ciphertext")
            .doesNotContain("contact_name_ciphertext");
}
```

- [ ] **Step 2: Run the mapper test and confirm it fails**

```powershell
.\mvnw.cmd -B -pl :inspection "-Dtest=ReservationPersistenceMapperSqlTest" test
```

Expected: FAIL because `findCustomerReservations` is missing.

- [ ] **Step 3: Add the list and count queries**

Add `findCustomerReservations(...)` using a lateral join for the current published or
confirmed plan and a left join for the unique service order. Use this lifecycle CASE in
both list and count queries:

```sql
CASE
    WHEN r.status = 'CANCELLED' THEN 'CANCELLED'
    WHEN o.status = 'ACCEPTED' THEN 'COMPLETED'
    WHEN o.status = 'DELIVERED' THEN 'AWAITING_ACCEPTANCE'
    WHEN o.status IN ('ARRANGING', 'IN_PROGRESS') THEN 'IN_PROGRESS'
    WHEN p.status = 'PUBLISHED' THEN 'AWAITING_CONFIRMATION'
    ELSE 'AWAITING_PLAN'
END
```

The list query must select:

```sql
r.id reservation_id,
r.reservation_no,
r.service_id,
r.service_title_snapshot service_title,
r.service_cover_url_snapshot service_cover_url,
r.city_code,
r.address_ciphertext location_ciphertext,
r.preferred_service_date,
r.submitted_at,
lifecycle.stage,
r.status reservation_status,
p.id current_plan_id,
o.id service_order_id,
o.status service_order_status,
p.total_amount_minor quote_amount_minor
```

Apply the filter and stable ordering:

```sql
WHERE r.customer_id = #{customerId}
  AND (#{stage} = 'ALL' OR lifecycle.stage = #{stage})
ORDER BY lifecycle_updated_at DESC, r.id DESC
LIMIT #{limit} OFFSET #{offset}
```

The count query returns `all_count` plus one `count(*) FILTER` for each visible stage.
Cancelled rows contribute only to `all_count`.

Add these mapper signatures and rows:

```java
@Select("""
        WITH order_projection AS (
            SELECT r.id,
                   CASE
                       WHEN r.status = 'CANCELLED' THEN 'CANCELLED'
                       WHEN o.status = 'ACCEPTED' THEN 'COMPLETED'
                       WHEN o.status = 'DELIVERED' THEN 'AWAITING_ACCEPTANCE'
                       WHEN o.status IN ('ARRANGING', 'IN_PROGRESS') THEN 'IN_PROGRESS'
                       WHEN p.status = 'PUBLISHED' THEN 'AWAITING_CONFIRMATION'
                       ELSE 'AWAITING_PLAN'
                   END stage
            FROM inspection_reservation r
            LEFT JOIN LATERAL (
                SELECT current_plan.id, current_plan.status
                FROM inspection_service_plan current_plan
                WHERE current_plan.reservation_id = r.id
                  AND current_plan.status IN ('PUBLISHED', 'CONFIRMED')
                ORDER BY current_plan.plan_version DESC
                LIMIT 1
            ) p ON true
            LEFT JOIN inspection_service_order o ON o.reservation_id = r.id
            WHERE r.customer_id = #{customerId}
        )
        SELECT count(*) all_count,
               count(*) FILTER (WHERE stage = 'AWAITING_PLAN') awaiting_plan,
               count(*) FILTER (WHERE stage = 'AWAITING_CONFIRMATION') awaiting_confirmation,
               count(*) FILTER (WHERE stage = 'IN_PROGRESS') in_progress,
               count(*) FILTER (WHERE stage = 'AWAITING_ACCEPTANCE') awaiting_acceptance,
               count(*) FILTER (WHERE stage = 'COMPLETED') completed
        FROM order_projection
        """)
CustomerReservationCountsRow countCustomerReservations(
        @Param("customerId") UUID customerId);

List<CustomerReservationSummaryRow> findCustomerReservations(
        @Param("customerId") UUID customerId,
        @Param("stage") String stage,
        @Param("limit") int limit,
        @Param("offset") int offset);

record CustomerReservationSummaryRow(
        UUID reservationId,
        String reservationNo,
        UUID serviceId,
        String serviceTitle,
        String serviceCoverUrl,
        String cityCode,
        byte[] locationCiphertext,
        LocalDate preferredServiceDate,
        Instant submittedAt,
        String stage,
        String reservationStatus,
        UUID currentPlanId,
        UUID serviceOrderId,
        String serviceOrderStatus,
        Long quoteAmountMinor
) { }

record CustomerReservationCountsRow(
        long allCount,
        long awaitingPlan,
        long awaitingConfirmation,
        long inProgress,
        long awaitingAcceptance,
        long completed
) { }
```

- [ ] **Step 4: Implement the PostgreSQL adapter and bean**

```java
final class PostgresCustomerReservationQueryStore
        implements CustomerReservationQueryStore {
    private final ReservationPersistenceMapper mapper;

    PostgresCustomerReservationQueryStore(ReservationPersistenceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public List<StoredCustomerReservationSummary> findOwn(
            UUID customerId,
            CustomerReservationFilter filter,
            int limit,
            int offset
    ) {
        return mapper.findCustomerReservations(
                        customerId, filter.name(), limit, offset)
                .stream()
                .map(row -> new StoredCustomerReservationSummary(
                        row.reservationId(),
                        row.reservationNo(),
                        row.serviceId(),
                        row.serviceTitle(),
                        row.serviceCoverUrl(),
                        row.cityCode(),
                        new String(row.locationCiphertext(), StandardCharsets.UTF_8),
                        row.preferredServiceDate(),
                        row.submittedAt(),
                        CustomerReservationStage.valueOf(row.stage()),
                        row.reservationStatus(),
                        row.currentPlanId(),
                        row.serviceOrderId(),
                        row.serviceOrderStatus(),
                        row.quoteAmountMinor()))
                .toList();
    }

    @Override
    public CustomerReservationCountSnapshot countOwn(UUID customerId) {
        ReservationPersistenceMapper.CustomerReservationCountsRow row =
                mapper.countCustomerReservations(customerId);
        return new CustomerReservationCountSnapshot(
                row.allCount(),
                row.awaitingPlan(),
                row.awaitingConfirmation(),
                row.inProgress(),
                row.awaitingAcceptance(),
                row.completed());
    }
}
```

Register the service without refactoring the existing reservation bean:

```java
@Bean
CustomerReservationQueryService customerReservationQueryService(
        ReservationPersistenceMapper mapper,
        @Value("${INSPECTION_DATA_ENCRYPTION_BASE64_KEY}") String key
) {
    return new CustomerReservationQueryService(
            new PostgresCustomerReservationQueryStore(mapper),
            new AesGcmSensitiveDataCipher(key));
}
```

- [ ] **Step 5: Run focused persistence and application tests**

```powershell
.\mvnw.cmd -B -pl :inspection "-Dtest=CustomerReservationQueryServiceTest,ReservationPersistenceMapperSqlTest" test
```

Expected: PASS. No migration is added because existing customer/time indexes and unique
reservation-to-order relation support this read projection.

- [ ] **Step 6: Review the task diff without staging**

```powershell
git diff --check -- backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapper.java backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapperSqlTest.java
```

Expected: no whitespace errors and no migration, schema or unrelated catalog changes. Do not stage or commit.

### Task 3: Customer Order HTTP Contract

**Files:**
- Modify: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionReservationController.java`
- Modify: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationControllerTest.java`
- Modify: `contracts/openapi-inspection-service-flow.yaml`
- Modify: `contracts/inspection-service-flow.examples.json`

**Interfaces:**
- Consumes: `CustomerReservationQueryService.listOwn(...)`.
- Produces: `GET /api/v1/inspection/reservations?page=1&size=20&stage=ALL`.

- [ ] **Step 1: Add failing controller tests**

```java
@Test
void customerListsOnlyOwnOrdersWithStableStageCounts() throws Exception {
    queryStore.rows = List.of(stored(
            CustomerReservationStage.AWAITING_PLAN,
            "x:东营市测试路8号",
            null,
            null,
            null));
    queryStore.counts = new CustomerReservationCountSnapshot(1, 1, 0, 0, 0, 0);

    mvc.perform(get("/api/v1/inspection/reservations")
                    .param("page", "1")
                    .param("size", "20")
                    .param("stage", "ALL")
                    .principal(customerJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].reservationId").exists())
            .andExpect(jsonPath("$.items[0].stage").value("AWAITING_PLAN"))
            .andExpect(jsonPath("$.items[0].quoteAmountMinor").doesNotExist())
            .andExpect(jsonPath("$.stageCounts.AWAITING_PLAN").value(1))
            .andExpect(jsonPath("$.page").value(1))
            .andExpect(jsonPath("$.hasMore").value(false));
}

@Test
void customerOrderCollectionRejectsInvalidStageAndMissingPermission() throws Exception {
    mvc.perform(get("/api/v1/inspection/reservations")
                    .param("stage", "CANCELLED")
                    .principal(customerJwt()))
            .andExpect(status().isBadRequest());

    mvc.perform(get("/api/v1/inspection/reservations")
                    .principal(subject(
                            CUSTOMER,
                            Set.of("CUSTOMER"),
                            null,
                            Set.of())))
            .andExpect(status().isForbidden());
}
```

- [ ] **Step 2: Run the controller test and confirm it fails**

```powershell
.\mvnw.cmd -B -pl :bootstrap -am "-Dtest=InspectionReservationControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: FAIL because the collection GET and query-service constructor dependency are absent.

- [ ] **Step 3: Add the protected collection GET**

Update test setup with a dedicated in-memory query port so existing create/read tests remain
independent:

```java
queryStore = new InMemoryCustomerReservationQueryStore();
queryService = new CustomerReservationQueryService(
        queryStore,
        new ReversibleTestCipher());
mvc = MockMvcBuilders
        .standaloneSetup(new InspectionReservationController(service, queryService))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
```

```java
private final ReservationApplicationService service;
private final CustomerReservationQueryService queryService;

public InspectionReservationController(
        ReservationApplicationService service,
        CustomerReservationQueryService queryService
) {
    this.service = service;
    this.queryService = queryService;
}

@GetMapping
public CustomerReservationPageResponse list(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "ALL") String stage,
        Authentication authentication
) {
    CurrentSubject subject = currentSubject(authentication);
    if (!subject.roles().contains("CUSTOMER")
            || !subject.permissions().contains("inspection.reservation.read.own")) {
        throw new AccessDeniedException("customer own reservation permission is required");
    }
    return CustomerReservationPageResponse.from(
            queryService.listOwn(
                    subject.subjectId(),
                    CustomerReservationFilter.parse(stage),
                    page,
                    size));
}
```

`CustomerReservationPageResponse` must serialize:

```json
{
  "items": [],
  "page": 1,
  "size": 20,
  "total": 0,
  "hasMore": false,
  "stageCounts": {
    "AWAITING_PLAN": 0,
    "AWAITING_CONFIRMATION": 0,
    "IN_PROGRESS": 0,
    "AWAITING_ACCEPTANCE": 0,
    "COMPLETED": 0
  }
}
```

Do not return contact name, phone, coordinates or note in collection items.

Use explicit response records so the HTTP schema does not expose persistence rows:

```java
public record CustomerReservationSummaryResponse(
        UUID reservationId,
        String reservationNo,
        UUID serviceId,
        String serviceTitle,
        String serviceCoverUrl,
        String cityCode,
        String locationSummary,
        LocalDate preferredServiceDate,
        Instant submittedAt,
        String stage,
        String reservationStatus,
        UUID currentPlanId,
        UUID serviceOrderId,
        String serviceOrderStatus,
        Long quoteAmountMinor,
        String currency
) {
    static CustomerReservationSummaryResponse from(CustomerReservationSummary value) {
        return new CustomerReservationSummaryResponse(
                value.reservationId(),
                value.reservationNo(),
                value.serviceId(),
                value.serviceTitle(),
                value.serviceCoverUrl(),
                value.cityCode(),
                value.locationSummary(),
                value.preferredServiceDate(),
                value.submittedAt(),
                value.stage().name(),
                value.reservationStatus(),
                value.currentPlanId(),
                value.serviceOrderId(),
                value.serviceOrderStatus(),
                value.quoteAmountMinor(),
                value.currency());
    }
}

public record CustomerReservationPageResponse(
        List<CustomerReservationSummaryResponse> items,
        int page,
        int size,
        long total,
        boolean hasMore,
        Map<String, Long> stageCounts
) {
    static CustomerReservationPageResponse from(CustomerReservationPage value) {
        CustomerReservationStageCounts counts = value.stageCounts();
        return new CustomerReservationPageResponse(
                value.items().stream()
                        .map(CustomerReservationSummaryResponse::from)
                        .toList(),
                value.page(),
                value.size(),
                value.total(),
                value.hasMore(),
                Map.of(
                        "AWAITING_PLAN", counts.awaitingPlan(),
                        "AWAITING_CONFIRMATION", counts.awaitingConfirmation(),
                        "IN_PROGRESS", counts.inProgress(),
                        "AWAITING_ACCEPTANCE", counts.awaitingAcceptance(),
                        "COMPLETED", counts.completed()));
    }
}
```

- [ ] **Step 4: Update OpenAPI and fixture-only examples**

Add a GET operation under `/api/v1/inspection/reservations` with:

```yaml
get:
  tags: [CustomerInspectionFlow]
  operationId: listMyInspectionReservations
  summary: 分页查询当前客户自己的巡检订单
  x-required-permission: inspection.reservation.read.own
  parameters:
    - name: page
      in: query
      schema: { type: integer, minimum: 1, default: 1 }
    - name: size
      in: query
      schema: { type: integer, minimum: 1, maximum: 50, default: 20 }
    - name: stage
      in: query
      schema:
        $ref: "#/components/schemas/CustomerReservationFilter"
  responses:
    "200":
      description: 当前客户的巡检订单分页
      content:
        application/json:
          schema:
            $ref: "#/components/schemas/CustomerReservationPage"
    "400":
      $ref: "#/components/responses/BadRequest"
    "401":
      $ref: "#/components/responses/Unauthorized"
    "403":
      $ref: "#/components/responses/Forbidden"
```

Add schema enums with the exact codes from Tasks 1–2 and add a
`reservationPage` example whose `meta.environment` remains `test-fixture-only`.

- [ ] **Step 5: Run controller and full inspection-module tests**

```powershell
.\mvnw.cmd -B -pl :bootstrap -am "-Dtest=InspectionReservationControllerTest,CustomerReservationQueryServiceTest,ReservationPersistenceMapperSqlTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: PASS; another customer cannot infer any row or count.

- [ ] **Step 6: Review contract and Java diffs**

```powershell
git diff --check -- backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionReservationController.java backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationControllerTest.java contracts/openapi-inspection-service-flow.yaml contracts/inspection-service-flow.examples.json
```

Expected: no whitespace errors; POST and single-resource GET remain unchanged. Do not stage or commit.

### Task 4: Mobile Order Types, API, and Destination Resolution

**Files:**
- Create: `apps/mobile/src/features/inspection/domain/service-flow/order-center.ts`
- Create: `apps/mobile/src/features/inspection/application/service-flow/inspection-order-navigation.ts`
- Create: `apps/mobile/src/features/inspection/data/service-flow/inspection-order-api.ts`
- Modify: `apps/mobile/src/features/inspection/data/service-flow/uni-booking-http-client.ts`
- Modify: `apps/mobile/src/features/inspection/navigation/inspection-routes.ts`
- Create: `apps/mobile/src/features/inspection/tests/order-center.test.ts`

**Interfaces:**
- Produces:
  - `inspectionOrderApi.list({ stage, page, size })`
  - `buildInspectionOrderCenterUrl(stage?)`
  - `resolveInspectionOrderDestination(order)`

- [ ] **Step 1: Write failing API and navigation tests**

```ts
it('queries the authenticated customer collection with serialized paging', async () => {
  const calls: Array<{ url: string; method: string }> = []
  const api = createInspectionOrderApi({
    request: async <T,>(input: { url: string; method: 'GET' | 'POST' }) => {
      calls.push(input)
      return emptyOrderPage() as T
    },
    putFile: async () => ({ etag: 'unused' }),
  }, { getAccessToken: () => 'customer-token' })

  await api.list({ stage: 'AWAITING_CONFIRMATION', page: 2, size: 20 })

  expect(calls).toEqual([{
    method: 'GET',
    url: '/api/v1/inspection/reservations?stage=AWAITING_CONFIRMATION&page=2&size=20',
  }])
})

it('routes each stage only when its stable target id exists', () => {
  expect(resolveInspectionOrderDestination(order({
    stage: 'AWAITING_PLAN',
  }))).toBe(`/pages/service/inspection/booking-submitted?reservationId=${RESERVATION_ID}`)
  expect(resolveInspectionOrderDestination(order({
    stage: 'AWAITING_CONFIRMATION',
    currentPlanId: PLAN_ID,
  }))).toBe(`/pages/service/inspection/plan-confirmation?planId=${PLAN_ID}`)
  expect(resolveInspectionOrderDestination(order({
    stage: 'IN_PROGRESS',
    serviceOrderId: ORDER_ID,
  }))).toBe(`/pages/service/inspection/fulfillment?serviceOrderId=${ORDER_ID}`)
  expect(resolveInspectionOrderDestination(order({
    stage: 'AWAITING_ACCEPTANCE',
    serviceOrderId: ORDER_ID,
  }))).toBe(`/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}`)
  expect(resolveInspectionOrderDestination(order({
    stage: 'COMPLETED',
    serviceOrderId: ORDER_ID,
  }))).toBe(`/pages/service/inspection/delivery?serviceOrderId=${ORDER_ID}`)
  expect(resolveInspectionOrderDestination(order({
    stage: 'AWAITING_CONFIRMATION',
    currentPlanId: null,
  }))).toBeNull()
})
```

- [ ] **Step 2: Run the focused Vitest file and confirm the red state**

Run from `apps/mobile`:

```powershell
pnpm.cmd test -- order-center.test.ts
```

Expected: FAIL because the new domain, API and navigation files do not exist.

- [ ] **Step 3: Add exact domain and API types**

```ts
export type InspectionOrderStage =
  | 'AWAITING_PLAN'
  | 'AWAITING_CONFIRMATION'
  | 'IN_PROGRESS'
  | 'AWAITING_ACCEPTANCE'
  | 'COMPLETED'
  | 'CANCELLED'

export type InspectionOrderFilter =
  | 'ALL'
  | Exclude<InspectionOrderStage, 'CANCELLED'>

export interface InspectionOrderSummary {
  reservationId: string
  reservationNo: string
  serviceId: string
  serviceTitle: string
  serviceCoverUrl: string | null
  cityCode: string
  locationSummary: string
  preferredServiceDate: string
  submittedAt: string
  stage: InspectionOrderStage
  reservationStatus: 'SUBMITTED' | 'PLAN_PUBLISHED' | 'CANCELLED'
  currentPlanId: string | null
  serviceOrderId: string | null
  serviceOrderStatus: 'ARRANGING' | 'IN_PROGRESS' | 'DELIVERED' | 'ACCEPTED' | null
  quoteAmountMinor: number | null
  currency: 'CNY'
}

export interface InspectionOrderPage {
  items: InspectionOrderSummary[]
  page: number
  size: number
  total: number
  hasMore: boolean
  stageCounts: Record<Exclude<InspectionOrderStage, 'CANCELLED'>, number>
}
```

Implement the API with the same HTTP and token ports already used by booking:

```ts
export interface InspectionOrderApi {
  list(input: {
    stage: InspectionOrderFilter
    page: number
    size: number
  }): Promise<InspectionOrderPage>
}

export function createInspectionOrderApi(
  http: BookingHttpClient,
  accessToken: AccessTokenPort = { getAccessToken: () => null },
): InspectionOrderApi {
  return {
    list(input) {
      if (!Number.isInteger(input.page) || input.page < 1
          || !Number.isInteger(input.size) || input.size < 1 || input.size > 50) {
        return Promise.reject(new Error('INVALID_ORDER_PAGE'))
      }
      const token = accessToken.getAccessToken()
      const query = [
        `stage=${encodeURIComponent(input.stage)}`,
        `page=${input.page}`,
        `size=${input.size}`,
      ].join('&')
      return http.request<InspectionOrderPage>({
        url: `/api/v1/inspection/reservations?${query}`,
        method: 'GET',
        headers: token ? { Authorization: `Bearer ${token}` } : {},
      })
    },
  }
}
```

- [ ] **Step 4: Add route and destination helpers**

```ts
export const INSPECTION_ORDER_CENTER_PATH = '/pages/mine/inspection-orders'

export function buildInspectionOrderCenterUrl(
  stage: InspectionOrderFilter = 'ALL',
): string {
  return `${INSPECTION_ORDER_CENTER_PATH}?stage=${encodeURIComponent(stage)}`
}
```

Extend the login-return whitelist so the parameterless order center is safe:

```ts
[INSPECTION_ORDER_CENTER_PATH]: {
  allowed: ['stage'],
},
```

Refactor the whitelist rule type to `required?: string`; retain all existing required-ID
checks:

```ts
const INSPECTION_PROTECTED_RETURN_KEYS: Record<
  string,
  { required?: string; allowed: readonly string[] }
> = {
  // keep existing entries
}

const values = parseQuery(query)
if (rule.required && !values[rule.required]) return '/pages/home/index'
```

`normalizeInspectionReturnUrl` must preserve the order-center path with a valid optional
stage and continue rejecting unknown paths.

Implement the destination resolver exactly once:

```ts
export function resolveInspectionOrderDestination(
  order: InspectionOrderSummary,
): string | null {
  switch (order.stage) {
    case 'AWAITING_PLAN':
    case 'CANCELLED':
      return order.reservationId
        ? buildInspectionBookingSubmittedUrl(order.reservationId)
        : null
    case 'AWAITING_CONFIRMATION':
      return order.currentPlanId
        ? buildInspectionPlanConfirmationUrl(order.currentPlanId)
        : null
    case 'IN_PROGRESS':
      return order.serviceOrderId
        ? buildInspectionFulfillmentUrl(order.serviceOrderId)
        : null
    case 'AWAITING_ACCEPTANCE':
    case 'COMPLETED':
      return order.serviceOrderId
        ? buildInspectionDeliveryUrl(order.serviceOrderId)
        : null
  }
}
```

Return `null` when a required ID is missing so the page can refresh instead of opening an
incorrect route.

- [ ] **Step 5: Export the real API instance**

```ts
export const inspectionOrderApi = createInspectionOrderApi(http, {
  getAccessToken: () => getBookingAuthContext().getAccessToken(),
})
```

Do not add a second token store or a second `uni.request` wrapper.

- [ ] **Step 6: Run focused tests and type-check**

```powershell
pnpm.cmd test -- order-center.test.ts authentication-flow.test.ts
pnpm.cmd run type-check
```

Expected: PASS; existing protected booking, plan, fulfillment and delivery return URLs remain valid.

- [ ] **Step 7: Review the task diff without staging**

```powershell
git diff --check -- src/features/inspection/domain/service-flow/order-center.ts src/features/inspection/application/service-flow/inspection-order-navigation.ts src/features/inspection/data/service-flow/inspection-order-api.ts src/features/inspection/data/service-flow/uni-booking-http-client.ts src/features/inspection/navigation/inspection-routes.ts src/features/inspection/tests/order-center.test.ts
```

Expected: no whitespace errors and no second authentication implementation. Do not stage or commit.

### Task 5: Order Center Page

**Files:**
- Create: `apps/mobile/src/features/inspection/presentation/orders/order-center-view.ts`
- Create: `apps/mobile/src/features/inspection/presentation/orders/components/InspectionOrderCard.vue`
- Create: `apps/mobile/src/features/inspection/presentation/orders/pages/InspectionOrderCenterPage.vue`
- Create: `apps/mobile/src/pages/mine/inspection-orders.vue`
- Modify: `apps/mobile/src/pages.json`
- Modify: `apps/mobile/src/features/inspection/tests/order-center.test.ts`

**Interfaces:**
- Consumes: Task 4 types, `inspectionOrderApi.list(...)` and `resolveInspectionOrderDestination(...)`.
- Produces: `/pages/mine/inspection-orders?stage=...`.

- [ ] **Step 1: Add failing page-contract tests**

```ts
it('registers a custom-navigation order center with complete list states', () => {
  const pages = JSON.parse(pagesSource) as {
    pages: Array<{ path: string; style?: Record<string, string> }>
  }
  expect(pages.pages.find((page) =>
    page.path === 'pages/mine/inspection-orders',
  )).toMatchObject({ style: { navigationStyle: 'custom' } })

  expect(orderCenterPageSource).toContain('onPullDownRefresh')
  expect(orderCenterPageSource).toContain('onReachBottom')
  expect(orderCenterPageSource).toContain('inspectionOrderApi.list')
  expect(orderCenterPageSource).toContain('resolveInspectionOrderDestination')
  expect(orderCenterPageSource).toContain('订单加载失败')
  expect(orderCenterPageSource).toContain('暂无巡检订单')
  expect(orderCenterPageSource).toContain('没有更多订单')
  expect(orderCenterPageSource).toContain('env(safe-area-inset-bottom)')
  expect(orderCenterPageSource).not.toContain('立即支付')
  expect(orderCenterPageSource).not.toContain('退款')
})
```

- [ ] **Step 2: Run the page test and confirm it fails**

```powershell
pnpm.cmd test -- order-center.test.ts
```

Expected: FAIL because the route and page components are absent.

- [ ] **Step 3: Add centralized display metadata**

```ts
export const INSPECTION_ORDER_FILTER_ITEMS = [
  { code: 'ALL', label: '全部' },
  { code: 'AWAITING_PLAN', label: '待方案' },
  { code: 'AWAITING_CONFIRMATION', label: '待确认' },
  { code: 'IN_PROGRESS', label: '进行中' },
  { code: 'AWAITING_ACCEPTANCE', label: '待验收' },
  { code: 'COMPLETED', label: '已完成' },
] as const

export function getInspectionOrderActionLabel(stage: InspectionOrderStage): string {
  return {
    AWAITING_PLAN: '查看进度',
    AWAITING_CONFIRMATION: '确认方案',
    IN_PROGRESS: '查看进度',
    AWAITING_ACCEPTANCE: '查看报告',
    COMPLETED: '查看报告',
    CANCELLED: '查看订单',
  }[stage]
}

export function getInspectionOrderStageLabel(stage: InspectionOrderStage): string {
  return {
    AWAITING_PLAN: '待方案',
    AWAITING_CONFIRMATION: '待确认',
    IN_PROGRESS: '进行中',
    AWAITING_ACCEPTANCE: '待验收',
    COMPLETED: '已完成',
    CANCELLED: '已取消',
  }[stage]
}

export function formatInspectionOrderAmount(amountMinor: number | null): string {
  if (amountMinor == null) return '待报价'
  return `¥${(amountMinor / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

export function toPlatformMineOrder(
  order: InspectionOrderSummary,
): PlatformMineOrder {
  return {
    id: order.reservationId,
    title: order.serviceTitle,
    status: order.stage,
    imageUrl: order.serviceCoverUrl
      || '/static/images/inspection/inspection-hero-v1.png',
    locationText: order.locationSummary,
    scheduledAtText: order.preferredServiceDate,
    orderNumber: order.reservationNo,
    amount: {
      label: '报价',
      text: formatInspectionOrderAmount(order.quoteAmountMinor),
    },
  }
}
```

- [ ] **Step 4: Implement a pure order card**

`InspectionOrderCard.vue` receives exactly one `InspectionOrderSummary`, emits `select`,
uses the fallback inspection image when `serviceCoverUrl` is null, and renders:

```vue
<button class="inspection-order-card" @click="emit('select', props.order)">
  <image
    class="inspection-order-card__image"
    :src="props.order.serviceCoverUrl || '/static/images/inspection/inspection-hero-v1.png'"
    mode="aspectFill"
  />
  <view class="inspection-order-card__body">
    <view class="inspection-order-card__title-line">
      <text>{{ props.order.serviceTitle }}</text>
      <text>{{ getInspectionOrderStageLabel(props.order.stage) }}</text>
    </view>
    <text>{{ props.order.locationSummary }}</text>
    <text>{{ props.order.preferredServiceDate }}</text>
    <text>{{ props.order.reservationNo }}</text>
  </view>
  <view class="inspection-order-card__aside">
    <text>{{ formatInspectionOrderAmount(props.order.quoteAmountMinor) }}</text>
    <text>{{ getInspectionOrderActionLabel(props.order.stage) }}</text>
  </view>
</button>
```

Use a three-column card that remains readable at 375px:

```css
.inspection-order-card{display:grid;width:100%;grid-template-columns:96px minmax(0,1fr) 82px;gap:12px;align-items:center;padding:14px;border:0;border-radius:18px;background:#fff;box-shadow:0 5px 16px rgba(43,66,94,.06);text-align:left}
.inspection-order-card::after{border:0}
.inspection-order-card__image{width:96px;height:82px;border-radius:13px;background:#eef2f6}
.inspection-order-card__body{display:flex;min-width:0;flex-direction:column;gap:5px}
.inspection-order-card__title-line{display:flex;min-width:0;align-items:center;gap:6px;font-size:15px;font-weight:700}
.inspection-order-card__title-line>text:first-child{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.inspection-order-card__body>text{overflow:hidden;color:#5f6873;font-size:13px;text-overflow:ellipsis;white-space:nowrap}
.inspection-order-card__aside{display:flex;min-width:0;flex-direction:column;align-items:flex-end;gap:10px}
.inspection-order-card__aside>text:first-child{color:#ff6508;font-size:16px;font-weight:800}
.inspection-order-card__aside>text:last-child{padding:6px 10px;border:1px solid #ff6508;border-radius:16px;color:#d64d00;font-size:13px;white-space:nowrap}
@media (max-width:380px){.inspection-order-card{grid-template-columns:84px minmax(0,1fr) 76px;gap:9px;padding:12px}.inspection-order-card__image{width:84px;height:76px}}
```

- [ ] **Step 5: Implement page state, refresh, pagination and safe return**

The page state must use:

```ts
const selectedStage = ref<InspectionOrderFilter>('ALL')
const orders = ref<InspectionOrderSummary[]>([])
const page = ref(1)
const hasMore = ref(false)
const isLoading = ref(true)
const isLoadingMore = ref(false)
const loadError = ref('')
let requestVersion = 0
```

Parse the route stage through the approved filter list:

```ts
function normalizeOrderFilter(value?: string): InspectionOrderFilter {
  return INSPECTION_ORDER_FILTER_ITEMS.some((item) => item.code === value)
    ? value as InspectionOrderFilter
    : 'ALL'
}

onLoad((query: { stage?: string } | undefined) => {
  selectedStage.value = normalizeOrderFilter(query?.stage)
  void reload()
})
```

Every new filter or refresh increments `requestVersion`; only the newest response may write
state:

```ts
async function reload(): Promise<void> {
  const version = ++requestVersion
  isLoading.value = true
  isLoadingMore.value = false
  loadError.value = ''
  try {
    const response = await inspectionOrderApi.list({
      stage: selectedStage.value,
      page: 1,
      size: 20,
    })
    if (version !== requestVersion) return
    orders.value = response.items
    page.value = response.page
    hasMore.value = response.hasMore
  } catch (error) {
    if (version !== requestVersion) return
    loadError.value = error instanceof Error ? error.message : '订单加载失败'
  } finally {
    if (version === requestVersion) isLoading.value = false
  }
}

async function loadMore(): Promise<void> {
  if (isLoading.value || isLoadingMore.value || !hasMore.value) return
  const version = requestVersion
  isLoadingMore.value = true
  try {
    const response = await inspectionOrderApi.list({
      stage: selectedStage.value,
      page: page.value + 1,
      size: 20,
    })
    if (version !== requestVersion) return
    const known = new Set(orders.value.map((order) => order.reservationId))
    orders.value.push(
      ...response.items.filter((order) => !known.has(order.reservationId)),
    )
    page.value = response.page
    hasMore.value = response.hasMore
  } catch (error) {
    if (version === requestVersion) {
      uni.showToast({
        title: error instanceof Error ? error.message : '订单加载失败',
        icon: 'none',
      })
    }
  } finally {
    if (version === requestVersion) isLoadingMore.value = false
  }
}

async function selectStage(stage: InspectionOrderFilter): Promise<void> {
  if (selectedStage.value === stage) return
  selectedStage.value = stage
  await reload()
}

onReachBottom(() => {
  void loadMore()
})

onPullDownRefresh(() => {
  void reload().finally(() => uni.stopPullDownRefresh())
})
```

Pagination appends by `reservationId` without duplicates.

When `resolveInspectionOrderDestination(order)` returns null:

```ts
await reload()
uni.showToast({ title: '订单状态已更新，请重新查看', icon: 'none' })
```

Header back behavior:

```ts
function returnMine(): void {
  uni.navigateBack({
    delta: 1,
    fail: () => uni.switchTab({ url: '/pages/mine/index' }),
  })
}

onBackPress((event) => {
  if (event.from === 'navigateBack') return false
  returnMine()
  return true
})
```

This calls `returnMine()` when the system back action has no normal `navigateBack` source.

- [ ] **Step 6: Assemble the approved order-center hierarchy**

```vue
<template>
  <view class="inspection-orders">
    <view class="inspection-orders__header">
      <button aria-label="返回我的" @click="returnMine">
        <UniIcons type="back" size="24" color="#17191d" />
      </button>
      <text>我的订单</text>
      <view aria-hidden="true" />
    </view>
    <scroll-view class="inspection-orders__filters" scroll-x>
      <button
        v-for="item in INSPECTION_ORDER_FILTER_ITEMS"
        :key="item.code"
        :class="{ 'inspection-orders__filter--active': item.code === selectedStage }"
        @click="selectStage(item.code)"
      >
        {{ item.label }}
      </button>
    </scroll-view>
    <view v-if="isLoading" class="inspection-orders__state">正在加载订单…</view>
    <view v-else-if="loadError" class="inspection-orders__state">
      <text>订单加载失败</text>
      <text>{{ loadError }}</text>
      <button @click="reload">重新加载</button>
    </view>
    <view v-else-if="orders.length === 0" class="inspection-orders__state">
      <text>暂无巡检订单</text>
      <text>提交巡检需求后，可在这里查看服务进度</text>
    </view>
    <view v-else class="inspection-orders__list">
      <InspectionOrderCard
        v-for="order in orders"
        :key="order.reservationId"
        :order="order"
        @select="openOrder"
      />
      <text v-if="isLoadingMore" class="inspection-orders__more">正在加载更多…</text>
      <text v-else-if="!hasMore" class="inspection-orders__more">没有更多订单</text>
    </view>
  </view>
</template>
```

Use these layout limits:

```css
.inspection-orders{min-height:100vh;color:#17191d;background:#f7f8fa}
.inspection-orders__header{display:grid;height:calc(var(--status-bar-height) + 56px);grid-template-columns:48px 1fr 48px;align-items:end;padding:0 12px 12px}
.inspection-orders__header>text{font-size:20px;font-weight:700;text-align:center}
.inspection-orders__filters{width:100%;padding:10px 16px;box-sizing:border-box;white-space:nowrap;background:#fff}
.inspection-orders__filters button{display:inline-flex;min-height:40px;align-items:center;margin-right:10px;padding:0 18px;border-radius:20px;background:#f2f4f7;font-size:15px}
.inspection-orders__filter--active{color:#fff!important;background:#ff6508!important;font-weight:700}
.inspection-orders__list{display:flex;flex-direction:column;gap:12px;padding:12px 16px calc(24px + env(safe-area-inset-bottom))}
.inspection-orders__state{display:flex;min-height:420px;flex-direction:column;align-items:center;justify-content:center;gap:12px;padding:24px;color:#68717d;text-align:center}
.inspection-orders__more{padding:8px 0 12px;color:#7d8590;font-size:14px;text-align:center}
```

- [ ] **Step 7: Add the route shell and page registration**

```vue
<script setup lang="ts">
import InspectionOrderCenterPage from '@/features/inspection/presentation/orders/pages/InspectionOrderCenterPage.vue'
</script>

<template><InspectionOrderCenterPage /></template>
```

Register `pages/mine/inspection-orders` with `navigationStyle: "custom"` and
`enablePullDownRefresh: true`.

- [ ] **Step 8: Run focused tests, type-check and H5 build**

```powershell
pnpm.cmd test -- order-center.test.ts
pnpm.cmd run type-check
pnpm.cmd run build:h5
```

Expected: PASS; the build contains the new route and no payment/refund text.

- [ ] **Step 9: Review the page diff without staging**

```powershell
git diff --check -- src/features/inspection/presentation/orders src/pages/mine/inspection-orders.vue src/pages.json src/features/inspection/tests/order-center.test.ts
```

Expected: no whitespace errors and no changes to existing root tabs or unrelated first-level pages. Do not stage or commit.

### Task 6: Mine Entry and Existing Flow Re-entry

**Files:**
- Modify: `apps/mobile/src/pages/mine/index.vue`
- Modify: `apps/mobile/src/features/inspection/presentation/platform/mine/platform-mine.ts`
- Modify: `apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingSubmittedPage.vue`
- Modify: `apps/mobile/src/features/inspection/presentation/plan/pages/InspectionPlanConfirmationPage.vue`
- Modify: `apps/mobile/src/features/inspection/presentation/fulfillment/pages/InspectionFulfillmentPage.vue`
- Modify: `apps/mobile/src/features/inspection/presentation/delivery/pages/InspectionDeliveryPage.vue`
- Modify: `apps/mobile/src/features/inspection/tests/platform-mine.test.ts`
- Modify: `apps/mobile/src/features/inspection/tests/booking-flow.test.ts`
- Modify: `apps/mobile/src/features/inspection/tests/plan-flow.test.ts`
- Modify: `apps/mobile/src/features/inspection/tests/fulfillment-flow.test.ts`
- Modify: `apps/mobile/src/features/inspection/tests/delivery-flow.test.ts`

**Interfaces:**
- Consumes: Task 4 API/navigation and Task 5 view mapping.
- Produces: “我的 → 状态/全部订单/近期订单 → 对应真实阶段页面”的完整入口。

- [ ] **Step 1: Add failing wiring tests**

```ts
it('loads real recent orders and opens the shared order center', () => {
  expect(mineRouteSource).toContain('inspectionOrderApi.list')
  expect(mineRouteSource).toContain("size: 3")
  expect(mineRouteSource).toContain('buildInspectionOrderCenterUrl')
  expect(mineRouteSource).toContain('resolveInspectionOrderDestination')
  expect(mineRouteSource).not.toContain('@view-all-orders="showUnavailable"')
  expect(mineRouteSource).not.toContain('@select-status="showUnavailable"')
  expect(mineRouteSource).not.toContain('@select-order="showUnavailable"')
})

it('returns submitted reservations to the real order center and refreshes plans', () => {
  expect(submittedPageSource).toContain('buildInspectionOrderCenterUrl')
  expect(submittedPageSource).toContain('onShow')
  expect(submittedPageSource).not.toContain("uni.reLaunch({ url: '/pages/home/index' })")
})
```

Add matching assertions to plan, fulfillment and delivery tests for the order-center fallback.

- [ ] **Step 2: Run the five affected test files and confirm the red state**

```powershell
pnpm.cmd test -- platform-mine.test.ts booking-flow.test.ts plan-flow.test.ts fulfillment-flow.test.ts delivery-flow.test.ts
```

Expected: FAIL on the new real-entry assertions.

- [ ] **Step 3: Load recent orders without forcing login on passive Mine visits**

First extend only the recent-order status type; do not add a cancelled filter tab:

```ts
export type InspectionMineOrderStatus =
  | InspectionMineStatus
  | 'CANCELLED'

export interface PlatformMineOrder {
  id: string
  title: string
  status: InspectionMineOrderStatus
  imageUrl: string
  locationText: string
  scheduledAtText: string
  orderNumber: string
  amount: {
    label: PlatformAmountLabel
    text: string
  }
}

export const getInspectionStatusLabel = (
  status: InspectionMineOrderStatus,
): string => status === 'CANCELLED'
  ? '已取消'
  : INSPECTION_STATUS_ITEMS.find((item) => item.code === status)?.label || status
```

In `pages/mine/index.vue`, move the existing four-item `featureEntries` array unchanged into
a typed `const featureEntries: PlatformMineFeatureEntry[]`, then add:

```ts
const recentOrderById = ref(new Map<string, InspectionOrderSummary>())

function emptyStageCounts(): Record<InspectionMineStatus, number> {
  return {
    AWAITING_PLAN: 0,
    AWAITING_CONFIRMATION: 0,
    IN_PROGRESS: 0,
    AWAITING_ACCEPTANCE: 0,
    COMPLETED: 0,
  }
}

function setMineContent(
  recentOrders: PlatformMineOrder[],
  statusCounts: Record<InspectionMineStatus, number>,
): void {
  state.value = resolvePlatformMineState({
    status: 'ready',
    content: {
      statusCounts,
      recentOrders,
      featureEntries,
    },
  })
}

async function loadRecentOrders(): Promise<void> {
  if (!getBookingAuthContext().getAccessToken()) {
    setMineContent([], emptyStageCounts())
    return
  }
  try {
    const response = await inspectionOrderApi.list({
      stage: 'ALL',
      page: 1,
      size: 3,
    })
    recentOrderById.value = new Map(
      response.items.map((order) => [order.reservationId, order]),
    )
    setMineContent(
      response.items.map(toPlatformMineOrder),
      response.stageCounts,
    )
  } catch (error) {
    state.value = resolvePlatformMineState({
      status: 'error',
      message: error instanceof Error ? error.message : '订单加载失败',
    })
  }
}
```

Call it from `onShow`. Do not redirect an unauthenticated user merely for opening the Mine
tab; clicking an order entry opens the protected order center, whose API invokes existing
login recovery.

Wire:

```ts
function openAllOrders(): void {
  uni.navigateTo({ url: buildInspectionOrderCenterUrl('ALL') })
}

function openStatus(status: InspectionMineStatus): void {
  uni.navigateTo({ url: buildInspectionOrderCenterUrl(status) })
}

function openRecentOrder(order: PlatformMineOrder): void {
  const source = recentOrderById.value.get(order.id)
  const url = source ? resolveInspectionOrderDestination(source) : null
  if (url) uni.navigateTo({ url })
  else void loadRecentOrders()
}
```

- [ ] **Step 4: Refresh and return from the submitted page**

Store the route ID and refresh on each display:

```ts
const reservationId = ref('')

onLoad((query: SubmittedRouteQuery | undefined) => {
  reservationId.value = query?.reservationId?.trim() ?? ''
  if (!reservationId.value) {
    isLoading.value = false
    loadError.value = '未找到预约记录'
  }
})

onShow(() => {
  if (reservationId.value) void loadReservation(reservationId.value)
})

function returnOrders(): void {
  uni.redirectTo({ url: buildInspectionOrderCenterUrl('ALL') })
}
```

Use `returnOrders` for the header back button and the primary button when no
`currentPlanId` exists. Keep the existing real plan navigation when the ID appears.

- [ ] **Step 5: Make later-stage fallback navigation return to orders**

For plan and fulfillment pages, use normal `navigateBack` when the previous page is the
order center; otherwise redirect/relaunch to `buildInspectionOrderCenterUrl('ALL')`.

For delivery:

```ts
const previousRoute = getCurrentPages().at(-2)?.route
if (previousRoute === 'pages/service/inspection/fulfillment'
    || previousRoute === 'pages/mine/inspection-orders') {
  uni.navigateBack({ delta: 1 })
  return
}
uni.reLaunch({ url: buildInspectionOrderCenterUrl('ALL') })
```

Do not change confirmation, fulfillment, download or acceptance business APIs.

- [ ] **Step 6: Run affected tests and full mobile verification**

```powershell
pnpm.cmd test -- platform-mine.test.ts booking-flow.test.ts plan-flow.test.ts fulfillment-flow.test.ts delivery-flow.test.ts order-center.test.ts
pnpm.cmd test
pnpm.cmd run type-check
pnpm.cmd run build:h5
```

Expected: all Vitest files pass, type-check passes, and H5 production build succeeds.

- [ ] **Step 7: Review the complete mobile diff**

```powershell
git diff --check -- src/pages/mine/index.vue src/features/inspection/presentation/booking/pages/InspectionBookingSubmittedPage.vue src/features/inspection/presentation/plan/pages/InspectionPlanConfirmationPage.vue src/features/inspection/presentation/fulfillment/pages/InspectionFulfillmentPage.vue src/features/inspection/presentation/delivery/pages/InspectionDeliveryPage.vue src/features/inspection/tests
git diff --stat
```

Expected: no payment/refund implementation, no unrelated UI rewrite, no fixture order source, and no unapproved deletion. Do not stage or commit.

### Task 7: Full-Stack Regression and User Acceptance

**Files:**
- Modify only if a failing test exposes a direct defect in Tasks 1–6.
- Evidence output: existing test logs and H5/Android screenshots; do not create an APK.

**Interfaces:**
- Consumes: all previous tasks.
- Produces: verified real re-entry path and a user acceptance handoff.

- [ ] **Step 1: Run the complete backend suite**

From `backend`:

```powershell
.\mvnw.cmd -B -pl :bootstrap -am clean test
```

Expected: all identity, inspection and bootstrap tests pass; no module is skipped due a stale target directory.

- [ ] **Step 2: Run backend package verification without tests**

```powershell
.\mvnw.cmd -B -pl :bootstrap -am package "-DskipTests"
```

Expected: `bootstrap/target/bootstrap-*.jar` is produced. This is a backend package, not an APK.

- [ ] **Step 3: Run the complete mobile suite**

From `apps/mobile`:

```powershell
pnpm.cmd test
pnpm.cmd run type-check
pnpm.cmd run build:h5
```

Expected: all tests, TypeScript checking and H5 build pass.

- [ ] **Step 4: Verify the H5 flow at the same mobile viewport**

At `393×852`, verify:

```text
我的
→ 全部订单
→ 待方案订单
→ 订单已提交／等待商家确认需求
→ 返回订单
→ 待确认订单
→ 服务方案确认
```

Also verify loading, empty data, server error, invalid target ID refresh, filter switching,
pull-to-refresh, pagination and bottom safe area. Expected: no blank page, fake price,
truncated action, fixed-navigation overlap or login loop.

- [ ] **Step 5: Verify Android through the existing HBuilderX debug connection**

Use the already connected development device, not cloud packaging. Verify:

```text
Mine tab → 全部订单 → 订单已提交 → 返回订单
Mine tab → 待确认 → 服务方案确认 → 系统返回
```

Expected: status bar is not covered, system back reaches orders/Mine safely, and relaunching
the app restores data from the backend rather than local memory.

- [ ] **Step 6: Audit the final change boundary**

```powershell
git diff --check
git diff --stat
git status --short
```

Expected:

- only approved order-center files plus pre-existing user changes are present;
- no APK, credentials, token, `.env.production`, HBuilderX cache or build artifact is staged;
- no automatic commit has been created.

- [ ] **Step 7: Stop for user acceptance**

Report:

```text
入口：我的 → 全部订单
待方案：打开“订单已提交／等待商家确认需求”
待确认：打开“服务方案确认”
打包：未执行
```

Wait for the user to confirm the interface and behavior before any APK cloud packaging or
formal release operation.
