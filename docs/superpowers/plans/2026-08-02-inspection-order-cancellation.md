# Inspection Order Cancellation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让客户在方案确认完成前安全取消巡检订单，并在前端、后端、权限、审计和订单中心形成一致的“已取消”闭环，不引入支付或退款能力。

**Architecture:** 以后端预约状态和服务单存在性作为最终事实源，通过 `POST /api/v1/inspection/reservations/{reservationId}/cancel`、强 ETag 和幂等键执行原子取消。移动端复用一个纯 TypeScript 取消用例，在“订单已提交”和“服务方案确认”两个页面接入同一确认文案与错误恢复；订单中心在重新显示时刷新列表和阶段数量。

**Tech Stack:** Java 21、Spring Boot 3.5、MyBatis、PostgreSQL、Vue 3、TypeScript 4.9、uni-app、Vitest、JUnit 5、MockMvc。

## Global Constraints

- 仅 `SUBMITTED` 和 `PLAN_PUBLISHED` 可取消；已经创建服务单、进入执行、待验收或完成后不可取消。
- `PLAN_PUBLISHED` 取消时必须在同一事务内将当前方案改为 `WITHDRAWN`，再将预约改为 `CANCELLED`。
- 产品文案只使用“取消订单”，不得出现“退款”“退款成功”或资金原路退回。
- 取消与确认方案并发时只允许一个事务成功，后端不得依赖客户端操作顺序。
- 接口必须校验订单本人、`Idempotency-Key` 和强 `If-Match`；越权资源不得泄露存在性。
- 已取消订单保留历史并可查看，不物理删除；取消后不再占用预约时段容量。
- 不修改支付、退款、结算、预约表单、天气、登录、订单列表卡片布局或其他一级页面。
- 不新增依赖，不批量格式化，不覆盖当前工作树中已有未提交改动。
- 用户尚未授权暂存、提交或推送；每个任务只做精确差异检查，不执行 `git add`、`git commit` 或 `git push`。

---

## File Map

### Backend cancellation policy and persistence

- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java`：取消权限、状态判断、幂等回放和事务入口。
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationCancellationNotAllowedException.java`：不可取消状态异常。
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationVersionConflictException.java`：预约 ETag 冲突异常。
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/PostgresReservationStore.java`：把应用层取消命令落到 Mapper，并写状态轨迹和 outbox。
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapper.java`：幂等记录、方案撤回、预约取消、状态轨迹和 outbox SQL。
- `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java`：应用层状态和幂等测试。
- `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapperSqlTest.java`：SQL 的并发保护条件测试。

### HTTP, security and contract

- `backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionReservationController.java`：取消端点与 ETag 响应。
- `backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/api/GlobalExceptionHandler.java`：`409/412` 错误映射。
- `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationControllerTest.java`：端点、权限、幂等和冲突测试。
- `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationCancellationOpenApiContractTest.java`：OpenAPI 路径、请求头和错误码事实检查。
- `backend/modules/identity/migrations/V003__inspection_reservation_cancel_permission.sql`：客户取消本人预约权限。
- `contracts/openapi-inspection-service-flow.yaml`：取消接口契约。
- `contracts/inspection-service-flow.examples.json`：已取消响应示例。

### Mobile API, shared use case and pages

- `apps/mobile/src/features/inspection/data/service-flow/inspection-booking-api.ts`：类型化 `cancelReservation` 请求。
- `apps/mobile/src/features/inspection/application/service-flow/inspection-order-cancellation.ts`：可取消判断、统一确认流程、冲突分类和错误结果。
- `apps/mobile/src/features/inspection/presentation/shared/inspection-cancellation-ui.ts`：唯一的二次确认弹窗文案适配器。
- `apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingSubmittedPage.vue`：待方案入口和已取消状态。
- `apps/mobile/src/features/inspection/presentation/plan/pages/InspectionPlanConfirmationPage.vue`：待确认入口，成功后回到已取消订单详情。
- `apps/mobile/src/features/inspection/presentation/orders/pages/InspectionOrderCenterPage.vue`：页面重新显示时刷新列表和阶段数量。
- `apps/mobile/src/features/inspection/tests/inspection-order-cancellation.test.ts`：API 和纯用例测试。
- `apps/mobile/src/features/inspection/tests/booking-flow.test.ts`：提交结果页结构契约。
- `apps/mobile/src/features/inspection/tests/plan-flow.test.ts`：方案确认页结构契约。
- `apps/mobile/src/features/inspection/tests/order-center.test.ts`：订单中心重新显示刷新契约。

---

### Task 1: Define the cancellation application contract with failing tests

**Files:**
- Modify: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java`
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java`
- Create: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationCancellationNotAllowedException.java`
- Create: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationVersionConflictException.java`

**Interfaces:**
- Consumes: existing `ReservationDetails`, `ReservationFlowState`, `CurrentSubject` and Spring `@Transactional`.
- Produces: `ReservationApplicationService.cancel(CurrentSubject, UUID, UUID, int, String)`, `CancellationCommand`, `CancellationWriteResult`, `CancellationIdempotencyEntry`, and new `ReservationStore` cancellation methods used by Task 2.

- [ ] **Step 1: Add failing application-service tests**

Add fixed identifiers and tests covering both allowed states, replay, version conflict and state conflict:

```java
private static final UUID CANCEL_KEY =
        UUID.fromString("55555555-5555-4555-8555-555555555555");
private static final UUID PLAN_ID =
        UUID.fromString("66666666-6666-4666-8666-666666666666");

@Test
void customerCancelsSubmittedReservationAndReplayDoesNotWriteTwice() {
    InMemoryReservationStore store = new InMemoryReservationStore();
    ReservationApplicationService service = service(store);
    ReservationView created = service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));

    ReservationDetails first = service.cancel(
            customerSubject(), created.id(), CANCEL_KEY, 0, "cancel-submitted");
    ReservationDetails replay = service.cancel(
            customerSubject(), created.id(), CANCEL_KEY, first.version(), "cancel-replay");

    assertThat(first.status()).isEqualTo("CANCELLED");
    assertThat(first.currentPlanId()).isNull();
    assertThat(replay.status()).isEqualTo("CANCELLED");
    assertThat(store.cancelWriteCount()).isEqualTo(1);
}

@Test
void customerCancelsPublishedPlanAndStoreReceivesCurrentPlan() {
    InMemoryReservationStore store = new InMemoryReservationStore();
    ReservationApplicationService service = service(store);
    ReservationView created = service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));
    store.setFlowState(created.id(), new ReservationFlowState("PLAN_PUBLISHED", PLAN_ID, 1));

    ReservationDetails cancelled = service.cancel(
            customerSubject(), created.id(), CANCEL_KEY, 1, "cancel-published");

    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(store.lastCancellation().currentPlanId()).isEqualTo(PLAN_ID);
}

@Test
void cancellationRejectsStaleVersionAndConfirmedFlow() {
    InMemoryReservationStore store = new InMemoryReservationStore();
    ReservationApplicationService service = service(store);
    ReservationView created = service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));
    store.setFlowState(created.id(), new ReservationFlowState("PLAN_PUBLISHED", PLAN_ID, 2));

    assertThatThrownBy(() -> service.cancel(
            customerSubject(), created.id(), CANCEL_KEY, 1, "stale"))
            .isInstanceOf(ReservationVersionConflictException.class);

    store.setCancellationResult(CancellationWriteResult.STATE_CONFLICT);
    assertThatThrownBy(() -> service.cancel(
            customerSubject(), created.id(), UUID.randomUUID(), 2, "confirmed"))
            .isInstanceOf(ReservationCancellationNotAllowedException.class);
}
```

Add this subject helper:

```java
private static com.lowaltitude.inspection.identity.domain.CurrentSubject customerSubject() {
    return new com.lowaltitude.inspection.identity.domain.CurrentSubject(
            CUSTOMER_ID,
            java.util.Set.of("CUSTOMER"),
            UUID.randomUUID(),
            java.util.Set.of(
                    "inspection.reservation.read.own",
                    "inspection.reservation.cancel.own"),
            0);
}

