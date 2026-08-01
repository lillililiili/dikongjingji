# Inspection Time Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the booking page's native date picker with the approved bottom-sheet date-and-time selector, backed by real per-slot availability with a fixed capacity of 9.

**Architecture:** Keep selection state local to the existing booking page and isolate the sheet in one presentation component. The backend owns the three slot definitions, counts non-cancelled reservations, and serializes booking creation by locking the existing service row before the authoritative capacity check; no inventory table, new route, Pinia store, or scheduling administration is introduced.

**Tech Stack:** uni-app 3.0, Vue 3.4, TypeScript 4.9 strict mode, Vitest 1.6, Java 21, Spring Boot 3.5.16, MyBatis-Plus 3.5.17, PostgreSQL/PostGIS 16, Flyway, JUnit 5, MockMvc, PowerShell, Playwright.

## Global Constraints

- Work only in `C:\Users\Admin（无密码）\Documents\低空经济\.worktrees\inspection-service-flow`.
- Preserve the existing untracked `apps/mobile/.hbuilderx/` and `artifacts/` directories.
- The visual baseline is the user-provided “选择服务时间” screenshot; implement real controls and never reuse the screenshot as a background or crop.
- Keep `/pages/service/inspection/booking`; add no page and no route.
- Fixed slots are `MORNING 09:00–12:00`, `AFTERNOON 14:00–17:00`, and `EVENING 18:00–21:00`.
- Each service, city, date, and slot combination has capacity `9`.
- Do not add a slot inventory table, Pinia store, runtime dependency, scheduling administration, payment, refund, order-confirmation page, or cancellation endpoint.
- Selection range is today through 30 days from today in `Asia/Shanghai`.
- New reservations require a date and slot code; idempotent replay consumes no additional capacity.
- Existing M01, M02, service-detail structure, plan, fulfillment, delivery, bottom tabs, and unrelated dirty files stay unchanged.
- Follow TDD: capture a failing targeted test before each production change.
- Do not format whole files or directories.

---

## File Map

**Create**

- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicy.java` — fixed slot definitions, date-range validation, response view construction.
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/TimeSlotFullException.java` — typed capacity conflict.
- `backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotController.java` — authenticated availability endpoint.
- `backend/modules/inspection/migrations/V004__inspection_reservation_time_slot.sql` — reservation slot column, constraint, and count index.
- `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicyTest.java` — fixed-rule tests.
- `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotControllerTest.java` — HTTP contract tests.
- `apps/mobile/src/features/inspection/presentation/booking/components/InspectionTimePickerSheet.vue` — approved bottom sheet.
- `apps/mobile/src/features/inspection/tests/booking-time-selection.test.ts` — domain/API/component source-contract tests.
- `references/ui-03-inspection-time-picker.png` — durable copy of the user-approved visual baseline.

**Modify**

- `contracts/openapi-inspection-service-flow.yaml` — availability endpoint, slot schemas, reservation field, error code.
- `contracts/inspection-service-flow.examples.json` — selected slot and availability examples.
- `docs/12-inspection-service-flow-baseline.md` — current-scope time-selection and capacity rule.
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java` — availability query, validation, digest, capacity-aware reservation data.
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/PostgresReservationStore.java` — lock/count/save implementation.
- `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapper.java` — slot reads/writes and grouped counts.
- `backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionReservationController.java` — request/response slot field.
- `backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/api/GlobalExceptionHandler.java` — `INS_TIME_SLOT_FULL`.
- `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java` — capacity and idempotency tests.
- `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapperSqlTest.java` — lock/count/insert SQL checks.
- `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationControllerTest.java` — request/response and conflict tests.
- `apps/mobile/src/features/inspection/domain/service-flow/booking.ts` — structured date/slot selection and payload.
- `apps/mobile/src/features/inspection/data/service-flow/inspection-booking-api.ts` — availability DTO and client method.
- `apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingPage.vue` — replace native picker and handle confirmation/conflict.
- `apps/mobile/src/features/inspection/tests/booking-flow.test.ts` — existing booking regression expectations.
- `infra/scripts/verify-f04-reservation-smoke.ps1` — slot-aware reservation smoke.
- `infra/scripts/verify-f08-golden-e2e.cjs` — custom selector interaction and screenshot.
- `docs/15-inspection-service-flow-runbook.md` — targeted verification command and evidence path.

---

### Task 1: Freeze the Contract and Fixed Slot Policy

**Files:**

- Create: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicy.java`
- Create: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicyTest.java`
- Modify: `contracts/openapi-inspection-service-flow.yaml`
- Modify: `contracts/inspection-service-flow.examples.json`
- Modify: `docs/12-inspection-service-flow-baseline.md`

**Interfaces:**

- Produces: `InspectionTimeSlotPolicy.CAPACITY`, `InspectionTimeSlotPolicy.validateDateRange(LocalDate, int, Clock)`, `InspectionTimeSlotPolicy.slot(String)`, `InspectionTimeSlotView`, and `InspectionServiceDateAvailabilityView`.
- Consumes: Java `Clock`; business zone `Asia/Shanghai`.

- [ ] **Step 1: Write the failing policy test**

```java
package com.lowaltitude.inspection.application.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class InspectionTimeSlotPolicyTest {
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-31T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void exposesThreeFixedSlotsWithCapacityNine() {
        assertThat(InspectionTimeSlotPolicy.CAPACITY).isEqualTo(9);
        assertThat(InspectionTimeSlotPolicy.slots())
                .extracting(InspectionTimeSlotDefinition::code)
                .containsExactly("MORNING", "AFTERNOON", "EVENING");
        assertThat(InspectionTimeSlotPolicy.slot("MORNING").startTime().toString())
                .isEqualTo("09:00");
        assertThat(InspectionTimeSlotPolicy.slot("EVENING").endTime().toString())
                .isEqualTo("21:00");
    }

    @Test
    void acceptsTodayThroughThirtyDaysAndRejectsInvalidRanges() {
        InspectionTimeSlotPolicy.validateDateRange(
                LocalDate.of(2026, 7, 31), 31, CLOCK);
        assertThatThrownBy(() -> InspectionTimeSlotPolicy.validateDateRange(
                LocalDate.of(2026, 7, 30), 1, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InspectionTimeSlotPolicy.validateDateRange(
                LocalDate.of(2026, 7, 31), 32, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InspectionTimeSlotPolicy.slot("NIGHT"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
```

- [ ] **Step 2: Run the policy test and verify RED**

Run:

```powershell
.\mvnw.cmd -pl :inspection -am -Dtest=InspectionTimeSlotPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because `InspectionTimeSlotPolicy` and `InspectionTimeSlotDefinition` do not exist.

- [ ] **Step 3: Add the minimal fixed policy**

```java
package com.lowaltitude.inspection.application.reservation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

