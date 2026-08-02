# 巡检预约提前一小时 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让巡检预约时段在 `Asia/Shanghai` 时区下只允许选择和提交“开始时间不早于当前时间加一小时”的时段，并在时段变旧时给出可恢复的明确提示。

**Architecture:** `InspectionTimeSlotPolicy` 是提前量计算的唯一事实源；可用时段查询用它返回 `TOO_SOON`，创建预约在容量检查前用同一规则再次校验。移动端只消费服务端状态：非 `AVAILABLE` 时段不可选，提交竞态由专用错误码恢复到时间选择弹层，其他表单内容保持不变。

**Tech Stack:** Java 21、Spring Boot 3.5、JUnit 5、MockMvc、Vue 3、TypeScript strict、uni-app、Vitest、OpenAPI 3、HBuilderX Android 调试基座。

## Global Constraints

- 业务时区固定为 `Asia/Shanghai`。
- 最早可预约时刻为 `当前时刻 + 1 小时`；时段开始时刻等于该边界时可选，早一秒也不可选。
- `AVAILABLE` 可选；`FULL` 显示“已约满”；`TOO_SOON` 显示“需提前1小时预约”。
- 提交不满足提前量的预约返回 HTTP `409`、错误码 `INS_TIME_SLOT_TOO_SOON`。
- 提交竞态后保留地点、线路长度、巡检项目、附件、说明、联系人和协议勾选状态，只清除失效时间并重新打开时间弹层。
- 原有“今天至未来 30 天”、容量 9、`INS_TIME_SLOT_FULL`、幂等与并发锁行为保持不变。
- 不修改支付、退款、订单流转、数据库结构、时段容量、日期范围或其他页面。
- 不新增依赖，不记录令牌、坐标、手机号或完整请求体。
- 本计划属于用户已批准的跨前后端业务规则，明确覆盖默认 2 个业务文件/30 行预算；仍禁止无关重构、格式化和改名。
- 当前工作树已有其他未提交改动；每一步只审计本任务差异，不撤销、不覆盖、不暂存其他改动。
- 项目规则禁止自动提交；各任务的提交命令仅在用户另行明确授权后执行。

## File Structure

- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicy.java`：唯一负责上海时区、固定时段与一小时提前量计算。
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java`：查询状态编排与创建预约的服务端最终校验。
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/TimeSlotTooSoonException.java`：提前量不足的专用领域异常。
- `backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/api/GlobalExceptionHandler.java`：把专用异常映射为稳定 HTTP 错误。
- `contracts/openapi-inspection-service-flow.yaml`：把 `TOO_SOON` 和 `INS_TIME_SLOT_TOO_SOON` 纳入正式契约。
- `contracts/inspection-service-flow.examples.json`：提供 `TOO_SOON` 返回示例。
- `apps/mobile/src/features/inspection/data/service-flow/inspection-booking-api.ts`：移动端可用性 DTO 联合类型。
- `apps/mobile/src/features/inspection/presentation/booking/components/InspectionTimePickerSheet.vue`：禁用状态、文案和刷新后失效选择处理。
- `apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingPage.vue`：提交竞态错误恢复。
- 对应测试文件只验证本规则，不引入生产 Mock。

---

### Task 1: 用固定时钟锁定一小时边界

**Files:**
- Modify: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicyTest.java:6-48`
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicy.java:3-43`

**Interfaces:**
- Consumes: `InspectionTimeSlotDefinition`、`Clock`、`BUSINESS_ZONE`。
- Produces: `static boolean satisfiesLeadTime(LocalDate serviceDate, InspectionTimeSlotDefinition slot, Clock clock)`。

- [ ] **Step 1: 写边界失败测试**

在 `InspectionTimeSlotPolicyTest` 增加上海时间 17:00 和 17:00:01 对当天 18:00 时段的断言：

```java
@Test
void acceptsExactlyOneHourLeadTimeAndRejectsOneSecondLess() {
    LocalDate serviceDate = LocalDate.of(2026, 8, 1);
    InspectionTimeSlotDefinition evening = InspectionTimeSlotPolicy.slot("EVENING");
    Clock exactlyOneHour = Clock.fixed(
            Instant.parse("2026-08-01T09:00:00Z"), ZoneOffset.UTC);
    Clock oneSecondTooLate = Clock.fixed(
            Instant.parse("2026-08-01T09:00:01Z"), ZoneOffset.UTC);

    assertThat(InspectionTimeSlotPolicy.satisfiesLeadTime(
            serviceDate, evening, exactlyOneHour)).isTrue();
    assertThat(InspectionTimeSlotPolicy.satisfiesLeadTime(
            serviceDate, evening, oneSecondTooLate)).isFalse();
}
```

- [ ] **Step 2: 运行测试并确认 RED**

Run from repository root:

```powershell
.\mvnw.cmd -B -pl :inspection -am "-Dtest=InspectionTimeSlotPolicyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: compilation fails because `satisfiesLeadTime` does not exist; existing date-range tests remain discoverable.