private static ReservationApplicationService service(InMemoryReservationStore store) {
    return new ReservationApplicationService(
            new InMemoryServiceCatalog(
                    SERVICE_ID,
                    PROVIDER_ID,
                    "服务端巡检标题",
                    "https://cdn.example/cover.jpg"),
            store,
            new PlainTestCipher(),
            BOOKING_CLOCK);
}
```

- [ ] **Step 2: Run the focused test and verify failure**

Run from the repository root:

```powershell
.\mvnw.cmd -B -pl :inspection -Dtest=ReservationApplicationServiceTest test
```

Expected: compilation fails because `cancel`, cancellation records, exceptions and in-memory store methods do not exist.

- [ ] **Step 3: Add the application contract and minimal policy**

Add this public method to `ReservationApplicationService` and keep the existing `read` authorization unchanged:

```java
@Transactional
public ReservationDetails cancel(
        com.lowaltitude.inspection.identity.domain.CurrentSubject subject,
        UUID reservationId,
        UUID idempotencyKey,
        int expectedVersion,
        String requestId
) {
    if (!subject.roles().contains("CUSTOMER")
            || !subject.permissions().contains("inspection.reservation.cancel.own")) {
        throw new org.springframework.security.access.AccessDeniedException(
                "customer reservation cancellation permission is required");
    }
    StoredReservation reservation = store.findById(reservationId)
            .orElseThrow(ReservationNotFoundException::new);
    if (!reservation.customerId().equals(subject.subjectId())) {
        throw new ReservationNotFoundException();
    }
    String requestDigest = cancellationDigest(reservationId);
    Optional<CancellationIdempotencyEntry> prior =
            store.findCancellationIdempotency(subject.subjectId(), idempotencyKey);
    if (prior.isPresent()) {
        if (!prior.get().requestDigest().equals(requestDigest)) {
            throw new IdempotencyConflictException();
        }
        return read(subject, prior.get().reservationId());
    }
    ReservationFlowState state = store.findFlowState(reservationId)
            .orElse(new ReservationFlowState("SUBMITTED", null, reservation.version()));
    if ("CANCELLED".equals(state.status())) {
        return read(subject, reservationId);
    }
    if (state.version() != expectedVersion) {
        throw new ReservationVersionConflictException();
    }
    if (!"SUBMITTED".equals(state.status())
            && !"PLAN_PUBLISHED".equals(state.status())) {
        throw new ReservationCancellationNotAllowedException();
    }
    CancellationWriteResult result = store.cancel(new CancellationCommand(
            reservationId,
            reservation.customerId(),
            state.status(),
            state.currentPlanId(),
            expectedVersion,
            new CancellationIdempotencyEntry(
                    subject.subjectId(), idempotencyKey, requestDigest, reservationId),
            subject.subjectId(),
            requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim(),
            clock.instant()));
    if (result == CancellationWriteResult.VERSION_CONFLICT) {
        throw new ReservationVersionConflictException();
    }
    if (result == CancellationWriteResult.STATE_CONFLICT) {
        throw new ReservationCancellationNotAllowedException();
    }
    if (result == CancellationWriteResult.IDEMPOTENCY_EXISTS) {
        CancellationIdempotencyEntry replay =
                store.findCancellationIdempotency(subject.subjectId(), idempotencyKey)
                        .orElseThrow(IdempotencyConflictException::new);
        if (!replay.requestDigest().equals(requestDigest)) {
            throw new IdempotencyConflictException();
        }
    }
    return read(subject, reservationId);
}

