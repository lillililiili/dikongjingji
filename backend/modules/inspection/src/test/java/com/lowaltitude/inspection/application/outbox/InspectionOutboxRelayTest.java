package com.lowaltitude.inspection.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.application.outbox.InspectionOutboxRows.EventContextRow;
import com.lowaltitude.inspection.application.outbox.InspectionOutboxRows.PendingEventRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionOutboxMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

class InspectionOutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-11-17T04:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RESERVATION = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final UUID ORDER = UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID VISIT = UUID.fromString("61000000-0000-4000-8000-000000000001");

    private final FakeStore store = new FakeStore();
    private final List<InspectionProgressEvent> delivered = new ArrayList<>();
    private RuntimeException sinkFailure;
    private final InspectionOutboxRelay relay = new InspectionOutboxRelay(
            store,
            event -> {
                if (sinkFailure != null && event.eventType().equals("INSPECTION_VISIT_SCHEDULED")) {
                    throw sinkFailure;
                }
                delivered.add(event);
            },
            TransactionOperations.withoutTransaction(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void handsEachPendingEventToTheSubscriberWithItsContextAndMarksItPublished() {
        UUID first = store.add("SERVICE_VISIT", VISIT, "INSPECTION_VISIT_SCHEDULED", 0);
        UUID second = store.add("SERVICE_VISIT", VISIT, "INSPECTION_VISIT_SERVICE_STARTED", 0);

        assertThat(relay.relayPending()).isEqualTo(2);

        assertThat(delivered).extracting(InspectionProgressEvent::eventId).containsExactly(first, second);
        assertThat(delivered.get(0)).isEqualTo(new InspectionProgressEvent(
                first, "INSPECTION_VISIT_SCHEDULED", NOW.minusSeconds(30), CUSTOMER, RESERVATION, "IR1",
                "光伏电站巡检服务", null, ORDER, VISIT, 2, Instant.parse("2026-11-26T01:00:00Z")));
        assertThat(store.published).containsExactly(first, second);
        assertThat(store.failed).isEmpty();
    }

    // 一条失败不拖住后面的；失败的记下原因和重试时间，不标成已发布。
    @Test
    void aFailingEventIsRetriedLaterAndDoesNotBlockTheRest() {
        sinkFailure = new IllegalStateException("inbox is down");
        UUID failing = store.add("SERVICE_VISIT", VISIT, "INSPECTION_VISIT_SCHEDULED", 3);
        UUID fine = store.add("SERVICE_VISIT", VISIT, "INSPECTION_VISIT_SERVICE_STARTED", 0);

        assertThat(relay.relayPending()).isEqualTo(1);

        assertThat(store.published).containsExactly(fine);
        assertThat(store.failed).containsOnlyKeys(failing);
        assertThat(store.failed.get(failing).error()).isEqualTo("IllegalStateException: inbox is down");
        // 已经试过 3 次：5 秒 × 2³。
        assertThat(store.failed.get(failing).nextAttemptAt()).isEqualTo(NOW.plusSeconds(40));
    }

    @Test
    void anEventWhoseAggregateCannotBeFoundFailsInsteadOfBeingDropped() {
        UUID orphan = store.add("SERVICE_VISIT", UUID.randomUUID(), "INSPECTION_VISIT_SCHEDULED", 0);
        UUID unknownKind = store.add("SOMETHING_ELSE", VISIT, "SOMETHING_HAPPENED", 0);

        assertThat(relay.relayPending()).isZero();

        assertThat(delivered).isEmpty();
        assertThat(store.published).isEmpty();
        assertThat(store.failed).containsOnlyKeys(orphan, unknownKind);
    }

    // 另一个实例正在处理（或已经处理完）的事件，这里锁不到，直接跳过。
    @Test
    void anEventLockedOrAlreadyPublishedElsewhereIsSkipped() {
        UUID taken = store.add("SERVICE_VISIT", VISIT, "INSPECTION_VISIT_SCHEDULED", 0);
        store.lockedElsewhere.add(taken);

        assertThat(relay.relayPending()).isZero();

        assertThat(delivered).isEmpty();
        assertThat(store.published).isEmpty();
        assertThat(store.failed).isEmpty();
    }

    @Test
    void retryDelayDoublesFromFiveSecondsUpToTenMinutes() {
        assertThat(InspectionOutboxRelay.retryDelay(0)).isEqualTo(Duration.ofSeconds(5));
        assertThat(InspectionOutboxRelay.retryDelay(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(InspectionOutboxRelay.retryDelay(6)).isEqualTo(Duration.ofSeconds(320));
        assertThat(InspectionOutboxRelay.retryDelay(7)).isEqualTo(Duration.ofMinutes(10));
        assertThat(InspectionOutboxRelay.retryDelay(500)).isEqualTo(Duration.ofMinutes(10));
        assertThat(InspectionOutboxRelay.retryDelay(-1)).isEqualTo(Duration.ofSeconds(5));
    }

    // 上下文查询是隐私边界：只取标识和服务名，不碰加密保存的联系人、地址、备注。
    @Test
    void contextQueriesReadOnlyIdentifiersAndTheServiceTitle() {
        List<Method> contextQueries = Arrays.stream(InspectionOutboxMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().endsWith("Context"))
                .toList();

        assertThat(contextQueries).hasSize(5);
        for (Method query : contextQueries) {
            String sql = String.join("\n", query.getAnnotation(Select.class).value());
            assertThat(sql)
                    .contains("r.customer_id")
                    .contains("r.service_title_snapshot service_title")
                    .contains("#{aggregateId}")
                    .doesNotContain("ciphertext")
                    .doesNotContain("location")
                    .doesNotContain("${")
                    .doesNotContainPattern("[0-9a-z_)]FROM ")
                    .doesNotContainPattern(",\\s*FROM ");
        }
    }

    @Test
    void pendingEventsAreClaimedWithSkipLockedAndOnlyUnpublishedRowsAreUpdated() throws Exception {
        String lock = String.join("\n", InspectionOutboxMapper.class
                .getMethod("lockPending", UUID.class).getAnnotation(Select.class).value());
        assertThat(lock).contains("published_at IS NULL").contains("FOR UPDATE SKIP LOCKED");

        String pending = String.join("\n", InspectionOutboxMapper.class
                .getMethod("findPendingIds", Instant.class, int.class).getAnnotation(Select.class).value());
        assertThat(pending)
                .contains("published_at IS NULL")
                .contains("next_attempt_at <= #{now}")
                .contains("ORDER BY occurred_at ASC, id ASC")
                .contains("LIMIT #{limit}");

        for (String name : List.of("markPublished", "markFailed")) {
            Method method = Arrays.stream(InspectionOutboxMapper.class.getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(name))
                    .findFirst()
                    .orElseThrow();
            assertThat(String.join("\n", method.getAnnotation(Update.class).value()))
                    .contains("WHERE id = #{eventId}")
                    .contains("published_at IS NULL");
        }
    }

    private record Failure(String error, Instant nextAttemptAt) {
    }

    private static final class FakeStore implements InspectionOutboxStore {
        final Map<UUID, PendingEventRow> pending = new LinkedHashMap<>();
        final List<UUID> published = new ArrayList<>();
        final Map<UUID, Failure> failed = new LinkedHashMap<>();
        final List<UUID> lockedElsewhere = new ArrayList<>();

        UUID add(String aggregateType, UUID aggregateId, String eventType, int attempts) {
            UUID id = UUID.randomUUID();
            pending.put(id, new PendingEventRow(id, aggregateType, aggregateId, eventType, NOW.minusSeconds(30), attempts));
            return id;
        }

        @Override
        public List<UUID> findPendingIds(Instant now, int limit) {
            return pending.keySet().stream().limit(limit).toList();
        }

        @Override
        public Optional<PendingEventRow> lockPending(UUID eventId) {
            return lockedElsewhere.contains(eventId) ? Optional.empty() : Optional.ofNullable(pending.get(eventId));
        }

        @Override
        public Optional<EventContextRow> findContext(String aggregateType, UUID aggregateId) {
            return "SERVICE_VISIT".equals(aggregateType) && VISIT.equals(aggregateId)
                    ? Optional.of(new EventContextRow(
                            CUSTOMER, RESERVATION, "IR1", "光伏电站巡检服务", null, ORDER, VISIT, 2,
                            Instant.parse("2026-11-26T01:00:00Z")))
                    : Optional.empty();
        }

        @Override
        public void markPublished(UUID eventId, Instant at) {
            published.add(eventId);
        }

        @Override
        public void markFailed(UUID eventId, String error, Instant nextAttemptAt) {
            failed.put(eventId, new Failure(error, nextAttemptAt));
        }
    }
}