- [ ] **Step 3: 写最小策略实现**

在 `InspectionTimeSlotPolicy` 增加 `Duration`、`ZonedDateTime` 导入和唯一计算函数：

```java
import java.time.Duration;
import java.time.ZonedDateTime;

private static final Duration MINIMUM_LEAD_TIME = Duration.ofHours(1);

static boolean satisfiesLeadTime(
        LocalDate serviceDate,
        InspectionTimeSlotDefinition slot,
        Clock clock
) {
    ZonedDateTime slotStart = ZonedDateTime.of(
            serviceDate, slot.startTime(), BUSINESS_ZONE);
    return !slotStart.toInstant().isBefore(
            clock.instant().plus(MINIMUM_LEAD_TIME));
}
```

不在控制器、客户端或 SQL 中重复计算“当前时间 + 1 小时”。

- [ ] **Step 4: 运行测试并确认 GREEN**

Run the same Maven command.

Expected: `InspectionTimeSlotPolicyTest` passes, including exact one-hour acceptance and one-second-short rejection.

- [ ] **Step 5: 审查本任务差异**

```powershell
git diff -- backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicy.java backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicyTest.java
git diff --check -- backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicy.java backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicyTest.java
```

Expected: only policy and its test change; no date-range, slot time or capacity changes. Do not stage or commit without new user authorization.

---

### Task 2: 查询返回 TOO_SOON，创建预约再次拒绝过早时段

**Files:**
- Modify: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java:17-233`
- Create: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/TimeSlotTooSoonException.java`
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java:35-64,181-228`

**Interfaces:**
- Consumes: `InspectionTimeSlotPolicy.satisfiesLeadTime(...)` from Task 1。
- Produces: `TimeSlotTooSoonException`；`InspectionTimeSlotView.availability()` may now be `TOO_SOON`。

- [ ] **Step 1: 写查询和提交失败测试**

在 `ReservationApplicationServiceTest` 增加：

```java
@Test
void marksTodaySlotsTooSoonWithoutPretendingTheyAreFull() {
    ReservationApplicationService service = new ReservationApplicationService(
            new InMemoryServiceCatalog(
                    SERVICE_ID, PROVIDER_ID, "服务端巡检标题", null),
            new InMemoryReservationStore(),
            new PlainTestCipher(),
            Clock.fixed(Instant.parse("2026-08-01T09:00:01Z"), ZoneOffset.UTC));

    List<InspectionTimeSlotView> slots = service.availableTimeSlots(
            SERVICE_ID, "370500", LocalDate.of(2026, 8, 1), 1)
            .getFirst()
            .slots();

    assertThat(slots)
            .extracting(
                    InspectionTimeSlotView::slotCode,
                    InspectionTimeSlotView::remainingCount,
                    InspectionTimeSlotView::availability)
            .containsExactly(
                    org.assertj.core.groups.Tuple.tuple("MORNING", 9, "TOO_SOON"),
                    org.assertj.core.groups.Tuple.tuple("AFTERNOON", 9, "TOO_SOON"),
                    org.assertj.core.groups.Tuple.tuple("EVENING", 9, "TOO_SOON"));
}