final class InspectionTimeSlotPolicy {
    static final int CAPACITY = 9;
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<InspectionTimeSlotDefinition> SLOTS = List.of(
            new InspectionTimeSlotDefinition("MORNING", LocalTime.of(9, 0), LocalTime.of(12, 0)),
            new InspectionTimeSlotDefinition("AFTERNOON", LocalTime.of(14, 0), LocalTime.of(17, 0)),
            new InspectionTimeSlotDefinition("EVENING", LocalTime.of(18, 0), LocalTime.of(21, 0)));

    private InspectionTimeSlotPolicy() {
    }

    static List<InspectionTimeSlotDefinition> slots() {
        return SLOTS;
    }

    static InspectionTimeSlotDefinition slot(String code) {
        return SLOTS.stream()
                .filter(value -> value.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("time slot code is invalid"));
    }

    static void validateDateRange(LocalDate startDate, int days, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        if (startDate == null
                || startDate.isBefore(today)
                || startDate.isAfter(today.plusDays(30))
                || days < 1
                || days > 31
                || startDate.plusDays(days - 1L).isAfter(today.plusDays(30))) {
            throw new IllegalArgumentException("time slot date range is invalid");
        }
    }
}

record InspectionTimeSlotDefinition(
        String code,
        LocalTime startTime,
        LocalTime endTime
) {
}

record InspectionTimeSlotView(
        String slotCode,
        LocalTime startTime,
        LocalTime endTime,
        int remainingCount,
        String availability
) {
}

record InspectionServiceDateAvailabilityView(
        LocalDate serviceDate,
        List<InspectionTimeSlotView> slots
) {
}
```

- [ ] **Step 4: Extend OpenAPI and examples**

Add the authenticated `GET /api/v1/inspection/services/{serviceId}/available-time-slots` operation with `cityCode`, `startDate`, and `days`. Add schemas whose exact property names match the Java and TypeScript DTOs:

```yaml
AvailableServiceDate:
  type: object
  additionalProperties: false
  required: [serviceDate, slots]
  properties:
    serviceDate:
      type: string
      format: date
    slots:
      type: array
      minItems: 3
      maxItems: 3
      items:
        $ref: "#/components/schemas/AvailableTimeSlot"
AvailableTimeSlot:
  type: object
  additionalProperties: false
  required:
    - slotCode
    - startTime
    - endTime
    - remainingCount
    - availability
  properties:
    slotCode:
      type: string
      enum: [MORNING, AFTERNOON, EVENING]
    startTime:
      type: string
      format: time
    endTime:
      type: string
      format: time
    remainingCount:
      type: integer
      minimum: 0
      maximum: 9
    availability:
      type: string
      enum: [AVAILABLE, FULL]
```

Add `preferredTimeSlotCode` to both `CreateReservationRequest` and `Reservation`, and add `INS_TIME_SLOT_FULL` to the API error-code enum. Add one example date with all three slots and one `FULL` state to `inspection-service-flow.examples.json`.

- [ ] **Step 5: Update the active service-flow baseline**

In `docs/12-inspection-service-flow-baseline.md`, extend INS-M-004 with the approved interaction and state exactly:

```text
期望时间使用内嵌底部弹层选择，不调用系统日期选择器。每天固定
09:00–12:00、14:00–17:00、18:00–21:00 三个时段，每个
“服务 + 城市 + 日期 + 时段”容量为 9。余量由后端按非 CANCELLED
预约实时计算，创建预约时再次校验。
```

Keep payment, refund, order-confirmation, scheduling administration, and unrelated pages excluded.

- [ ] **Step 6: Run the focused test and contract checks**

Run:

```powershell
.\mvnw.cmd -pl :inspection -am -Dtest=InspectionTimeSlotPolicyTest -Dsurefire.failIfNoSpecifiedTests=false test
rg -n "available-time-slots|preferredTimeSlotCode|INS_TIME_SLOT_FULL" contracts/openapi-inspection-service-flow.yaml contracts/inspection-service-flow.examples.json
```

Expected: the JUnit test passes and all three contract tokens appear in both intended contract files.

- [ ] **Step 7: Commit**

```powershell
git add -- `
  contracts/openapi-inspection-service-flow.yaml `
  contracts/inspection-service-flow.examples.json `
  docs/12-inspection-service-flow-baseline.md `
  backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicy.java `
  backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotPolicyTest.java
git commit -m "feat(inspection): define booking time slots"
```

---

### Task 2: Add Slot Persistence, Counts, and Service Locking

**Files:**

- Create: `backend/modules/inspection/migrations/V004__inspection_reservation_time_slot.sql`
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java`
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapper.java`
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/PostgresReservationStore.java`
- Modify: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionReservationController.java`
- Modify: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java`
- Modify: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationControllerTest.java`
- Modify: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapperSqlTest.java`

**Interfaces:**

- Consumes: the fixed slot policy from Task 1.
- Produces: slot-aware reservation request/response records, `ReservationStore.lockActiveService`, `ReservationStore.findBookedTimeSlotCounts`, and persisted `preferred_time_slot_code`.

- [ ] **Step 1: Add failing mapper SQL assertions**

Add this test:

```java
@Test
void locksServiceAndCountsOnlyActiveReservationsByDateAndSlot() throws Exception {
    Method lock = ReservationPersistenceMapper.class.getMethod(
            "lockActiveService", java.util.UUID.class, String.class);
    String lockSql = String.join(" ", lock.getAnnotation(
            org.apache.ibatis.annotations.Select.class).value());
    assertThat(lockSql).contains("FOR UPDATE");

    Method counts = ReservationPersistenceMapper.class.getMethod(
            "findBookedTimeSlotCounts",
            java.util.UUID.class,
            String.class,
            java.time.LocalDate.class,
            java.time.LocalDate.class);
    String countSql = String.join(" ", counts.getAnnotation(
            org.apache.ibatis.annotations.Select.class).value());
    assertThat(countSql)
            .contains("preferred_time_slot_code")
            .contains("status <> 'CANCELLED'")
            .contains("GROUP BY preferred_service_date, preferred_time_slot_code");

    Method insert = ReservationPersistenceMapper.class.getMethod(
            "insertReservation",
            ReservationPersistenceMapper.ReservationRow.class);
    String insertSql = String.join(" ", insert.getAnnotation(Insert.class).value());
    assertThat(insertSql).contains("preferred_time_slot_code");
}
```

- [ ] **Step 2: Run the mapper test and verify RED**

Run:

```powershell
.\mvnw.cmd -pl :inspection -am -Dtest=ReservationPersistenceMapperSqlTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: reflection fails because the new mapper methods do not exist.

- [ ] **Step 3: Add the migration**