private static String cancellationDigest(UUID reservationId) {
    try {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(
                digest.digest(reservationId.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
        throw new IllegalStateException(exception);
    }
}
```

Extend the package-private store contract at the bottom of the same file:

```java
record CancellationIdempotencyEntry(
        UUID actorId,
        UUID key,
        String requestDigest,
        UUID reservationId
) { }

record CancellationCommand(
        UUID reservationId,
        UUID customerId,
        String expectedStatus,
        UUID currentPlanId,
        int expectedVersion,
        CancellationIdempotencyEntry idempotency,
        UUID actorId,
        String requestId,
        Instant cancelledAt
) { }

enum CancellationWriteResult {
    SAVED,
    IDEMPOTENCY_EXISTS,
    VERSION_CONFLICT,
    STATE_CONFLICT
}

interface ReservationStore {
    Optional<IdempotencyEntry> findIdempotency(UUID actorId, UUID key);
    void save(StoredReservation reservation, IdempotencyEntry entry);
    Optional<StoredReservation> findById(UUID id);
    void lockActiveService(UUID serviceId, String cityCode);
    List<BookedTimeSlotCount> findBookedTimeSlotCounts(
            UUID serviceId, String cityCode, LocalDate startDate, LocalDate endDate);
    default Optional<ReservationFlowState> findFlowState(UUID reservationId) {
        return Optional.empty();
    }
    default List<ReservationFileView> findAttachments(UUID reservationId) {
        return List.of();
    }
    default Optional<CancellationIdempotencyEntry> findCancellationIdempotency(
            UUID actorId, UUID key) {
        return Optional.empty();
    }
    default CancellationWriteResult cancel(CancellationCommand command) {
        throw new UnsupportedOperationException("reservation cancellation is not implemented");
    }
}
```

Create the two exceptions:

```java
package com.lowaltitude.inspection.application.reservation;

public final class ReservationCancellationNotAllowedException extends RuntimeException {
    public ReservationCancellationNotAllowedException() {
        super("reservation cannot be cancelled in its current state");
    }
}
```

```java
package com.lowaltitude.inspection.application.reservation;

public final class ReservationVersionConflictException extends RuntimeException {
    public ReservationVersionConflictException() {
        super("reservation version is stale");
    }
}
```

Add these fields and overrides to the test-only `InMemoryReservationStore`:

```java
private final Map<UUID, ReservationFlowState> flowStates = new java.util.HashMap<>();
private final Map<String, CancellationIdempotencyEntry> cancellationKeys =
        new java.util.HashMap<>();
private CancellationWriteResult cancellationResult = CancellationWriteResult.SAVED;
private CancellationCommand lastCancellation;
private int cancelWriteCount;

@Override
public Optional<ReservationFlowState> findFlowState(UUID reservationId) {
    return Optional.ofNullable(flowStates.get(reservationId));
}

@Override
public Optional<CancellationIdempotencyEntry> findCancellationIdempotency(
        UUID actorId,
        UUID key
) {
    return Optional.ofNullable(cancellationKeys.get(actorId + ":" + key));
}

@Override
public CancellationWriteResult cancel(CancellationCommand command) {
    if (cancellationResult != CancellationWriteResult.SAVED) {
        return cancellationResult;
    }
    cancellationKeys.put(
            command.idempotency().actorId() + ":" + command.idempotency().key(),
            command.idempotency());
    flowStates.put(
            command.reservationId(),
            new ReservationFlowState("CANCELLED", null, command.expectedVersion() + 1));
    lastCancellation = command;
    cancelWriteCount++;
    return CancellationWriteResult.SAVED;
}

void setFlowState(UUID reservationId, ReservationFlowState state) {
    flowStates.put(reservationId, state);
}

void setCancellationResult(CancellationWriteResult result) {
    cancellationResult = result;
}

CancellationCommand lastCancellation() { return lastCancellation; }
int cancelWriteCount() { return cancelWriteCount; }
```

In `findBookedTimeSlotCounts`, add this filter before grouping so the in-memory test double matches PostgreSQL capacity behavior:

```java
.filter(value -> !"CANCELLED".equals(
        flowStates.getOrDefault(
                value.id(),
                new ReservationFlowState("SUBMITTED", null, value.version()))
                .status()))
```

- [ ] **Step 4: Run the application tests**

```powershell
.\mvnw.cmd -B -pl :inspection -Dtest=ReservationApplicationServiceTest test
```

Expected: all `ReservationApplicationServiceTest` tests pass, including the new cancellation cases.

- [ ] **Step 5: Review the task diff without staging**

```powershell
git diff --check -- backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java
git diff --stat -- backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java
```

Expected: no whitespace errors; changes are limited to cancellation policy, two exceptions and focused tests. Do not stage or commit.

---

### Task 2: Implement atomic PostgreSQL cancellation and audit evidence

**Files:**
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/PostgresReservationStore.java`
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapper.java`
- Modify: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapperSqlTest.java`

**Interfaces:**
- Consumes: `CancellationCommand`, `CancellationIdempotencyEntry`, `CancellationWriteResult` from Task 1.
- Produces: transactional store behavior that withdraws `PUBLISHED` plan before cancelling the reservation and writes one transition/outbox event.

- [ ] **Step 1: Add failing SQL contract tests**

Extend `ReservationPersistenceMapperSqlTest` to inspect the new annotation SQL and assert all race guards:

```java
@Test
void cancellationSqlWithdrawsPublishedPlanAndRejectsExistingServiceOrder() throws Exception {
    String withdraw = updateSql("withdrawPublishedPlanForCancellation");
    String cancel = updateSql("cancelReservation");

    assertThat(withdraw)
            .contains("status = 'WITHDRAWN'")
            .contains("status = 'PUBLISHED'")
            .contains("id = #{planId}")
            .contains("version = version + 1");
    assertThat(cancel)
            .contains("status = 'CANCELLED'")
            .contains("status IN ('SUBMITTED', 'PLAN_PUBLISHED')")
            .contains("version = #{expectedVersion}")
            .contains("NOT EXISTS")
            .contains("inspection_service_order")
            .contains("status = 'CONFIRMED'");
}
```

Use the existing reflection helper style in this test file; the helper must return the `@Update` value joined with spaces.

Add the import and helper exactly as follows:

```java
import java.util.Arrays;
import org.apache.ibatis.annotations.Update;

private static String updateSql(String methodName) {
    Method method = Arrays.stream(ReservationPersistenceMapper.class.getMethods())
            .filter(candidate -> candidate.getName().equals(methodName))
            .findFirst()
            .orElseThrow();
    return String.join(" ", method.getAnnotation(Update.class).value());
}
```

- [ ] **Step 2: Run the SQL test and verify failure**

```powershell
.\mvnw.cmd -B -pl :inspection -Dtest=ReservationPersistenceMapperSqlTest test
```

Expected: FAIL because the two Mapper methods do not exist.

- [ ] **Step 3: Add exact Mapper operations**

Add generic cancellation idempotency lookup/insert, plan withdrawal, guarded reservation update, state transition and outbox methods to `ReservationPersistenceMapper`:

```java
@Select("""
        SELECT request_hash request_digest, resource_id reservation_id
        FROM inspection_idempotency_record
        WHERE actor_id = #{actorId}
          AND operation_code = 'inspection.reservation.cancel'
          AND idempotency_key = #{key}
        """)
Optional<IdempotencyRow> findCancellationIdempotency(
        @Param("actorId") UUID actorId,
        @Param("key") UUID key);

@Insert("""
        INSERT INTO inspection_idempotency_record (
            id, actor_id, operation_code, idempotency_key, request_hash,
            resource_type, resource_id, response_status, response_body, expires_at
        ) VALUES (
            gen_random_uuid(), #{actorId}, 'inspection.reservation.cancel', #{key}, #{digest},
            'RESERVATION', #{reservationId}, 200, '{}'::jsonb, now() + interval '24 hours'
        )
        ON CONFLICT (actor_id, operation_code, idempotency_key) DO NOTHING
        """)
int insertCancellationIdempotency(
        @Param("actorId") UUID actorId,
        @Param("key") UUID key,
        @Param("digest") String digest,
        @Param("reservationId") UUID reservationId);

@Update("""
        UPDATE inspection_service_plan
        SET status = 'WITHDRAWN', version = version + 1,
            updated_by = #{actorId}, updated_at = #{cancelledAt}
        WHERE id = #{planId}
          AND reservation_id = #{reservationId}
          AND status = 'PUBLISHED'
        """)
int withdrawPublishedPlanForCancellation(
        @Param("reservationId") UUID reservationId,
        @Param("planId") UUID planId,
        @Param("actorId") UUID actorId,
        @Param("cancelledAt") Instant cancelledAt);

@Update("""
        UPDATE inspection_reservation r
        SET status = 'CANCELLED', version = version + 1,
            updated_by = #{actorId}, updated_at = #{cancelledAt}
        WHERE r.id = #{reservationId}
          AND r.customer_id = #{customerId}
          AND r.version = #{expectedVersion}
          AND r.status IN ('SUBMITTED', 'PLAN_PUBLISHED')
          AND NOT EXISTS (
              SELECT 1 FROM inspection_service_plan p
              WHERE p.reservation_id = r.id AND p.status = 'CONFIRMED'
          )
          AND NOT EXISTS (
              SELECT 1 FROM inspection_service_order o
              WHERE o.reservation_id = r.id
          )
        """)
int cancelReservation(
        @Param("reservationId") UUID reservationId,
        @Param("customerId") UUID customerId,
        @Param("expectedVersion") int expectedVersion,
        @Param("actorId") UUID actorId,
        @Param("cancelledAt") Instant cancelledAt);
```

Copy the existing `insertTransition` and `insertOutbox` SQL shape from `ServiceFlowPersistenceMapper` into this Mapper, keeping payload limited to `aggregateId`.

- [ ] **Step 4: Implement the PostgreSQL store transaction steps**

Add these overrides to `PostgresReservationStore`:

```java
@Override
public Optional<CancellationIdempotencyEntry> findCancellationIdempotency(
        UUID actorId,
        UUID key
) {
    return mapper.findCancellationIdempotency(actorId, key)
            .map(row -> new CancellationIdempotencyEntry(
                    actorId, key, row.requestDigest(), row.reservationId()));
}

@Override
public CancellationWriteResult cancel(CancellationCommand command) {
    CancellationIdempotencyEntry entry = command.idempotency();
    if (mapper.insertCancellationIdempotency(
            entry.actorId(), entry.key(), entry.requestDigest(), entry.reservationId()) == 0) {
        return CancellationWriteResult.IDEMPOTENCY_EXISTS;
    }
    if ("PLAN_PUBLISHED".equals(command.expectedStatus())) {
        if (command.currentPlanId() == null
                || mapper.withdrawPublishedPlanForCancellation(
                        command.reservationId(),
                        command.currentPlanId(),
                        command.actorId(),
                        command.cancelledAt()) != 1) {
            return CancellationWriteResult.STATE_CONFLICT;
        }
        mapper.insertTransition(
                "PLAN", command.currentPlanId(), "PUBLISHED", "WITHDRAWN",
                "CUSTOMER", command.actorId(), command.requestId(), command.cancelledAt());
    }
    if (mapper.cancelReservation(
            command.reservationId(),
            command.customerId(),
            command.expectedVersion(),
            command.actorId(),
            command.cancelledAt()) != 1) {
        return mapper.findReservationFlowState(command.reservationId())
                .filter(row -> row.version() != command.expectedVersion())
                .map(row -> CancellationWriteResult.VERSION_CONFLICT)
                .orElse(CancellationWriteResult.STATE_CONFLICT);
    }
    mapper.insertTransition(
            "RESERVATION", command.reservationId(), command.expectedStatus(), "CANCELLED",
            "CUSTOMER", command.actorId(), command.requestId(), command.cancelledAt());
    mapper.insertOutbox(
            "RESERVATION", command.reservationId(),
            "INSPECTION_RESERVATION_CANCELLED", command.cancelledAt());
    return CancellationWriteResult.SAVED;
}
```

The application service throws on `VERSION_CONFLICT` and `STATE_CONFLICT`; because it owns the transaction, an earlier plan withdrawal or idempotency insert rolls back with the exception.

- [ ] **Step 5: Run focused persistence and application tests**

```powershell
.\mvnw.cmd -B -pl :inspection -Dtest=ReservationPersistenceMapperSqlTest,ReservationApplicationServiceTest test
```

Expected: both test classes pass.

- [ ] **Step 6: Review the persistence diff without staging**

```powershell
git diff --check -- backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/PostgresReservationStore.java backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapper.java backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapperSqlTest.java
```

Expected: no unrelated query, table or schema changes. Do not stage or commit.

---

### Task 3: Expose the secured HTTP endpoint and contract

**Files:**
- Modify: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionReservationController.java`
- Modify: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/api/GlobalExceptionHandler.java`
- Modify: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationControllerTest.java`
- Create: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationCancellationOpenApiContractTest.java`
- Create: `backend/modules/identity/migrations/V003__inspection_reservation_cancel_permission.sql`
- Modify: `contracts/openapi-inspection-service-flow.yaml`
- Modify: `contracts/inspection-service-flow.examples.json`

**Interfaces:**
- Consumes: `ReservationApplicationService.cancel(...)` from Task 1 and persistence behavior from Task 2.
- Produces: authenticated `POST /api/v1/inspection/reservations/{id}/cancel`, error codes `INS_RESERVATION_NOT_CANCELLABLE` and `INS_VERSION_CONFLICT`, and customer permission `inspection.reservation.cancel.own`.

- [ ] **Step 1: Add failing MockMvc tests**

Add these endpoint expectations to `InspectionReservationControllerTest` and include `inspection.reservation.cancel.own` in `customerJwt()`:

```java
@Test
void customerCancelsOwnReservationWithIdempotencyAndStrongEtag() throws Exception {
    String id = createReservation(customerJwt(), requestJson("东营市取消测试路1号"));

    mvc.perform(post("/api/v1/inspection/reservations/{id}/cancel", id)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .header("If-Match", "\"0\"")
                    .header("X-Request-Id", REQUEST_ID)
                    .principal(customerJwt()))
            .andExpect(status().isOk())
            .andExpect(header().string("ETag", "\"1\""))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.currentPlanId").doesNotExist());
}

@Test
void cancellationHidesOtherCustomersReservationAndRejectsStaleVersion() throws Exception {
    String id = createReservation(customerJwt(), requestJson("东营市取消测试路2号"));

    mvc.perform(post("/api/v1/inspection/reservations/{id}/cancel", id)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .header("If-Match", "\"0\"")
                    .principal(subject(
                            OTHER_CUSTOMER,
                            Set.of("CUSTOMER"),
                            UUID.randomUUID(),
                            Set.of("inspection.reservation.cancel.own"))))
            .andExpect(status().isNotFound());

    mvc.perform(post("/api/v1/inspection/reservations/{id}/cancel", id)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .header("If-Match", "\"9\"")
                    .principal(customerJwt()))
            .andExpect(status().isPreconditionFailed())
            .andExpect(jsonPath("$.code").value("INS_VERSION_CONFLICT"));
}
```

The controller test declares its own `InMemoryReservationStore`. Add the Task 1 flow-state/cancellation fields and overrides to that class as well, and keep its existing create/read behavior. The exact cancellation implementation is:

```java
private final Map<UUID, ReservationFlowState> flowStates = new HashMap<>();
private final Map<String, CancellationIdempotencyEntry> cancellationKeys = new HashMap<>();

@Override
public Optional<ReservationFlowState> findFlowState(UUID reservationId) {
    return Optional.ofNullable(flowStates.get(reservationId));
}

@Override
public Optional<CancellationIdempotencyEntry> findCancellationIdempotency(
        UUID actorId,
        UUID key
) {
    return Optional.ofNullable(cancellationKeys.get(actorId + ":" + key));
}

@Override
public CancellationWriteResult cancel(CancellationCommand command) {
    cancellationKeys.put(
            command.idempotency().actorId() + ":" + command.idempotency().key(),
            command.idempotency());
    flowStates.put(
            command.reservationId(),
            new ReservationFlowState("CANCELLED", null, command.expectedVersion() + 1));
    return CancellationWriteResult.SAVED;
}
```

Update the JWT permission list to:

```java
.claim("permissions", List.of(
        "inspection.reservation.create",
        "inspection.reservation.read.own",
        "inspection.reservation.cancel.own"))
```

- [ ] **Step 2: Run the controller test and verify failure**

```powershell
.\mvnw.cmd -B -pl :bootstrap -am -Dtest=InspectionReservationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: endpoint returns 404/405 or compilation fails because controller and exception mappings are absent.

- [ ] **Step 3: Add the controller endpoint and strong ETag parser**

Add to `InspectionReservationController`:

```java
@PostMapping("/{id}/cancel")
public ResponseEntity<ReservationResponse> cancel(
        @PathVariable UUID id,
        @RequestHeader("Idempotency-Key") UUID idempotencyKey,
        @RequestHeader("If-Match") String ifMatch,
        @RequestHeader(name = "X-Request-Id", required = false) String requestId,
        Authentication authentication
) {
    ReservationDetails cancelled = service.cancel(
            currentSubject(authentication),
            id,
            idempotencyKey,
            parseIfMatch(ifMatch),
            requestId == null || requestId.isBlank()
                    ? UUID.randomUUID().toString()
                    : requestId.trim());
    return ResponseEntity.ok()
            .eTag(Integer.toString(cancelled.version()))
            .body(ReservationResponse.from(cancelled));
}

private static int parseIfMatch(String value) {
    if (value == null || value.isBlank() || value.startsWith("W/")) {
        throw new IllegalArgumentException("If-Match must be a strong numeric ETag");
    }
    String normalized = value.trim();
    if (normalized.startsWith("\"") && normalized.endsWith("\"")
            && normalized.length() > 2) {
        normalized = normalized.substring(1, normalized.length() - 1);
    }
    if (!normalized.matches("0|[1-9][0-9]*")) {
        throw new IllegalArgumentException("If-Match must be a strong numeric ETag");
    }
    return Integer.parseInt(normalized);
}
```

Add explicit handlers to `GlobalExceptionHandler`:

```java
@ExceptionHandler(ReservationCancellationNotAllowedException.class)
public ResponseEntity<ApiError> handleReservationCancellationNotAllowed(
        ReservationCancellationNotAllowedException exception,
        HttpServletRequest request
) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError(
                    "INS_RESERVATION_NOT_CANCELLABLE",
                    "当前订单状态不允许取消",
                    traceId(request)));
}