@Test
void rejectsCreatingAReservationLessThanOneHourBeforeSlotStart() {
    ReservationApplicationService service = new ReservationApplicationService(
            new InMemoryServiceCatalog(
                    SERVICE_ID, PROVIDER_ID, "服务端巡检标题", null),
            new InMemoryReservationStore(),
            new PlainTestCipher(),
            Clock.fixed(Instant.parse("2026-08-01T09:00:01Z"), ZoneOffset.UTC));
    CreateReservationCommand tooSoon = command(
            SERVICE_ID, LocalDate.of(2026, 8, 1), "EVENING");

    assertThatThrownBy(() -> service.create(
            CUSTOMER_ID, IDEMPOTENCY_KEY, tooSoon))
            .isInstanceOf(TimeSlotTooSoonException.class);
}
```

把现有 helper 保留为默认未来日期，并增加明确重载：

```java
private static CreateReservationCommand command(UUID serviceId) {
    return command(serviceId, LocalDate.of(2026, 8, 1), "MORNING");
}

private static CreateReservationCommand command(
        UUID serviceId,
        LocalDate serviceDate,
        String slotCode
) {
    return new CreateReservationCommand(
            serviceId, "370500", "东营市开发区测试路1号", 118.5, 37.4,
            1200L, serviceDate, slotCode, List.of("PIPELINE"), List.of(),
            "现场需要重点查看", "张三", "13800138000", "v1", true);
}
```

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
.\mvnw.cmd -B -pl :inspection -am "-Dtest=ReservationApplicationServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: compilation fails because `TimeSlotTooSoonException` does not exist; after adding only the class, assertions still fail because service behavior is unchanged.

- [ ] **Step 3: 添加专用异常并接入创建校验**

Create `TimeSlotTooSoonException.java`:

```java
package com.lowaltitude.inspection.application.reservation;

public final class TimeSlotTooSoonException extends RuntimeException {

    public TimeSlotTooSoonException() {
        super("inspection time slot requires one hour lead time");
    }
}
```

在 `ReservationApplicationService.create` 的初始幂等重放判断之后先完成日期、时段和服务快照校验，再在加锁与容量查询之前执行提前量判断：

```java
InspectionTimeSlotPolicy.validateDateRange(
        command.preferredServiceDate(), 1, clock);
InspectionTimeSlotDefinition selectedSlot = InspectionTimeSlotPolicy.slot(
        command.preferredTimeSlotCode());
InspectionServiceSnapshot snapshot = services.findActive(
        command.serviceId(), command.cityCode())
        .orElseThrow(() -> new ReservationValidationException(
                "service is unknown or disabled"));
if (!InspectionTimeSlotPolicy.satisfiesLeadTime(
        command.preferredServiceDate(), selectedSlot, clock)) {
    throw new TimeSlotTooSoonException();
}
```

保持首次 `store.findIdempotency(...)` 在该校验之前，使已成功请求的幂等重放不会因时间流逝而失败。

- [ ] **Step 4: 接入可用时段状态**

在 `availableTimeSlots` 构造每个时段时，先计算提前量，再计算状态；容量数值仍真实返回：

```java
boolean leadTimeSatisfied = InspectionTimeSlotPolicy.satisfiesLeadTime(
        date, slot, clock);
String availability = !leadTimeSatisfied
        ? "TOO_SOON"
        : remaining == 0 ? "FULL" : "AVAILABLE";
return new InspectionTimeSlotView(
        slot.code(),
        slot.startTime(),
        slot.endTime(),
        remaining,
        availability);