Create:

```sql
ALTER TABLE inspection_reservation
    ADD COLUMN preferred_time_slot_code varchar(16) NOT NULL DEFAULT 'MORNING'
        CHECK (preferred_time_slot_code IN (
            'MORNING',
            'AFTERNOON',
            'EVENING'
        ));

ALTER TABLE inspection_reservation
    ALTER COLUMN preferred_time_slot_code DROP DEFAULT;

CREATE INDEX idx_inspection_reservation_slot_capacity
    ON inspection_reservation (
        service_id,
        city_code,
        preferred_service_date,
        preferred_time_slot_code,
        status
    );
```

This branch is pre-production and its existing reservations are acceptance fixtures. The temporary migration default permits the existing local database to upgrade; all new API requests must provide an explicit slot code and no runtime default remains.

- [ ] **Step 4: Add mapper methods and slot columns**

Add:

```java
@Select("""
        SELECT id
        FROM inspection_service
        WHERE id = #{serviceId}
          AND city_code = #{cityCode}
          AND status = 'ACTIVE'
        FOR UPDATE
        """)
UUID lockActiveService(
        @Param("serviceId") UUID serviceId,
        @Param("cityCode") String cityCode
);

@Select("""
        SELECT preferred_service_date service_date,
               preferred_time_slot_code slot_code,
               count(*) booked_count
        FROM inspection_reservation
        WHERE service_id = #{serviceId}
          AND city_code = #{cityCode}
          AND preferred_service_date BETWEEN #{startDate} AND #{endDate}
          AND status <> 'CANCELLED'
        GROUP BY preferred_service_date, preferred_time_slot_code
        """)
List<TimeSlotCountRow> findBookedTimeSlotCounts(
        @Param("serviceId") UUID serviceId,
        @Param("cityCode") String cityCode,
        @Param("startDate") LocalDate startDate,
        @Param("endDate") LocalDate endDate
);

record TimeSlotCountRow(
        LocalDate serviceDate,
        String slotCode,
        int bookedCount
) {
}
```

Include `r.preferred_time_slot_code` in reservation reads, `preferred_time_slot_code` in the insert column/value lists, and `String preferredTimeSlotCode` immediately after `LocalDate preferredServiceDate` in `ReservationRow`.

- [ ] **Step 5: Thread the slot through reservation records**

Add `String preferredTimeSlotCode` immediately after
`LocalDate preferredServiceDate` in:

- `CreateReservationCommand`
- `ReservationView`
- `StoredReservation`
- `ReservationDetails`
- `ReservationPersistenceMapper.ReservationRow`
- `InspectionReservationController.CreateReservationRequest`
- `InspectionReservationController.ReservationResponse`

Validate with:

```java
InspectionTimeSlotPolicy.validateDateRange(
        command.preferredServiceDate(), 1, clock);
InspectionTimeSlotPolicy.slot(command.preferredTimeSlotCode());
```

Include the slot code in the SHA-256 request digest. Update every constructor
call, JSON request fixture, and response assertion explicitly with
`MORNING`; do not add a runtime default.

Define one fixed clock in each reservation test class and use it for every
`ReservationApplicationService` instance:

```java
private static final Clock BOOKING_CLOCK = Clock.fixed(
        Instant.parse("2026-07-31T01:00:00Z"),
        ZoneOffset.UTC);
```

- [ ] **Step 6: Extend the store port and PostgreSQL adapter**

Add to `ReservationStore`:

```java
void lockActiveService(UUID serviceId, String cityCode);

List<BookedTimeSlotCount> findBookedTimeSlotCounts(
        UUID serviceId,
        String cityCode,
        LocalDate startDate,
        LocalDate endDate
);
```

Add the shared record:

```java
record BookedTimeSlotCount(
        LocalDate serviceDate,
        String slotCode,
        int bookedCount
) {
}
```

Implement both in `PostgresReservationStore` by delegating to the mapper. Throw `ReservationValidationException` if `lockActiveService` returns `null`. Thread `preferredTimeSlotCode` through `save` and `findById`.

- [ ] **Step 7: Keep existing in-memory adapters compiling**

Implement the new methods explicitly in both test stores. Before capacity fixtures are added, return current stored counts rather than a fixed success:

```java
@Override
public void lockActiveService(UUID serviceId, String cityCode) {
    if (serviceId == null || cityCode == null) {
        throw new ReservationValidationException("service is invalid");
    }
}

@Override
public List<BookedTimeSlotCount> findBookedTimeSlotCounts(
        UUID serviceId,
        String cityCode,
        LocalDate startDate,
        LocalDate endDate
) {
    return reservations.values().stream()
            .filter(value -> value.snapshot().serviceId().equals(serviceId))
            .filter(value -> value.cityCode().equals(cityCode))
            .filter(value -> !value.preferredServiceDate().isBefore(startDate))
            .filter(value -> !value.preferredServiceDate().isAfter(endDate))
            .collect(java.util.stream.Collectors.groupingBy(
                    value -> value.preferredServiceDate()
                            + ":"
                            + value.preferredTimeSlotCode(),
                    java.util.stream.Collectors.counting()))
            .entrySet().stream()
            .map(entry -> {
                String[] key = entry.getKey().split(":", 2);
                return new BookedTimeSlotCount(
                        LocalDate.parse(key[0]),
                        key[1],
                        entry.getValue().intValue());
            })
            .toList();
}
```

Make `ConflictStore` delegate both methods. Add the same explicit methods to
the controller test's `InMemoryReservationStore`.

- [ ] **Step 8: Run persistence and reservation tests**

Run:

```powershell
.\mvnw.cmd -pl :bootstrap -am -Dtest=ReservationPersistenceMapperSqlTest,ReservationApplicationServiceTest,InspectionReservationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
powershell -NoProfile -ExecutionPolicy Bypass -File .\infra\scripts\verify-f08-empty-database.ps1
```

Expected: mapper and slot-aware reservation tests pass; a clean PostgreSQL/PostGIS database applies V001 through V004 successfully.

- [ ] **Step 9: Commit**

```powershell
git add -- `
  backend/modules/inspection/migrations/V004__inspection_reservation_time_slot.sql `
  backend/modules/inspection/src/main/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapper.java `
  backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/PostgresReservationStore.java `
  backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java `
  backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionReservationController.java `
  backend/modules/inspection/src/test/java/com/lowaltitude/inspection/infrastructure/persistence/ReservationPersistenceMapperSqlTest.java `
  backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java `
  backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationControllerTest.java
git commit -m "feat(inspection): persist booking time slots"
```

---

### Task 3: Expose Real Time-Slot Availability

**Files:**

- Create: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotController.java`
- Create: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotControllerTest.java`
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java`
- Modify: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java`