@ExceptionHandler(ReservationVersionConflictException.class)
public ResponseEntity<ApiError> handleReservationVersionConflict(
        ReservationVersionConflictException exception,
        HttpServletRequest request
) {
    return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
            .body(new ApiError(
                    "INS_VERSION_CONFLICT",
                    "页面数据已更新，请刷新后重试",
                    traceId(request)));
}
```

- [ ] **Step 4: Add the permission migration**

Create `V003__inspection_reservation_cancel_permission.sql` with idempotent inserts:

```sql
INSERT INTO identity_permission (permission_code, permission_name)
VALUES ('inspection.reservation.cancel.own', '取消本人巡检预约')
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO identity_role_permission (role_id, permission_id)
SELECT r.id, p.id
FROM identity_role r
JOIN identity_permission p
  ON p.permission_code = 'inspection.reservation.cancel.own'
WHERE r.role_code = 'CUSTOMER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
```

- [ ] **Step 5: Extend OpenAPI and examples with a contract test first**

Create `InspectionReservationCancellationOpenApiContractTest`:

```java
package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class InspectionReservationCancellationOpenApiContractTest {
    @Test
    void contractDeclaresCancellationHeadersPermissionAndConflicts() throws Exception {
        String contract = Files.readString(Path.of(
                "..", "..", "contracts", "openapi-inspection-service-flow.yaml"));

        assertThat(contract)
                .contains("/api/v1/inspection/reservations/{reservationId}/cancel:")
                .contains("operationId: cancelInspectionReservation")
                .contains("x-required-permission: inspection.reservation.cancel.own")
                .contains("#/components/parameters/IdempotencyKey")
                .contains("#/components/parameters/IfMatch")
                .contains("INS_RESERVATION_NOT_CANCELLABLE");
    }
}
```

Then add this path to `openapi-inspection-service-flow.yaml`:

```yaml
  /api/v1/inspection/reservations/{reservationId}/cancel:
    post:
      tags: [CustomerInspectionFlow]
      operationId: cancelInspectionReservation
      summary: 取消方案确认前的本人巡检订单
      x-required-permission: inspection.reservation.cancel.own
      parameters:
        - $ref: "#/components/parameters/ReservationId"
        - $ref: "#/components/parameters/IdempotencyKey"
        - $ref: "#/components/parameters/IfMatch"
      responses:
        "200":
          description: 已取消或幂等返回同一已取消预约
          headers:
            ETag:
              $ref: "#/components/headers/ETag"
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Reservation"
        "401":
          $ref: "#/components/responses/Unauthorized"
        "404":
          $ref: "#/components/responses/NotFound"
        "409":
          description: 当前订单状态不允许取消
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ApiError"
              example:
                code: INS_RESERVATION_NOT_CANCELLABLE
                message: 当前订单状态不允许取消
                traceId: cancel-conflict-example
        "412":
          $ref: "#/components/responses/PreconditionFailed"