```

`TOO_SOON` 优先于 `FULL`，因为用户当前无法预约的直接原因是提前量不足；`remainingCount` 不伪造为 0。

- [ ] **Step 5: 运行测试并确认 GREEN**

Run the same Maven command.

Expected: existing capacity/idempotency tests and new `TOO_SOON` tests all pass.

- [ ] **Step 6: 审查本任务差异**

```powershell
git diff -- backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicy.java backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/TimeSlotTooSoonException.java backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java
git diff --check -- backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java
```

Expected: no persistence, schema, slot-capacity or order-flow changes. Do not stage or commit.

---

### Task 3: 固化 HTTP 和 OpenAPI 契约

**Files:**
- Modify: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationControllerTest.java:10-320`
- Modify: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotControllerTest.java:80-114`
- Modify: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/api/GlobalExceptionHandler.java:12-109`
- Modify: `contracts/openapi-inspection-service-flow.yaml:763-775,954-975`
- Modify: `contracts/inspection-service-flow.examples.json:73-99`

**Interfaces:**
- Consumes: `TimeSlotTooSoonException` and `TOO_SOON` from Task 2。
- Produces: HTTP `409 INS_TIME_SLOT_TOO_SOON`；OpenAPI enum `[AVAILABLE, FULL, TOO_SOON]`。

- [ ] **Step 1: 写控制器失败测试**

在 `InspectionReservationControllerTest` 增加一个单独使用 17:00:01 上海时钟的请求：

```java
@Test
void returnsConflictWhenTimeSlotDoesNotMeetOneHourLeadTime() throws Exception {
    service = new ReservationApplicationService(
            new InMemoryServiceCatalog(SERVICE, PROVIDER, "服务端标题", null),
            store,
            new ReversibleTestCipher(),
            Clock.fixed(Instant.parse("2026-08-01T09:00:01Z"), ZoneOffset.UTC));
    mvc = MockMvcBuilders
            .standaloneSetup(new InspectionReservationController(service, queryService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    mvc.perform(post("/api/v1/inspection/reservations")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .header("X-Request-Id", REQUEST_ID)
                    .contentType("application/json")
                    .content(requestJson(
                            "东营市提前量测试路", "2026-08-01", "EVENING"))
                    .principal(customerJwt()))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INS_TIME_SLOT_TOO_SOON"))
            .andExpect(jsonPath("$.message").value("该时段需提前1小时预约，请重新选择"))
            .andExpect(jsonPath("$.traceId").value(REQUEST_ID));
}
```

为现有 `requestJson(String address)` 增加显式重载，不改变所有旧调用：

```java
private static String requestJson(String address) {
    return requestJson(address, "2026-08-01", "MORNING");
}

private static String requestJson(
        String address,
        String serviceDate,
        String slotCode
) {
    return """
            {
              "serviceId": "%s",
              "cityCode": "370500",
              "location": {
                "address": "%s",
                "longitude": 118.5,
                "latitude": 37.4
              },
              "lineLengthMeters": 12,
              "preferredServiceDate": "%s",
              "preferredTimeSlotCode": "%s",
              "inspectionItemCodes": ["PIPELINE"],
              "contact": {"name": "张三", "mobile": "13800138000"},
              "agreementVersion": "v1",
              "agreementAccepted": true
            }
            """.formatted(SERVICE, address, serviceDate, slotCode);
}
```

在 `InspectionTimeSlotControllerTest` 的 mocked fixture 中把当天一个时段设为 `TOO_SOON`，并断言：

```java
.andExpect(jsonPath("$[0].slots[0].availability").value("TOO_SOON"))
```

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
.\mvnw.cmd -B -pl :bootstrap -am "-Dtest=InspectionReservationControllerTest,InspectionTimeSlotControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

Expected: reservation controller returns the generic 500 mapping for the new exception, while the availability serialization assertion documents the new wire value.

- [ ] **Step 3: 添加稳定错误映射**

在 `GlobalExceptionHandler` 导入异常并添加：

```java
import com.lowaltitude.inspection.application.reservation.TimeSlotTooSoonException;

@ExceptionHandler(TimeSlotTooSoonException.class)
public ResponseEntity<ApiError> handleTimeSlotTooSoon(
        TimeSlotTooSoonException exception,
        HttpServletRequest request
) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError(
                    "INS_TIME_SLOT_TOO_SOON",
                    "该时段需提前1小时预约，请重新选择",
                    traceId(request)));
}
```