**Interfaces:**

- Consumes: `ReservationStore.findBookedTimeSlotCounts`.
- Produces: `ReservationApplicationService.availableTimeSlots(UUID, String, LocalDate, int)` and the authenticated HTTP endpoint.

- [ ] **Step 1: Write failing availability tests**

Add a store fixture containing 9 `MORNING` reservations and 2 `AFTERNOON` reservations for `2026-08-01`, then assert:

```java
List<InspectionServiceDateAvailabilityView> dates =
        service.availableTimeSlots(
                SERVICE_ID,
                "370500",
                LocalDate.of(2026, 8, 1),
                1);

assertThat(dates).hasSize(1);
assertThat(dates.getFirst().slots())
        .extracting(
                InspectionTimeSlotView::slotCode,
                InspectionTimeSlotView::remainingCount,
                InspectionTimeSlotView::availability)
        .containsExactly(
                org.assertj.core.groups.Tuple.tuple("MORNING", 0, "FULL"),
                org.assertj.core.groups.Tuple.tuple("AFTERNOON", 7, "AVAILABLE"),
                org.assertj.core.groups.Tuple.tuple("EVENING", 9, "AVAILABLE"));
```

Create a MockMvc test that performs:

```java
mvc.perform(get(
        "/api/v1/inspection/services/{serviceId}/available-time-slots",
        SERVICE)
        .param("cityCode", "370500")
        .param("startDate", "2026-08-01")
        .param("days", "1")
        .principal(customerJwt()))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$[0].serviceDate").value("2026-08-01"))
    .andExpect(jsonPath("$[0].slots[0].slotCode").value("MORNING"))
    .andExpect(jsonPath("$[0].slots[0].remainingCount").value(9));
```

- [ ] **Step 2: Run the focused tests and verify RED**

Run:

```powershell
.\mvnw.cmd -pl :bootstrap -am -Dtest=ReservationApplicationServiceTest,InspectionTimeSlotControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because the service method and controller do not exist.

- [ ] **Step 3: Implement availability aggregation**

Add to `ReservationApplicationService`:

```java
public List<InspectionServiceDateAvailabilityView> availableTimeSlots(
        UUID serviceId,
        String cityCode,
        LocalDate startDate,
        int days
) {
    if (serviceId == null
            || cityCode == null
            || !cityCode.matches("[A-Z0-9]{4,16}")) {
        throw new IllegalArgumentException("time slot query is invalid");
    }
    InspectionTimeSlotPolicy.validateDateRange(startDate, days, clock);
    services.findActive(serviceId, cityCode)
            .orElseThrow(() -> new ReservationValidationException(
                    "service is unknown or disabled"));
    LocalDate endDate = startDate.plusDays(days - 1L);
    Map<String, Integer> counts = store.findBookedTimeSlotCounts(
                    serviceId, cityCode, startDate, endDate).stream()
            .collect(java.util.stream.Collectors.toMap(
                    value -> value.serviceDate() + ":" + value.slotCode(),
                    BookedTimeSlotCount::bookedCount));

    return startDate.datesUntil(endDate.plusDays(1))
            .map(date -> new InspectionServiceDateAvailabilityView(
                    date,
                    InspectionTimeSlotPolicy.slots().stream()
                            .map(slot -> {
                                int booked = counts.getOrDefault(
                                        date + ":" + slot.code(), 0);
                                int remaining = Math.max(
                                        0,
                                        InspectionTimeSlotPolicy.CAPACITY - booked);
                                return new InspectionTimeSlotView(
                                        slot.code(),
                                        slot.startTime(),
                                        slot.endTime(),
                                        remaining,
                                        remaining == 0 ? "FULL" : "AVAILABLE");
                            })
                            .toList()))
            .toList();
}
```

- [ ] **Step 4: Add the controller**

```java
package com.lowaltitude.inspection.application.reservation;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class InspectionTimeSlotController {
    private final ReservationApplicationService service;

    public InspectionTimeSlotController(ReservationApplicationService service) {
        this.service = service;
    }

    @GetMapping(
            "/api/v1/inspection/services/{serviceId}/available-time-slots")
    public List<InspectionServiceDateAvailabilityView> availableTimeSlots(
            @PathVariable UUID serviceId,
            @RequestParam String cityCode,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate startDate,
            @RequestParam(defaultValue = "4") int days
    ) {
        return service.availableTimeSlots(
                serviceId,
                cityCode.trim(),
                startDate,
                days);
    }
}
```

Do not add this endpoint to the public security matcher. The booking page is already protected, so the existing authenticated default applies.

- [ ] **Step 5: Run availability tests**

Run:

```powershell
.\mvnw.cmd -pl :bootstrap -am -Dtest=ReservationApplicationServiceTest,InspectionTimeSlotControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: policy, service aggregation, parameter validation, authentication, and JSON response tests pass.

- [ ] **Step 6: Commit**

```powershell
git add -- `
  backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java `
  backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java `
  backend/bootstrap/src/main/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotController.java `
  backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionTimeSlotControllerTest.java
git commit -m "feat(inspection): expose booking slot availability"
```

---

### Task 4: Enforce Capacity During Reservation Creation

**Files:**

- Create: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/TimeSlotFullException.java`
- Modify: `backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java`
- Modify: `backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/api/GlobalExceptionHandler.java`
- Modify: `backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java`
- Modify: `backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationControllerTest.java`

**Interfaces:**

- Consumes: fixed policy, lock/count persistence, existing idempotency transaction.
- Produces: authoritative capacity enforcement and `409 INS_TIME_SLOT_FULL`.

- [ ] **Step 1: Write failing ninth/tenth/idempotency tests**

Use the counting adapter introduced in Task 2. Add:

```java
@Test
void acceptsNineReservationsAndRejectsTheTenthForOneSlot() {
    InMemoryReservationStore store = new InMemoryReservationStore();
    ReservationApplicationService service = new ReservationApplicationService(
            new InMemoryServiceCatalog(
                    SERVICE_ID, PROVIDER_ID, "服务端巡检标题", null),
            store,
            new PlainTestCipher(),
            Clock.fixed(
                    Instant.parse("2026-07-31T01:00:00Z"),
                    ZoneOffset.UTC));

    for (int index = 0; index < 9; index++) {
        service.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                command(SERVICE_ID));
    }

    assertThatThrownBy(() -> service.create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            command(SERVICE_ID)))
            .isInstanceOf(TimeSlotFullException.class);
}