```

Add this concrete top-level fixture to `inspection-service-flow.examples.json`; do not add payment fields:

```json
"cancelledReservation": {
  "id": "10000000-0000-4000-8000-000000000001",
  "reservationNo": "YY202607270001",
  "customerId": "20000000-0000-4000-8000-000000000001",
  "providerId": "30000000-0000-4000-8000-000000000001",
  "serviceId": "40000000-0000-4000-8000-000000000001",
  "serviceTitle": "输电线路无人机巡检",
  "serviceCoverUrl": "https://cdn.example.com/inspection/power-line.webp",
  "cityCode": "370500",
  "status": "CANCELLED",
  "location": {
    "address": "测试地址，仅用于自动化验收",
    "longitude": 118.5,
    "latitude": 37.4
  },
  "lineLengthMeters": 18600,
  "preferredServiceDate": "2026-08-02",
  "preferredTimeSlotCode": "MORNING",
  "inspectionItems": [
    { "code": "TOWER", "label": "杆塔巡检" }
  ],
  "note": null,
  "contact": { "name": "测试客户", "mobile": "13800138000" },
  "attachments": [],
  "submittedAt": "2026-07-27T08:00:00Z",
  "currentPlanId": null,
  "version": 2
}
```

- [ ] **Step 6: Run controller and contract tests**

```powershell
.\mvnw.cmd -B -pl :bootstrap -am -Dtest=InspectionReservationControllerTest,InspectionReservationCancellationOpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: all selected tests pass; cancellation returns `200`, stale ETag returns `412`, and another customer sees `404`.

- [ ] **Step 7: Review the HTTP and contract diff without staging**

```powershell
git diff --check -- backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionReservationController.java backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/api/GlobalExceptionHandler.java backend/modules/identity/migrations/V003__inspection_reservation_cancel_permission.sql contracts/openapi-inspection-service-flow.yaml contracts/inspection-service-flow.examples.json
```

Expected: no payment/refund fields, no unrelated permissions, no formatting sweep. Do not stage or commit.

---

### Task 4: Add the typed mobile API and shared cancellation use case

**Files:**
- Modify: `apps/mobile/src/features/inspection/data/service-flow/inspection-booking-api.ts`
- Create: `apps/mobile/src/features/inspection/application/service-flow/inspection-order-cancellation.ts`
- Create: `apps/mobile/src/features/inspection/presentation/shared/inspection-cancellation-ui.ts`
- Create: `apps/mobile/src/features/inspection/tests/inspection-order-cancellation.test.ts`

**Interfaces:**
- Consumes: existing `BookingReservation`, `InspectionBookingApi`, `BookingHttpClient`, `ApiRequestError` shape and `createBookingUuid()`.
- Produces: `InspectionBookingApi.cancelReservation(...)`, `canCancelInspectionOrder(...)`, `requestInspectionOrderCancellation(...)`, and `confirmInspectionOrderCancellation()` for both pages.

- [ ] **Step 1: Write failing API and use-case tests**

Create `inspection-order-cancellation.test.ts`:

```ts
import { describe, expect, it, vi } from 'vitest'
import { createInspectionBookingApi, type BookingHttpClient, type BookingReservation } from '../data/service-flow/inspection-booking-api'
import { canCancelInspectionOrder, requestInspectionOrderCancellation } from '../application/service-flow/inspection-order-cancellation'

const reservation = (status: BookingReservation['status']): BookingReservation => ({
  id: '40000000-0000-4000-8000-000000000001',
  reservationNo: 'IR202608020001',
  customerId: '10000000-0000-4000-8000-000000000001',
  providerId: '30000000-0000-4000-8000-000000000001',
  serviceId: '33333333-3333-3333-3333-333333333333',
  serviceTitle: '电力输电线路无人机巡检',
  serviceCoverUrl: null,
  cityCode: '370500',
  status,
  location: { address: '东营市测试路', longitude: 118.5, latitude: 37.4 },
  lineLengthMeters: 1200,
  preferredServiceDate: '2026-08-03',
  preferredTimeSlotCode: 'MORNING',
  inspectionItems: [{ code: 'PIPELINE', label: '线路本体巡检' }],
  note: null,
  contact: { name: '张三', mobile: '13800138000' },
  attachments: [],
  submittedAt: '2026-08-02T02:00:00Z',
  currentPlanId: status === 'PLAN_PUBLISHED' ? '50000000-0000-4000-8000-000000000001' : null,
  version: status === 'PLAN_PUBLISHED' ? 1 : 0,
})

it('posts cancellation with auth, idempotency and the reservation ETag', async () => {
  const calls: unknown[] = []
  const http: BookingHttpClient = {
    request: async <T,>(input: {
      url: string
      method: 'GET' | 'POST'
      data?: unknown
      headers?: Record<string, string>
    }) => {
      calls.push(input)
      return { ...reservation('CANCELLED'), version: 1 } as T
    },
    putFile: async () => ({ etag: 'unused' }),
  }
  const api = createInspectionBookingApi(http, { getAccessToken: () => 'customer-token' })

  await api.cancelReservation(reservation('SUBMITTED').id, 0, '70000000-0000-4000-8000-000000000001')

  expect(calls).toEqual([{
    url: '/api/v1/inspection/reservations/40000000-0000-4000-8000-000000000001/cancel',
    method: 'POST',
    headers: {
      Authorization: 'Bearer customer-token',
      'Idempotency-Key': '70000000-0000-4000-8000-000000000001',
      'If-Match': '"0"',
    },
  }])
})

it('allows only submitted or plan-published orders and respects dismissal', async () => {
  expect(canCancelInspectionOrder('SUBMITTED')).toBe(true)
  expect(canCancelInspectionOrder('PLAN_PUBLISHED')).toBe(true)
  expect(canCancelInspectionOrder('CANCELLED')).toBe(false)
  const cancelReservation = vi.fn()

  const result = await requestInspectionOrderCancellation({
    reservation: reservation('SUBMITTED'),
    cancelReservation,
    confirm: async () => false,
    idempotencyKey: () => 'unused',
  })

  expect(result).toEqual({ kind: 'dismissed' })
  expect(cancelReservation).not.toHaveBeenCalled()
})
```

- [ ] **Step 2: Run the new test and verify failure**

```powershell
Set-Location apps/mobile
pnpm.cmd test -- inspection-order-cancellation.test.ts
```

Expected: FAIL because the API method and application module do not exist.

- [ ] **Step 3: Add the typed API method**

Extend `InspectionBookingApi` and its implementation:

```ts
cancelReservation(
  reservationId: string,
  version: number,
  idempotencyKey: string,
): Promise<BookingReservation>
```

```ts
cancelReservation(reservationId, version, idempotencyKey) {
  return http.request<BookingReservation>({
    url: `/api/v1/inspection/reservations/${encodeURIComponent(reservationId)}/cancel`,
    method: 'POST',
    headers: {
      ...headers(idempotencyKey),
      'If-Match': `"${version}"`,
    },
  })
},
```

- [ ] **Step 4: Add the pure cancellation result model**

Create `inspection-order-cancellation.ts`:

```ts
import type { BookingReservation } from '../../data/service-flow/inspection-booking-api'

export type InspectionCancellationResult =
  | { kind: 'dismissed' }
  | { kind: 'cancelled'; reservation: BookingReservation }
  | { kind: 'refresh-required' }
  | { kind: 'failed'; message: string }

interface CancellationInput {
  reservation: BookingReservation
  cancelReservation(
    reservationId: string,
    version: number,
    idempotencyKey: string,
  ): Promise<BookingReservation>
  confirm(): Promise<boolean>
  idempotencyKey(): string
}

export function canCancelInspectionOrder(
  status: BookingReservation['status'],
): boolean {
  return status === 'SUBMITTED' || status === 'PLAN_PUBLISHED'
}

export async function requestInspectionOrderCancellation(
  input: CancellationInput,
): Promise<InspectionCancellationResult> {
  if (!canCancelInspectionOrder(input.reservation.status)) {
    return { kind: 'refresh-required' }
  }
  if (!await input.confirm()) return { kind: 'dismissed' }
  try {
    const cancelled = await input.cancelReservation(
      input.reservation.id,
      input.reservation.version,
      input.idempotencyKey(),
    )
    return { kind: 'cancelled', reservation: cancelled }
  } catch (error) {
    const code = typeof error === 'object' && error !== null && 'code' in error
      ? String(error.code)
      : ''
    if (code === 'INS_VERSION_CONFLICT'
        || code === 'INS_RESERVATION_NOT_CANCELLABLE') {
      return { kind: 'refresh-required' }
    }
    return { kind: 'failed', message: '取消失败，请检查网络后重试' }
  }
}
```

Create `inspection-cancellation-ui.ts` with the only modal copy:

```ts
export function confirmInspectionOrderCancellation(): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title: '确认取消订单？',
      content: '取消后服务将停止安排，本订单未付款，不涉及退款。',
      cancelText: '暂不取消',
      confirmText: '确认取消',
      success: result => resolve(result.confirm),
      fail: () => resolve(false),
    })
  })
}
```

- [ ] **Step 5: Add conflict and success assertions, then run the test**

Add these result assertions below the dismissal test:

```ts
it('returns cancelled, refresh-required and network failure as distinct outcomes', async () => {
  const submitted = reservation('SUBMITTED')
  const cancelled = { ...submitted, status: 'CANCELLED' as const, version: 1 }
  const base = {
    reservation: submitted,
    confirm: async () => true,
    idempotencyKey: () => '70000000-0000-4000-8000-000000000001',
  }

  await expect(requestInspectionOrderCancellation({
    ...base,
    cancelReservation: async () => cancelled,
  })).resolves.toEqual({ kind: 'cancelled', reservation: cancelled })

  await expect(requestInspectionOrderCancellation({
    ...base,
    cancelReservation: async () => {
      throw { code: 'INS_VERSION_CONFLICT' }
    },
  })).resolves.toEqual({ kind: 'refresh-required' })

  await expect(requestInspectionOrderCancellation({
    ...base,
    cancelReservation: async () => {
      throw new Error('network down')
    },
  })).resolves.toEqual({
    kind: 'failed',
    message: '取消失败，请检查网络后重试',
  })
})
```

```powershell
pnpm.cmd test -- inspection-order-cancellation.test.ts
```

Expected: all cancellation API/use-case tests pass.

- [ ] **Step 6: Run TypeScript checking and review the task diff**

```powershell
pnpm.cmd run type-check
Set-Location ..\..
git diff --check -- apps/mobile/src/features/inspection/data/service-flow/inspection-booking-api.ts apps/mobile/src/features/inspection/application/service-flow/inspection-order-cancellation.ts apps/mobile/src/features/inspection/presentation/shared/inspection-cancellation-ui.ts apps/mobile/src/features/inspection/tests/inspection-order-cancellation.test.ts
```

Expected: type check passes; no page UI changes yet. Do not stage or commit.

---

### Task 5: Add cancellation and cancelled state to the submitted-order page

**Files:**
- Modify: `apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingSubmittedPage.vue`
- Modify: `apps/mobile/src/features/inspection/tests/booking-flow.test.ts`

**Interfaces:**
- Consumes: `requestInspectionOrderCancellation`, `canCancelInspectionOrder`, `confirmInspectionOrderCancellation`, `createBookingUuid`, and `InspectionBookingApi.cancelReservation` from Task 4.
- Produces: a single cancellation entry for `SUBMITTED`/`PLAN_PUBLISHED`, and a read-only “订单已取消” state used after either page cancels.

- [ ] **Step 1: Add failing page-contract tests**

Extend `booking-flow.test.ts`:

```ts
it('shows the approved cancellation entry and a read-only cancelled state', () => {
  expect(submittedPageSource).toContain('取消订单')
  expect(submittedPageSource).toContain('requestInspectionOrderCancellation')
  expect(submittedPageSource).toContain('confirmInspectionOrderCancellation')
  expect(submittedPageSource).toContain("reservation.status === 'CANCELLED'")
  expect(submittedPageSource).toContain('订单已取消')
  expect(submittedPageSource).toContain("isCancelling ? '取消中…' : '取消订单'")
  expect(submittedPageSource).not.toContain('退款成功')
})
```