不要把它并入 `ReservationValidationException` 的 422 通用错误。

- [ ] **Step 4: 更新正式契约和示例**

在 OpenAPI 中把错误码与状态枚举改为：

```yaml
        - INS_TIME_SLOT_FULL
        - INS_TIME_SLOT_TOO_SOON
```

```yaml
        availability:
          type: string
          enum: [AVAILABLE, FULL, TOO_SOON]
```

在 `inspection-service-flow.examples.json` 的可用时段例子中保留一个 `FULL`，并把另一个不可预约时段写成：

```json
{
  "slotCode": "MORNING",
  "startTime": "09:00:00",
  "endTime": "12:00:00",
  "remainingCount": 9,
  "availability": "TOO_SOON"
}
```

- [ ] **Step 5: 运行测试并确认 GREEN**

Run the same Maven command.

Expected: controller tests pass; `traceId` is preserved; the new availability string serializes unchanged.

- [ ] **Step 6: 校验契约文件和差异**

```powershell
Get-Content -Raw -Encoding UTF8 contracts/inspection-service-flow.examples.json | ConvertFrom-Json | Out-Null
rg -n "INS_TIME_SLOT_TOO_SOON|enum: \[AVAILABLE, FULL, TOO_SOON\]" contracts/openapi-inspection-service-flow.yaml
git diff --check -- backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/api/GlobalExceptionHandler.java backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation contracts/openapi-inspection-service-flow.yaml contracts/inspection-service-flow.examples.json
```

Expected: JSON parses, both contract additions are present, no unrelated contract sections change. Do not stage or commit.

---

### Task 4: 移动端禁用 TOO_SOON 并说明原因

**Files:**
- Modify: `apps/mobile/src/features/inspection/tests/booking-time-selection.test.ts:1-176`
- Modify: `apps/mobile/src/features/inspection/data/service-flow/inspection-booking-api.ts:48-56`
- Modify: `apps/mobile/src/features/inspection/presentation/booking/components/InspectionTimePickerSheet.vue:169-207,238-273,397-414`

**Interfaces:**
- Consumes: wire value `TOO_SOON` from Task 3。
- Produces: `BookingAvailableTimeSlot['availability']` union includes `TOO_SOON`; only `AVAILABLE` is selectable。

- [ ] **Step 1: 写移动端失败测试**

扩展 availability fixture，使 API mapper 必须保留 `TOO_SOON`：

```ts
{
  slotCode: 'MORNING',
  startTime: '09:00',
  endTime: '12:00',
  remainingCount: 9,
  availability: 'TOO_SOON',
}
```

并在 sheet source contract 中加入：

```ts
expect(normalizedSheetSource).toContain('需提前1小时预约')
expect(normalizedSheetSource).toContain("slot.availability !== 'AVAILABLE'")
expect(normalizedSheetSource).toContain("case 'TOO_SOON':")
expect(normalizedSheetSource).toContain('该时段需提前1小时预约，请重新选择')
```

保留原有“已约满”断言，防止用新状态覆盖容量状态。

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
Set-Location apps/mobile
pnpm.cmd test -- booking-time-selection.test.ts
```

Expected: source assertions fail because `TOO_SOON` UI and union type are not implemented.

- [ ] **Step 3: 扩展 DTO 联合类型**

把 `BookingAvailableTimeSlot` 改为：

```ts
export interface BookingAvailableTimeSlot {
  slotCode: BookingTimeSlotCode
  startTime: string
  endTime: string
  remainingCount: number
  availability: 'AVAILABLE' | 'FULL' | 'TOO_SOON'
}
```

- [ ] **Step 4: 统一不可选判断和文案**

在 `InspectionTimePickerSheet.vue` 增加两个本地函数：

```ts
function slotUnavailableLabel(slot: BookingAvailableTimeSlot): string {
  switch (slot.availability) {
    case 'FULL':
      return '已约满'
    case 'TOO_SOON':
      return '需提前1小时预约'
    default:
      return ''
  }
}