@Test
void idempotentReplayDoesNotConsumeASecondSlot() {
    InMemoryReservationStore store = new InMemoryReservationStore();
    ReservationApplicationService service = new ReservationApplicationService(
            new InMemoryServiceCatalog(
                    SERVICE_ID, PROVIDER_ID, "服务端巡检标题", null),
            store,
            new PlainTestCipher(),
            BOOKING_CLOCK);

    service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));
    service.create(CUSTOMER_ID, IDEMPOTENCY_KEY, command(SERVICE_ID));

    assertThat(store.findBookedTimeSlotCounts(
            SERVICE_ID,
            "370500",
            LocalDate.of(2026, 8, 1),
            LocalDate.of(2026, 8, 1)))
            .extracting(
                    BookedTimeSlotCount::slotCode,
                    BookedTimeSlotCount::bookedCount)
            .containsExactly(
                    org.assertj.core.groups.Tuple.tuple("MORNING", 1));
}
```

Add a controller test expecting status `409` and code `INS_TIME_SLOT_FULL`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\mvnw.cmd -pl :bootstrap -am -Dtest=ReservationApplicationServiceTest,InspectionReservationControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: compilation fails because `TimeSlotFullException` and the
capacity rejection do not exist.

- [ ] **Step 3: Add the typed conflict**

```java
package com.lowaltitude.inspection.application.reservation;

public final class TimeSlotFullException extends RuntimeException {
    public TimeSlotFullException() {
        super("inspection time slot is full");
    }
}
```

Add to `GlobalExceptionHandler`:

```java
@ExceptionHandler(TimeSlotFullException.class)
public ResponseEntity<ApiError> handleTimeSlotFull(
        TimeSlotFullException exception,
        HttpServletRequest request
) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(new ApiError(
                    "INS_TIME_SLOT_FULL",
                    "该时段刚刚约满，请重新选择",
                    traceId(request)));
}
```

- [ ] **Step 4: Make reservation creation authoritative**

In `ReservationApplicationService.create`, acquire the database-backed
service lock after the first idempotency lookup and after resolving the
active service. Recheck idempotency while holding the lock so two concurrent
requests with the same key still replay:

```java
store.lockActiveService(
        command.serviceId(),
        command.cityCode());
Optional<IdempotencyEntry> concurrentPrior =
        store.findIdempotency(customerId, idempotencyKey);
if (concurrentPrior.isPresent()) {
    if (!concurrentPrior.get().requestDigest().equals(digest)) {
        throw new IdempotencyConflictException();
    }
    return toView(store.findById(concurrentPrior.get().reservationId())
            .orElseThrow());
}
int booked = store.findBookedTimeSlotCounts(
        command.serviceId(),
        command.cityCode(),
        command.preferredServiceDate(),
        command.preferredServiceDate()).stream()
        .filter(count -> count.slotCode().equals(
                command.preferredTimeSlotCode()))
        .mapToInt(BookedTimeSlotCount::bookedCount)
        .findFirst()
        .orElse(0);
if (booked >= InspectionTimeSlotPolicy.CAPACITY) {
    throw new TimeSlotFullException();
}
```

The existing `@Transactional` on `ReservationApplicationService.create` keeps the service lock, count, idempotency record, and reservation insert in one transaction.

- [ ] **Step 5: Run backend reservation tests**

Run:

```powershell
.\mvnw.cmd -pl :bootstrap -am -Dtest=ReservationApplicationServiceTest,InspectionReservationControllerTest,ReservationPersistenceMapperSqlTest -Dsurefire.failIfNoSpecifiedTests=false test
```

Expected: nine reservations pass, the tenth returns the typed conflict, and idempotent replay remains one reservation.

- [ ] **Step 6: Commit**

```powershell
git add -- `
  backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/TimeSlotFullException.java `
  backend/modules/inspection/src/main/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationService.java `
  backend/bootstrap/src/main/java/com/lowaltitude/inspection/bootstrap/api/GlobalExceptionHandler.java `
  backend/modules/inspection/src/test/java/com/lowaltitude/inspection/application/reservation/ReservationApplicationServiceTest.java `
  backend/bootstrap/src/test/java/com/lowaltitude/inspection/application/reservation/InspectionReservationControllerTest.java
git commit -m "feat(inspection): enforce booking slot capacity"
```

---

### Task 5: Add Mobile Selection and Availability Types

**Files:**

- Create: `apps/mobile/src/features/inspection/tests/booking-time-selection.test.ts`
- Modify: `apps/mobile/src/features/inspection/domain/service-flow/booking.ts`
- Modify: `apps/mobile/src/features/inspection/data/service-flow/inspection-booking-api.ts`
- Modify: `apps/mobile/src/features/inspection/tests/booking-flow.test.ts`

**Interfaces:**

- Produces: `BookingTimeSelection`, `BookingAvailableDate`, `InspectionBookingApi.loadAvailableTimeSlots`, and slot-aware `CreateReservationPayload`.
- Consumes: backend DTO names frozen in Tasks 1–4.

- [ ] **Step 1: Write failing domain and API tests**

```ts
import { describe, expect, it } from 'vitest'

import {
  createEmptyBookingDraft,
  createReservationPayload,
  validateBookingDraft,
  type BookingServiceSnapshot,
} from '../domain/service-flow/booking'
import {
  createInspectionBookingApi,
  type BookingHttpClient,
} from '../data/service-flow/inspection-booking-api'

describe('booking time selection', () => {
  it('requires one confirmed date and slot', () => {
    const draft = createEmptyBookingDraft()
    expect(validateBookingDraft(draft)).toContain('请选择期望巡检时间')
  })

  it('submits stable date and slot values without display copy', () => {
    const service = {
      serviceId: '40000000-0000-4000-8000-000000000001',
      cityCode: '370500',
      categoryName: '电力',
      title: '线路巡检',
      summary: '测试',
      coverImageUrl: null,
      inspectionItems: [{ code: 'TOWER', label: '杆塔' }],
      priceMode: 'PROJECT_QUOTE',
      priceAmountMinor: null,
      priceUnitLabel: null,
      priceLabel: '按项目报价',
    } satisfies BookingServiceSnapshot
    const payload = createReservationPayload(service, {
      ...createEmptyBookingDraft(),
      locationAddress: '东营市测试路1号',
      locationLongitude: 118.5,
      locationLatitude: 37.4,
      lineLengthKm: '1',
      preferredServiceTime: {
        serviceDate: '2026-08-01',
        slotCode: 'MORNING',
      },
      inspectionItemCodes: ['TOWER'],
      contactName: '张三',
      contactMobile: '13800138000',
      agreementAccepted: true,
    })
    expect(payload).toMatchObject({
      preferredServiceDate: '2026-08-01',
      preferredTimeSlotCode: 'MORNING',
    })
    expect(payload).not.toHaveProperty('timeLabel')
  })

  it('requests encoded service, city, date, and days', async () => {
    const calls: string[] = []
    const http: BookingHttpClient = {
      request: async <T,>(input) => {
        calls.push(input.url)
        return [] as T
      },
      putFile: async () => ({ etag: 'etag' }),
    }
    const api = createInspectionBookingApi(http, {
      getAccessToken: () => 'token',
    })
    await api.loadAvailableTimeSlots(
      '40000000-0000-4000-8000-000000000001',
      '370500',
      '2026-08-01',
      4,
    )
    expect(calls).toEqual([
      '/api/v1/inspection/services/40000000-0000-4000-8000-000000000001/available-time-slots?cityCode=370500&startDate=2026-08-01&days=4',
    ])
  })
})
```