- [ ] **Step 2: Run the focused page test and verify failure**

```powershell
Set-Location apps/mobile
pnpm.cmd test -- booking-flow.test.ts
```

Expected: FAIL because the page has no cancellation entry or cancelled presentation.

- [ ] **Step 3: Wire cancellation into the page script**

Import `computed`, `createBookingUuid`, the Task 4 use case and modal adapter. Add:

```ts
const isCancelling = ref(false)
const isCancelled = computed(() => reservation.value?.status === 'CANCELLED')
const canCancel = computed(() =>
  reservation.value !== null
  && canCancelInspectionOrder(reservation.value.status),
)

async function cancelOrder(): Promise<void> {
  if (!reservation.value || isCancelling.value) return
  isCancelling.value = true
  const result = await requestInspectionOrderCancellation({
    reservation: reservation.value,
    cancelReservation: inspectionBookingApi.cancelReservation,
    confirm: confirmInspectionOrderCancellation,
    idempotencyKey: createBookingUuid,
  })
  isCancelling.value = false
  if (result.kind === 'cancelled') {
    reservation.value = result.reservation
    uni.showToast({ title: '订单已取消', icon: 'success' })
    return
  }
  if (result.kind === 'refresh-required') {
    await loadReservation(reservationId.value)
    uni.showToast({ title: '订单状态已更新', icon: 'none' })
    return
  }
  if (result.kind === 'failed') {
    uni.showToast({ title: result.message, icon: 'none' })
  }
}
```

Guard the existing primary action:

```ts
function handlePrimaryAction(): void {
  if (reservation.value?.status === 'CANCELLED') {
    returnOrders()
    return
  }
  if (reservation.value?.currentPlanId) {
    uni.navigateTo({
      url: buildInspectionPlanConfirmationUrl(reservation.value.currentPlanId),
    })
    return
  }
  returnOrders()
}
```

- [ ] **Step 4: Render the cancelled state and secondary action**

Use dynamic success content:

```vue
<text class="booking-submitted__title">
  {{ isCancelled ? '订单已取消' : '订单已提交' }}
</text>
<text class="booking-submitted__copy">
  {{ isCancelled ? '本次服务已停止安排，订单记录将继续保留' : '预约申请已提交，服务商将在30分钟内联系您' }}
</text>
```

Place this after the timeline and before the fixed footer:

```vue
<button
  v-if="canCancel"
  class="booking-submitted__cancel"
  :disabled="isCancelling"
  @click="cancelOrder"
>
  {{ isCancelling ? '取消中…' : '取消订单' }}
</button>
```

Replace the active timeline block with these mutually exclusive branches:

```vue
<view v-if="isCancelled" class="booking-submitted__next-steps">
  <text class="booking-submitted__timeline-title">订单状态</text>
  <view class="booking-submitted__timeline-row booking-submitted__timeline-row--cancelled">
    <UniIcons type="info-filled" size="18" color="#7d8590" />
    <view><text>订单已取消</text><text>服务已停止安排</text></view>
  </view>
</view>
<view v-else class="booking-submitted__next-steps">
  <text class="booking-submitted__timeline-title">接下来</text>
  <view class="booking-submitted__timeline-row booking-submitted__timeline-row--done">
    <UniIcons type="checkbox-filled" size="18" color="#16af55" />
    <view><text>需求已提交</text><text>{{ formatSubmittedAt(reservation.submittedAt) }}</text></view>
  </view>
  <view class="booking-submitted__timeline-row booking-submitted__timeline-row--current">
    <UniIcons type="circle-filled" size="18" color="#ff6a00" />
    <view><text>服务商确认需求</text><text>等待联系</text></view>
  </view>
  <view class="booking-submitted__timeline-row">
    <UniIcons type="circle" size="18" color="#b5bac1" />
    <text>确认方案与报价</text>
  </view>
  <view class="booking-submitted__timeline-row">
    <UniIcons type="circle" size="18" color="#b5bac1" />
    <text>现场巡检与报告交付</text>
  </view>
</view>
```

The fixed footer keeps the existing two-column layout. Set its primary label exactly to:

```vue
{{ isCancelled
  ? '返回订单'
  : (reservation?.currentPlanId ? '查看服务方案' : '返回订单') }}
```

Style the cancellation entry as a 44px white secondary button with `1px solid #d9dde2`, `color:#c74432`, `border-radius:14px`; do not use the orange primary style and do not add a third fixed-footer column.

- [ ] **Step 5: Run page, API and type tests**

```powershell
pnpm.cmd test -- booking-flow.test.ts inspection-order-cancellation.test.ts
pnpm.cmd run type-check
```

Expected: all selected tests and type checking pass.

- [ ] **Step 6: Review the submitted-page diff without staging**

```powershell
Set-Location ..\..
git diff --check -- apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingSubmittedPage.vue apps/mobile/src/features/inspection/tests/booking-flow.test.ts
```

Expected: the fixed footer remains two columns; no refund copy and no unrelated booking form changes. Do not stage or commit.

---

### Task 6: Add plan-page cancellation and refresh the order center on return

**Files:**
- Modify: `apps/mobile/src/features/inspection/presentation/plan/pages/InspectionPlanConfirmationPage.vue`
- Modify: `apps/mobile/src/features/inspection/presentation/orders/pages/InspectionOrderCenterPage.vue`
- Modify: `apps/mobile/src/features/inspection/tests/plan-flow.test.ts`
- Modify: `apps/mobile/src/features/inspection/tests/order-center.test.ts`

**Interfaces:**
- Consumes: Task 4 cancellation use case and `buildInspectionBookingSubmittedUrl(reservationId)`.
- Produces: cancellation before confirmation from the plan page, redirect to the cancelled detail, and fresh order list/counts after returning.

- [ ] **Step 1: Add failing plan and order-center contract tests**

Add to `plan-flow.test.ts`:

```ts
it('allows cancellation only before plan confirmation and returns to cancelled detail', () => {
  expect(planPageSource).toContain('取消订单')
  expect(planPageSource).toContain('requestInspectionOrderCancellation')
  expect(planPageSource).toContain('confirmInspectionOrderCancellation')
  expect(planPageSource).toContain('buildInspectionBookingSubmittedUrl')
  expect(planPageSource).toContain('v-if="!isConfirmed && canCancel"')
  expect(planPageSource).not.toContain('退款成功')
})
```

Add a raw source import for `InspectionOrderCenterPage.vue` to `order-center.test.ts` and assert:

```ts
it('reloads orders and stage counts whenever the order center becomes visible', () => {
  expect(orderCenterPageSource).toContain('onShow(() =>')
  expect(orderCenterPageSource).toContain('void controller.reload()')
  expect(orderCenterPageSource).toContain(
    'state.selectedStage = normalizeInspectionOrderFilter(query?.stage)',
  )
  expect(orderCenterPageSource).not.toContain(
    'state.selectedStage = normalizeInspectionOrderFilter(query?.stage)\n  void controller.reload()',
  )
})
```

Implement the assertion as two separate `expect` calls: `onLoad` must keep only filter normalization, and `onShow` must own reload.

- [ ] **Step 2: Run focused tests and verify failure**

```powershell
Set-Location apps/mobile
pnpm.cmd test -- plan-flow.test.ts order-center.test.ts
```

Expected: FAIL because the plan page has no cancellation entry and the order center reloads only during `onLoad`.