function slotSelectionError(slot: BookingAvailableTimeSlot): string {
  switch (slot.availability) {
    case 'FULL':
      return '该时段已约满，请重新选择'
    case 'TOO_SOON':
      return '该时段需提前1小时预约，请重新选择'
    default:
      return ''
  }
}
```

把选择、刷新核对和确认核对统一为“仅 `AVAILABLE` 放行”：

```ts
if (refreshedSlot && refreshedSlot.availability !== 'AVAILABLE') {
  temporarySelection.value = null
  selectionError.value = slotSelectionError(refreshedSlot)
}
```

`reconcileTemporarySelection` 遇到当前批次中没有该时段时必须保留临时选择，因为打开弹层时已确认但不在前 4 天内的日期会在下一次请求中单独加载；只有实际返回且状态不是 `AVAILABLE` 才能清除。

```ts
function selectSlot(slot: BookingAvailableTimeSlot): void {
  if (isLoading.value || slot.availability !== 'AVAILABLE') {
    return
  }
  temporarySelection.value = {
    serviceDate: selectedDate.value,
    slotCode: slot.slotCode,
    startTime: slot.startTime,
    endTime: slot.endTime,
  }
  selectionError.value = ''
}
```

```ts
if (!refreshedSlot || refreshedSlot.availability !== 'AVAILABLE') {
  temporarySelection.value = null
  selectionError.value = refreshedSlot
    ? slotSelectionError(refreshedSlot)
    : '该时段已不可用，请重新选择'
  return
}
```

模板改为：

```vue
<button
  :class="[
    'inspection-time-sheet__slot',
    {
      'inspection-time-sheet__slot--selected': isSelectedSlot(slot),
      'inspection-time-sheet__slot--full':
        slot.availability !== 'AVAILABLE',
    },
  ]"
  :disabled="isLoading || slot.availability !== 'AVAILABLE'"
  @tap="selectSlot(slot)"
>
  <text v-if="slot.availability !== 'AVAILABLE'">
    {{ slotUnavailableLabel(slot) }}
  </text>
  <text v-else>
    {{ formatClockTime(slot.startTime) }}–{{ formatClockTime(slot.endTime) }}
  </text>