- [ ] **Step 2: Run the mobile test and verify RED**

Run:

```powershell
npm --prefix apps/mobile test -- src/features/inspection/tests/booking-time-selection.test.ts
```

Expected: TypeScript compilation fails because `preferredServiceTime` and `loadAvailableTimeSlots` do not exist.

- [ ] **Step 3: Update booking domain types**

Use:

```ts
export type BookingTimeSlotCode =
  | 'MORNING'
  | 'AFTERNOON'
  | 'EVENING'

export interface BookingTimeSelection {
  serviceDate: string
  slotCode: BookingTimeSlotCode
}
```

Replace `preferredServiceDate: string` in `BookingDraft` with:

```ts
preferredServiceTime: BookingTimeSelection | null
```

Initialize it to `null`; validate it with the message `请选择期望巡检时间`; map it to `preferredServiceDate` and `preferredTimeSlotCode` in `CreateReservationPayload`.

- [ ] **Step 4: Add availability DTOs and API method**

```ts
export interface BookingAvailableTimeSlot {
  slotCode: BookingTimeSlotCode
  startTime: string
  endTime: string
  remainingCount: number
  availability: 'AVAILABLE' | 'FULL'
}

export interface BookingAvailableDate {
  serviceDate: string
  slots: BookingAvailableTimeSlot[]
}
```

Add to `InspectionBookingApi`:

```ts
loadAvailableTimeSlots(
  serviceId: string,
  cityCode: string,
  startDate: string,
  days: number,
): Promise<BookingAvailableDate[]>
```

Implement it with authenticated `http.request`, not `publicReadHttp`.

- [ ] **Step 5: Update existing booking fixtures**

Replace every test fixture assignment:

```ts
preferredServiceDate: '2026-08-02'
```

with:

```ts
preferredServiceTime: {
  serviceDate: '2026-08-02',
  slotCode: 'MORNING',
}
```

Update `BookingReservation` to expose `preferredTimeSlotCode`.

- [ ] **Step 6: Run mobile domain/API tests**

Run:

```powershell
npm --prefix apps/mobile test -- src/features/inspection/tests/booking-time-selection.test.ts src/features/inspection/tests/booking-flow.test.ts
npm --prefix apps/mobile run type-check
```

Expected: both Vitest files and strict type-check pass.

- [ ] **Step 7: Commit**

```powershell
git add -- `
  apps/mobile/src/features/inspection/domain/service-flow/booking.ts `
  apps/mobile/src/features/inspection/data/service-flow/inspection-booking-api.ts `
  apps/mobile/src/features/inspection/tests/booking-time-selection.test.ts `
  apps/mobile/src/features/inspection/tests/booking-flow.test.ts
git commit -m "feat(inspection): model booking time selection"
```

---

### Task 6: Build the Bottom Sheet and Replace the Native Picker

**Files:**

- Create: `apps/mobile/src/features/inspection/presentation/booking/components/InspectionTimePickerSheet.vue`
- Create: `references/ui-03-inspection-time-picker.png`
- Modify: `apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingPage.vue`
- Modify: `apps/mobile/src/features/inspection/tests/booking-time-selection.test.ts`
- Modify: `apps/mobile/src/features/inspection/tests/booking-flow.test.ts`

**Interfaces:**

- Consumes: `BookingTimeSelection`, `BookingAvailableDate`, and `InspectionBookingApi.loadAvailableTimeSlots`.
- Produces: `confirm` event with `selection: BookingTimeSelection` and `displayText: string`.

- [ ] **Step 1: Add failing component source-contract tests**

Import the new component and booking page as raw source. Assert:

```ts
expect(sheetSource).toContain('选择服务时间')
expect(sheetSource).toContain('选择日期')
expect(sheetSource).toContain('选择时段')
expect(sheetSource).toContain('预计服务时长3小时')
expect(sheetSource).toContain('更多日期')
expect(sheetSource).toContain('已约满')
expect(sheetSource).toContain('env(safe-area-inset-bottom)')
expect(sheetSource).toContain('UniCalendar')
expect(bookingPageSource).toContain('<InspectionTimePickerSheet')
expect(bookingPageSource).toContain('openTimePicker')
expect(bookingPageSource).not.toContain('<picker mode="date"')
expect(bookingPageSource).not.toContain('draft.preferredServiceDate')
```

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
npm --prefix apps/mobile test -- src/features/inspection/tests/booking-time-selection.test.ts src/features/inspection/tests/booking-flow.test.ts
```

Expected: module import fails because `InspectionTimePickerSheet.vue` does not exist.

- [ ] **Step 3: Preserve and verify the approved baseline**

Run:

```powershell
Copy-Item `
  -LiteralPath 'C:\Users\Admin（无密码）\AppData\Local\Temp\codex-clipboard-15047236-3c33-47ad-8e7f-fcb576dfcbf4.png' `
  -Destination 'references\ui-03-inspection-time-picker.png'
$hash = (Get-FileHash `
  -LiteralPath 'references\ui-03-inspection-time-picker.png' `
  -Algorithm SHA256).Hash
if ($hash -ne '27B659F01D6626C8BBB9A6A8BA2C29A324FE3485C17645141D1E864A603244E8') {
  throw "Unexpected time-picker baseline hash: $hash"
}
```

Expected: the committed reference remains the approved `852×1846` image and its SHA-256 matches exactly.

- [ ] **Step 4: Implement the component behavior**

The component must:

- accept `visible`, `serviceId`, `cityCode`, `modelValue`, and a typed `loadSlots` function;
- copy `modelValue` to temporary state on open;
- load four dates beginning today;
- ignore stale responses using an incrementing request ID;
- open `UniCalendar` for “更多日期” with today and today + 30 days as bounds;
- request one selected calendar date when it is outside the current four-day result;
- disable selection until loading completes;
- render a two-column slot grid using backend `startTime` and `endTime`;
- clear only the temporary selection and show `该时段已约满，请重新选择` when a refreshed response marks it `FULL`;
- keep the previously confirmed parent value until the user confirms another available slot;
- show an inline `时间加载失败` state and `.inspection-time-sheet__retry` button without closing the sheet;
- emit nothing on close or mask click;
- emit selection and formatted display text only from the orange confirmation button.

Use these public props and emits:

```ts
type LoadSlots = (
  serviceId: string,
  cityCode: string,
  startDate: string,
  days: number,
) => Promise<BookingAvailableDate[]>