- [ ] **Step 3: Wire cancellation into the plan page**

Import the Task 4 use case/modal, `createBookingUuid`, and `buildInspectionBookingSubmittedUrl`. Add this state:

```ts
const isCancelling = ref(false)
const canCancel = computed(() =>
  reservation.value !== null
  && canCancelInspectionOrder(reservation.value.status),
)
```

Use this action:

```ts
async function cancelOrder(): Promise<void> {
  if (!reservation.value || isConfirmed.value || isCancelling.value) return
  isCancelling.value = true
  const result = await requestInspectionOrderCancellation({
    reservation: reservation.value,
    cancelReservation: inspectionBookingApi.cancelReservation,
    confirm: confirmInspectionOrderCancellation,
    idempotencyKey: createBookingUuid,
  })
  isCancelling.value = false
  if (result.kind === 'cancelled') {
    uni.redirectTo({
      url: buildInspectionBookingSubmittedUrl(result.reservation.id),
    })
    return
  }
  if (result.kind === 'refresh-required') {
    const latest = await inspectionBookingApi.getReservation(reservation.value.id)
    if (latest.status === 'CANCELLED') {
      uni.redirectTo({ url: buildInspectionBookingSubmittedUrl(latest.id) })
      return
    }
    if (plan.value) await loadPlan(plan.value.id)
    uni.showToast({ title: '订单状态已更新', icon: 'none' })
    return
  }
  if (result.kind === 'failed') {
    uni.showToast({ title: result.message, icon: 'none' })
  }
}
```

Add this secondary button after the plan content and above the fixed confirmation footer:

```vue
<button
  v-if="!isConfirmed && canCancel"
  class="plan-confirmation__cancel"
  :disabled="isCancelling"
  @click="cancelOrder"
>
  {{ isCancelling ? '取消中…' : '取消订单' }}
</button>
```

Use the same 44px secondary-button token from Task 5. Do not add it to the fixed two-column confirm action row.

- [ ] **Step 4: Move order-center loading to `onShow`**

Import `onShow`. Keep route parsing in `onLoad`, remove the reload call from it, and add:

```ts
onLoad((query: { stage?: string } | undefined) => {
  state.selectedStage = normalizeInspectionOrderFilter(query?.stage)
})

onShow(() => {
  void controller.reload()
})
```

This yields one initial load and reloads after navigation returns, so cancelled orders and stage counts are fresh.

- [ ] **Step 5: Run focused mobile tests and type checking**

```powershell
pnpm.cmd test -- inspection-order-cancellation.test.ts booking-flow.test.ts plan-flow.test.ts order-center.test.ts
pnpm.cmd run type-check
```

Expected: all selected tests and type checking pass.

- [ ] **Step 6: Review the plan/order-center diff without staging**

```powershell
Set-Location ..\..
git diff --check -- apps/mobile/src/features/inspection/presentation/plan/pages/InspectionPlanConfirmationPage.vue apps/mobile/src/features/inspection/presentation/orders/pages/InspectionOrderCenterPage.vue apps/mobile/src/features/inspection/tests/plan-flow.test.ts apps/mobile/src/features/inspection/tests/order-center.test.ts
```

Expected: no order-card layout change, no payment/refund copy, and no unrelated navigation edits. Do not stage or commit.

---

### Task 7: Run full regression, runtime checks and Android acceptance

**Files:**
- Verify only: all files listed in Tasks 1–6.
- Do not create APK artifacts in this task.

**Interfaces:**
- Consumes: completed backend, contract and mobile cancellation flow.
- Produces: evidence that cancellation works without regressing booking, plan confirmation, order stages or existing dirty changes.

- [ ] **Step 1: Run the full mobile verification set**

```powershell
Set-Location apps/mobile
pnpm.cmd test
pnpm.cmd run type-check
pnpm.cmd run build:h5
```

Expected: all Vitest tests pass, `vue-tsc` exits 0, and H5 build exits 0.

- [ ] **Step 2: Run the full backend verification set**

```powershell
Set-Location ..\..
.\mvnw.cmd -B -pl :bootstrap -am clean test
.\mvnw.cmd -B -pl :bootstrap -am package "-DskipTests"
```

Expected: every reactor module is `SUCCESS`, all tests pass, and bootstrap package is produced without test execution in the second command.

- [ ] **Step 3: Validate Compose and runtime readiness without claiming success from container status alone**

```powershell
docker compose -f infra/compose.yaml config --quiet
$inspectionPort = if ($env:INSPECTION_APP_PORT) { $env:INSPECTION_APP_PORT } else { '8080' }
powershell -NoProfile -ExecutionPolicy Bypass -File infra/scripts/verify-runtime-readiness.ps1 -BackendBaseUrl "http://127.0.0.1:$inspectionPort"
```

Expected: Compose configuration is valid; readiness checks confirm the required API is reachable, not merely that containers are running.

- [ ] **Step 4: Exercise the real cancellation state matrix**

Using a customer token with `inspection.reservation.cancel.own`, verify:

```text
SUBMITTED -> cancel -> 200 / CANCELLED / ETag increments
PLAN_PUBLISHED -> cancel -> 200 / reservation CANCELLED / plan WITHDRAWN
same Idempotency-Key replay -> 200 / same reservation / no duplicate event
stale If-Match -> 412 INS_VERSION_CONFLICT
confirmed plan or existing service order -> 409 INS_RESERVATION_NOT_CANCELLABLE
another customer -> 404 COMMON_NOT_FOUND
```

Query the order API after each successful cancellation and confirm the order remains visible under “全部”, no longer counts under “待方案/待确认”, and the same time slot capacity increases by one.

- [ ] **Step 5: Perform HBuilderX Android acceptance without cloud packaging**

On the connected Android device:

```text
1. 打开待方案订单，确认页面显示“取消订单”。
2. 点“取消订单”再点“暂不取消”，确认订单保持不变。
3. 再次取消并确认，确认按钮出现“取消中…”，随后显示“订单已取消”。
4. 返回“我的订单”，确认待方案数量减少、全部列表保留已取消订单。
5. 打开待确认订单，确认存在相同入口；取消后方案不能再确认。
6. 打开已进入服务安排的订单，确认没有取消入口。
7. 检查状态栏、底部安全区、返回手势以及 393×852 逻辑视口无遮挡。
```

Expected:所有步骤与设计规格一致；不云打包 APK。

- [ ] **Step 6: Audit the complete diff and preserve unrelated work**

```powershell
git diff --check
git diff --stat
git status --short
git diff -- apps/mobile/src/features/inspection backend/modules/inspection backend/modules/identity/migrations backend/bootstrap contracts docs/superpowers
```

Expected: no unapproved deletion, rename, full-file formatting, payment/refund implementation, credential, token or unrelated module change. Existing unrelated dirty files remain untouched. Do not stage, commit or push.

---

## Completion Gate

The feature is complete only when all of the following are evidenced:

- `SUBMITTED` and `PLAN_PUBLISHED` cancellation succeed through the real API.
- Published plan is `WITHDRAWN`, reservation is `CANCELLED`, time-slot capacity is released, and one audit/outbox event exists.
- Confirm-vs-cancel race cannot produce both a cancelled reservation and a service order.
- Repeated cancellation is idempotent; stale version and invalid state return distinct errors.
- The submitted page and plan page share identical copy and behavior; no list-card cancellation button exists.
- Cancelled orders remain visible, cannot re-enter plan confirmation, and order-center counts refresh.
- No payment or refund capability was introduced.
- Full mobile tests/type-check/H5 build, full backend tests/package, Compose/readiness and Android acceptance all pass.