</button>
```

不改变面板高度、日期范围、触控尺寸或可用时段的时间文案。

- [ ] **Step 5: 运行测试和类型检查并确认 GREEN**

```powershell
pnpm.cmd test -- booking-time-selection.test.ts
pnpm.cmd run type-check
```

Expected: targeted Vitest and strict type-check pass; both `FULL` and `TOO_SOON` are disabled with distinct text.

- [ ] **Step 6: 审查本任务差异**

```powershell
Set-Location ../..
git diff -- apps/mobile/src/features/inspection/data/service-flow/inspection-booking-api.ts apps/mobile/src/features/inspection/presentation/booking/components/InspectionTimePickerSheet.vue apps/mobile/src/features/inspection/tests/booking-time-selection.test.ts
git diff --check -- apps/mobile/src/features/inspection/data/service-flow/inspection-booking-api.ts apps/mobile/src/features/inspection/presentation/booking/components/InspectionTimePickerSheet.vue apps/mobile/src/features/inspection/tests/booking-time-selection.test.ts
```

Expected: no booking layout redesign, native picker reintroduction or unrelated API changes. Do not stage or commit.

---

### Task 5: 提交竞态后保留表单并重新选时间

**Files:**
- Modify: `apps/mobile/src/features/inspection/tests/booking-time-selection.test.ts:120-176`
- Modify: `apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingPage.vue:290-329`

**Interfaces:**
- Consumes: `ApiRequestError.code === 'INS_TIME_SLOT_TOO_SOON'` from Task 3。
- Produces: clear only `draft.preferredServiceTime` and `preferredServiceTimeText`; reopen `InspectionTimePickerSheet`。

- [ ] **Step 1: 写恢复行为失败测试**

在 `booking-time-selection.test.ts` 增加源代码契约：

```ts
it('recovers a too-soon submit without clearing the rest of the booking draft', () => {
  expect(bookingPageSource).toContain("error.code === 'INS_TIME_SLOT_TOO_SOON'")
  expect(bookingPageSource).toContain("submitError.value = '该时段需提前1小时预约，请重新选择'")
  expect(bookingPageSource).toContain('draft.value.preferredServiceTime = null')
  expect(bookingPageSource).toContain("preferredServiceTimeText.value = ''")
  expect(bookingPageSource).toContain('isTimePickerVisible.value = true')
  expect(bookingPageSource).not.toContain('draft.value = createEmptyBookingDraft()')
})
```

- [ ] **Step 2: 运行测试并确认 RED**

```powershell
Set-Location apps/mobile
pnpm.cmd test -- booking-time-selection.test.ts
```

Expected: new recovery assertions fail while existing `INS_TIME_SLOT_FULL` behavior remains green.

- [ ] **Step 3: 添加最小恢复分支**

在现有 `INS_TIME_SLOT_FULL` 分支之后添加：

```ts
if (
  error instanceof ApiRequestError
  && error.code === 'INS_TIME_SLOT_TOO_SOON'
) {
  draft.value.preferredServiceTime = null
  preferredServiceTimeText.value = ''
  submitError.value = '该时段需提前1小时预约，请重新选择'
  isTimePickerVisible.value = true
  return
}
```

不要重建 `draft`，不要删除附件，不要生成新的提交会话，也不要改变 `INS_TIME_SLOT_FULL` 分支。

- [ ] **Step 4: 运行测试和类型检查并确认 GREEN**

```powershell
pnpm.cmd test -- booking-time-selection.test.ts
pnpm.cmd run type-check
```

Expected: targeted test and type-check pass; only失效时间被清除。

- [ ] **Step 5: 审查本任务差异**

```powershell
Set-Location ../..
git diff -- apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingPage.vue apps/mobile/src/features/inspection/tests/booking-time-selection.test.ts
git diff --check -- apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingPage.vue apps/mobile/src/features/inspection/tests/booking-time-selection.test.ts
```

Expected: no form reset, upload, redirect, payment or order-flow changes. Do not stage or commit.

---

### Task 6: 全量回归、运行态检查和 HBuilderX 真机验收

**Files:**
- Verify only: all files changed in Tasks 1-5
- Evidence directory (ignored): `artifacts/inspection-booking-one-hour-lead-time/`

**Interfaces:**
- Consumes: completed backend, contract and mobile changes。
- Produces: repeatable test evidence and Android acceptance result; no APK cloud package。

- [ ] **Step 1: 运行前后端针对性测试**

From repository root:

```powershell
.\mvnw.cmd -B -pl :bootstrap -am "-Dtest=InspectionTimeSlotPolicyTest,ReservationApplicationServiceTest,InspectionReservationControllerTest,InspectionTimeSlotControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
pnpm.cmd --dir apps/mobile test -- booking-time-selection.test.ts
```

Expected: all named Java and Vitest tests pass.

- [ ] **Step 2: 运行全量静态与回归验证**

```powershell
.\mvnw.cmd -B -pl :bootstrap -am clean test
pnpm.cmd --dir apps/mobile test
pnpm.cmd --dir apps/mobile run type-check
pnpm.cmd --dir apps/mobile run build:h5
```

Expected: backend reactor tests, full mobile tests, strict type-check and H5 production build all pass. Record exact test counts rather than saying “应该可以”.

- [ ] **Step 3: 生成 App 调试资源，不云打包 APK**

```powershell
Set-Location apps/mobile
.\node_modules\.bin\uni.cmd build -p app
Set-Location ../..
```

Expected: resources are generated under `apps/mobile/dist/build/app`; no APK is produced or overwritten.

- [ ] **Step 4: 重建后端并执行就绪检查**

```powershell
docker compose --env-file .env -f infra/compose.yaml up -d --build backend
docker compose --env-file .env -f infra/compose.yaml ps
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
powershell -NoProfile -ExecutionPolicy Bypass -File .\infra\scripts\verify-runtime-readiness.ps1
```

Expected: backend container is healthy, health endpoint reports `UP`, and runtime readiness verifies the current network rather than merely seeing a started container.

- [ ] **Step 5: 运行真实预约 smoke**

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\infra\scripts\verify-f04-reservation-smoke.ps1
```