const props = defineProps<{
  visible: boolean
  serviceId: string
  cityCode: string
  modelValue: BookingTimeSelection | null
  loadSlots: LoadSlots
}>()

const emit = defineEmits<{
  close: []
  confirm: [
    selection: BookingTimeSelection,
    displayText: string,
  ]
}>()
```

Render the approved states with these class names so E2E and focused visual QA have stable selectors:

```text
inspection-time-sheet__mask
inspection-time-sheet__panel
inspection-time-sheet__date
inspection-time-sheet__date--selected
inspection-time-sheet__slot
inspection-time-sheet__slot--selected
inspection-time-sheet__slot--full
inspection-time-sheet__summary
inspection-time-sheet__confirm
inspection-time-sheet__retry
```

Use a white panel with top radii, orange `#ff5a00` selection and button, `#fff4ef` selected fill, `#f7f7f7` disabled fill, 44px minimum controls, and bottom padding that includes `env(safe-area-inset-bottom)`.

- [ ] **Step 5: Integrate the existing booking row**

In `InspectionBookingPage.vue`:

```ts
const isTimePickerVisible = ref(false)
const preferredServiceTimeText = ref('')

function openTimePicker(): void {
  if (!service.value || !currentCity.value) return
  isTimePickerVisible.value = true
}

function confirmPreferredTime(
  selection: BookingTimeSelection,
  displayText: string,
): void {
  draft.value.preferredServiceTime = selection
  preferredServiceTimeText.value = displayText
  isTimePickerVisible.value = false
}
```

Replace the native picker with a tappable row whose value is:

```vue
<text
  :class="[
    'booking-row__date',
    { 'booking-row__date--empty': !preferredServiceTimeText },
  ]"
>
  {{ preferredServiceTimeText || '请选择服务时间' }}
</text>
```

Mount the sheet once beneath the page footer and pass:

```vue
<InspectionTimePickerSheet
  v-if="service && currentCity"
  :visible="isTimePickerVisible"
  :service-id="service.serviceId"
  :city-code="currentCity.code"
  :model-value="draft.preferredServiceTime"
  :load-slots="inspectionBookingApi.loadAvailableTimeSlots"
  @close="isTimePickerVisible = false"
  @confirm="confirmPreferredTime"
/>
```

- [ ] **Step 6: Handle submit-time slot conflicts**

Import `ApiRequestError` from `uni-booking-http-client`. In the existing submit catch:

```ts
if (error instanceof ApiRequestError
    && error.code === 'INS_TIME_SLOT_FULL') {
  submitError.value = '该时段刚刚约满，请重新选择'
  isTimePickerVisible.value = true
  return
}
submitError.value =
  error instanceof Error ? error.message : '预约提交失败，请重试'
```

Do not clear any other draft field or attachment state.

- [ ] **Step 7: Run mobile checks**

Run:

```powershell
npm --prefix apps/mobile test -- src/features/inspection/tests/booking-time-selection.test.ts src/features/inspection/tests/booking-flow.test.ts
npm --prefix apps/mobile run type-check
npm --prefix apps/mobile run build:h5
```

Expected: targeted tests, strict type-check, and H5 build pass with no native date picker in the booking page.

- [ ] **Step 8: Commit**

```powershell
git add -- `
  apps/mobile/src/features/inspection/presentation/booking/components/InspectionTimePickerSheet.vue `
  apps/mobile/src/features/inspection/presentation/booking/pages/InspectionBookingPage.vue `
  apps/mobile/src/features/inspection/tests/booking-time-selection.test.ts `
  apps/mobile/src/features/inspection/tests/booking-flow.test.ts `
  references/ui-03-inspection-time-picker.png
git commit -m "feat(inspection): add booking time sheet"
```

---

### Task 7: Update Smoke, E2E, Visual Evidence, and Full Regression

**Files:**

- Modify: `infra/scripts/verify-f04-reservation-smoke.ps1`
- Modify: `infra/scripts/verify-f08-golden-e2e.cjs`
- Modify: `docs/15-inspection-service-flow-runbook.md`
- Evidence only: ignored `artifacts/inspection-time-selection/`

**Interfaces:**

- Consumes: stable sheet selectors and slot-aware API.
- Produces: repeatable service-capacity smoke, golden-path interaction, and two visual-comparison rounds.

- [ ] **Step 1: Update smoke requests**

Define a future date once before building `$reservationBody`:

```powershell
$serviceDate = (Get-Date).Date.AddDays(1).ToString('yyyy-MM-dd')
```

Replace the fixed `preferredServiceDate` value and add the slot:

```powershell
preferredServiceDate = $serviceDate
preferredTimeSlotCode = 'MORNING'
```

next to every `preferredServiceDate` in `verify-f04-reservation-smoke.ps1`. Before creating the reservation, query:

```powershell
$availability = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/services/$serviceId/available-time-slots?cityCode=370500&startDate=$serviceDate&days=1" `
    -Headers @{ Authorization = "Bearer $customerToken" }
if ($availability[0].slots.Count -ne 3) {
    throw 'Time-slot availability did not return three fixed slots.'
}
```

Keep the existing idempotency replay assertion. After it passes, use a second future date and the `EVENING` slot to exercise the real PostgreSQL lock:

```powershell
$capacityDate = (Get-Date).Date.AddDays(2).ToString('yyyy-MM-dd')
$capacityAvailability = Invoke-RestMethod `
    -Method Get `
    -Uri "$BaseUrl/api/v1/inspection/services/$serviceId/available-time-slots?cityCode=370500&startDate=$capacityDate&days=1" `
    -Headers @{ Authorization = "Bearer $customerToken" }
$capacitySlot = $capacityAvailability[0].slots `
    | Where-Object { $_.slotCode -eq 'EVENING' }
if ($null -eq $capacitySlot -or $capacitySlot.remainingCount -lt 2) {
    throw 'Capacity smoke requires at least two remaining EVENING slots.'
}

$capacityObject = $reservationBody | ConvertFrom-Json
$capacityObject.preferredServiceDate = $capacityDate
$capacityObject.preferredTimeSlotCode = 'EVENING'
$capacityBody = $capacityObject | ConvertTo-Json -Depth 6 -Compress

for ($index = 1; $index -lt $capacitySlot.remainingCount; $index++) {
    Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/api/v1/inspection/reservations" `
        -ContentType 'application/json; charset=utf-8' `
        -Body $capacityBody `
        -Headers @{
            Authorization = "Bearer $customerToken"
            'Idempotency-Key' = [Guid]::NewGuid().ToString()
            'X-Request-Id' = [Guid]::NewGuid().ToString()
        } | Out-Null
}

$client = [System.Net.Http.HttpClient]::new()
$requests = @()
$responses = @()
try {
    $client.DefaultRequestHeaders.Authorization =
        [System.Net.Http.Headers.AuthenticationHeaderValue]::new(
            'Bearer',
            $customerToken
        )
    $requests = 1..2 | ForEach-Object {
        $request = [System.Net.Http.HttpRequestMessage]::new(
            [System.Net.Http.HttpMethod]::Post,
            "$BaseUrl/api/v1/inspection/reservations"
        )
        $request.Headers.Add(
            'Idempotency-Key',
            [Guid]::NewGuid().ToString()
        )
        $request.Headers.Add(
            'X-Request-Id',
            [Guid]::NewGuid().ToString()
        )
        $request.Content = [System.Net.Http.StringContent]::new(
            $capacityBody,
            [System.Text.Encoding]::UTF8,
            'application/json'
        )
        $request
    }
    $tasks = @(
        $client.SendAsync($requests[0]),
        $client.SendAsync($requests[1])
    )
    [System.Threading.Tasks.Task]::WaitAll(
        [System.Threading.Tasks.Task[]]$tasks
    )
    $responses = $tasks | ForEach-Object { $_.Result }
    $statuses = $responses `
        | ForEach-Object { [int]$_.StatusCode } `
        | Sort-Object
    if (($statuses -join ',') -ne '201,409') {
        throw "Concurrent capacity result was $($statuses -join ',')."
    }
    $conflict = $responses `
        | Where-Object { [int]$_.StatusCode -eq 409 } `
        | Select-Object -First 1
    $conflictBody = $conflict.Content.ReadAsStringAsync().Result `
        | ConvertFrom-Json
    if ($conflictBody.code -ne 'INS_TIME_SLOT_FULL') {
        throw "Unexpected capacity conflict code: $($conflictBody.code)"
    }
} finally {
    foreach ($request in $requests) {
        $request.Dispose()
    }
    foreach ($response in $responses) {
        $response.Dispose()
    }
    $client.Dispose()
}
```

Expected: after filling the slot to one remaining place, two concurrent requests produce exactly one `201` and one `409 INS_TIME_SLOT_FULL`.

- [ ] **Step 2: Replace the native picker E2E action**

Replace the `.uni-picker-action-confirm` interaction with:

```js
await page.getByText('期望时间', { exact: true }).click()
await page.locator('.inspection-time-sheet__panel').waitFor()
await page.locator('.inspection-time-sheet__date').first().click()
await page.locator('.inspection-time-sheet__slot')
  .filter({ hasText: '09:00–12:00' })
  .click()
await page.locator('.inspection-time-sheet__confirm').click()
await page.getByText(/月\d+日 09:00–12:00/).waitFor()
```

Capture:

```js
await page.screenshot({
  path: 'artifacts/inspection-time-selection/round-1-390.png',
  fullPage: true,
})
```

- [ ] **Step 3: Run backend smoke and golden E2E**

Run:

```powershell
docker compose --env-file .env -f infra/compose.yaml up -d --build
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
powershell -NoProfile -ExecutionPolicy Bypass -File .\infra\scripts\verify-f04-reservation-smoke.ps1
npm --prefix apps/mobile run build:h5
npm --prefix apps/mobile exec -- vite preview --host 127.0.0.1 --port 4173 --outDir dist/build/h5
```

In a second terminal:

```powershell
if (-not $env:INSPECTION_PLAYWRIGHT_MODULE `
    -or -not (Test-Path -LiteralPath $env:INSPECTION_PLAYWRIGHT_MODULE)) {
  throw 'INSPECTION_PLAYWRIGHT_MODULE must point to the approved Playwright module directory.'
}
if (-not $env:INSPECTION_BROWSER_EXECUTABLE `
    -or -not (Test-Path -LiteralPath $env:INSPECTION_BROWSER_EXECUTABLE)) {
  throw 'INSPECTION_BROWSER_EXECUTABLE must point to the approved Edge or Chromium executable.'
}
node .\infra\scripts\verify-f08-golden-e2e.cjs
```

Expected: backend health returns `UP`, reservation smoke passes, and the golden path reaches booking submission after selecting a visible time range.

- [ ] **Step 4: Perform two measured visual correction rounds**

At 375, 390, and 430 CSS-pixel widths:

1. Open the sheet with the first date and `MORNING` selected.
2. Save baseline and implementation crops at the same width.
3. Save side-by-side and 50% overlay images under:

```text
artifacts/inspection-time-selection/round-1/
artifacts/inspection-time-selection/round-2/
```

4. Record deltas for panel bounds, top radii, handle, title, close icon, date-card bounds, two-column grid, disabled state, summary, button, and safe-area padding.
5. Fix only this component and repeat the focused crop plus full-page comparison.

The second round must have no unresolved user-identified mismatch, no incorrect icon source, no changed line wrap, and no spacing delta above 2 CSS px after viewport normalization.

- [ ] **Step 5: Run the full regression**

Run:

```powershell
.\mvnw.cmd test
npm --prefix apps/mobile test
npm --prefix apps/mobile run type-check
npm --prefix apps/mobile run build:h5
git diff --check
git status --short
```

Expected: all backend and mobile tests pass, H5 builds, the diff check is clean, and status contains only intended task files plus the pre-existing ignored/untracked acceptance directories.

- [ ] **Step 6: Update the runbook**

Document:

- availability endpoint smoke command;
- expected three fixed slots and capacity 9;
- the H5 selector path;
- visual evidence directories;
- Android and iOS verification status without substituting browser evidence for a real device.

- [ ] **Step 7: Commit**

```powershell
git add -- `
  infra/scripts/verify-f04-reservation-smoke.ps1 `
  infra/scripts/verify-f08-golden-e2e.cjs `
  docs/15-inspection-service-flow-runbook.md
git commit -m "test(inspection): verify booking time selection"
```

---

## Final Review Gate

- [ ] Inspect `git diff HEAD~7..HEAD --stat` and the complete diff.
- [ ] Confirm no route, Pinia store, inventory table, dependency, payment/refund code, scheduling administration, or unrelated formatting was added.
- [ ] Confirm the original booking fields remain populated after `INS_TIME_SLOT_FULL`.
- [ ] Confirm the selected value is displayed in the original “期望时间” row.
- [ ] Confirm `remainingCount` is derived from non-cancelled reservations and the tenth request is rejected transactionally.
- [ ] Confirm both visual-comparison rounds and focused sheet crops are saved.
- [ ] Stop for user acceptance before any broader service-flow change.