Expected: tomorrow's reservation, idempotency replay and capacity race continue to pass; the script cleans only its own created reservations.

- [ ] **Step 6: 同步到已连接 Android 设备**

```powershell
adb devices -l
adb reverse tcp:8080 tcp:8080
adb reverse tcp:19000 tcp:19000
```

Use the existing HBuilderX “运行到 Android App 基座” flow to sync `apps/mobile/dist/build/app` to the single authorized device. Do not cloud-package and do not operate a second device or another worktree.

Expected: one device is `device`, reverse rules exist, and the debug app loads the current worktree resources.

- [ ] **Step 7: 真机验收边界和恢复**

On Android:

1. 登录本地验收账号，从“服务 → 我要巡检 → 电力巡检服务 → 预约”进入真实页面。
2. 打开“期望时间”；当天已开始或不足一小时的时段必须禁用并显示“需提前1小时预约”。
3. 可用未来时段仍显示真实起止时间；已满时段仍显示“已约满”。
4. 在边界测试环境用固定时钟自动测试确认“刚好一小时可用、少一秒不可用”；真机不通过修改系统时间伪造边界。
5. 模拟页面停留导致时段过期后提交：页面显示“该时段需提前1小时预约，请重新选择”，自动打开时间弹层，地点、线路长度、项目、附件、说明、联系人和协议勾选保持不变。
6. 保存真机截图和操作记录到 `artifacts/inspection-booking-one-hour-lead-time/`。

Expected: no white screen, no generic “请先登录”, no form reset, no expired slot submission.

- [ ] **Step 8: 最终差异和白名单审计**

```powershell
git status --short
git diff --stat
git diff --check
git diff -- backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicy.java backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/TimeSlotTooSoonException.java backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/api/GlobalExceptionHandler.java contracts/openapi-inspection-service-flow.yaml contracts/inspection-service-flow.examples.json apps/mobile/src/features/inspection/data/service-flow/inspection-booking-api.ts apps/mobile/src/features/inspection/presentation/booking/components/InspectionTimePickerSheet.vue apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingPage.vue backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicyTest.java backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationControllerTest.java backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotControllerTest.java apps/mobile/src/features/inspection/tests/booking-time-selection.test.ts
```

Expected: only the approved rule, contract, UI recovery and tests appear in this task's diff; no secret, `.env`, build output, HBuilderX cache, APK, payment/refund or unrelated user change is staged.

- [ ] **Step 9: 授权门控的提交步骤**

Do not run these commands until the user explicitly asks for a commit. If authorized, stage only the paths listed in Step 8, review `git diff --cached`, then create one focused commit:

```powershell
git commit -m "fix: require one hour lead time for inspection booking"
```

Expected: one change set for this business rule, with no unrelated worktree changes included.

## Self-Review

- Spec coverage: one-hour exact boundary, `TOO_SOON`, backend recheck, 409 error mapping, form-preserving recovery, contract update, full regression and HBuilderX Android acceptance all have explicit tasks.
- Scope: no payment/refund, database, capacity, date-range, order-flow, dependency or unrelated page work is included.
- Type consistency: backend wire value and frontend union both use exactly `TOO_SOON`; exception/error code use exactly `TimeSlotTooSoonException` and `INS_TIME_SLOT_TOO_SOON`.
- Placeholder scan: the plan contains no deferred implementation marker; all code paths, commands and expected results are specified.
- Project-rule check: no automatic staging, commit, push or APK cloud packaging is authorized by this plan.
