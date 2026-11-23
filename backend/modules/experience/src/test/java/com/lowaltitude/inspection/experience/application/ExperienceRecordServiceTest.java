package com.lowaltitude.inspection.experience.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.ExperienceRecordException.Error;
import com.lowaltitude.inspection.experience.application.ExperienceRecordRows.RecordRow;
import com.lowaltitude.inspection.experience.application.ExperienceRecordRows.RecordableItemRow;
import com.lowaltitude.inspection.experience.application.ExperienceRecordService.RecordResult;
import com.lowaltitude.inspection.experience.application.ExperienceRecordService.SimulationResultCommand;
import com.lowaltitude.inspection.experience.infrastructure.persistence.ExperienceRecordMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 线上体验记录的规则（S5-4）。mapper 换成内存实现（按 SQL 的条件和 V004 的约束写），时钟固定；
 * 换时间时用同一份数据另起一个服务。SQL 文本由 ExperienceRecordMapperContractTest 检查。
 */
class ExperienceRecordServiceTest {

    /** 北京时间 2026-11-22 10:00。 */
    private static final Instant NOW = Instant.parse("2026-11-22T02:00:00Z");
    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_ACCOUNT = UUID.fromString("11111111-1111-1111-1111-222222222222");
    private static final UUID ROBOT = UUID.fromString("b1000000-0000-4000-8000-000000000001");
    private static final UUID DEMO = UUID.fromString("b1000000-0000-4000-8000-000000000003");
    private static final UUID SOFTWARE = UUID.fromString("b1000000-0000-4000-8000-000000000004");
    private static final UUID DRAFT = UUID.fromString("b1000000-0000-4000-8000-000000000006");
    private static final UUID NO_TEMPLATE = UUID.fromString("b1000000-0000-4000-8000-000000000007");

    private final InMemoryRecords mapper = new InMemoryRecords();
    private final ExperienceRecordService service = serviceAt(NOW);

    ExperienceRecordServiceTest() {
        mapper.items.put(ROBOT, new RecordableItemRow(ROBOT, "SIMULATION", "机器人避障模拟", "PUBLISHED", "ROBOT_OBSTACLE", null));
        mapper.items.put(DEMO, new RecordableItemRow(DEMO, "RECORDED_DEMO", "3 分钟看懂编程机器人", "PUBLISHED", null, null));
        mapper.items.put(SOFTWARE, new RecordableItemRow(SOFTWARE, "SOFTWARE_TRIAL", "图形化编程软件试用", "PUBLISHED", null, 25));
        mapper.items.put(DRAFT, new RecordableItemRow(DRAFT, "SIMULATION", "未发布的示例仿真", "DRAFT", "ROBOT_OBSTACLE", null));
        mapper.items.put(NO_TEMPLATE, new RecordableItemRow(NO_TEMPLATE, "SIMULATION", "没配模板", "PUBLISHED", null, null));
    }

    @Test
    void scoreGivesNinetyForTheLevelsTenForFinishingAndTakesFivePerMistake() {
        assertThat(ExperienceRecordService.score(3, 3, 0)).isEqualTo(100);
        assertThat(ExperienceRecordService.score(3, 3, 1)).isEqualTo(95);
        assertThat(ExperienceRecordService.score(1, 3, 3)).isEqualTo(15);
        assertThat(ExperienceRecordService.score(0, 3, 4)).isZero();
        assertThat(ExperienceRecordService.score(2, 3, 0)).isEqualTo(60);
    }

    @Test
    void aFinishedRunIsStoredWithTheServersScoreAndReplayedByItsKey() {
        UUID key = UUID.randomUUID();
        SimulationResultCommand run = run(3, 1, 156);

        RecordResult first = service.recordSimulation(ACCOUNT, key, run);
        RecordResult again = service.recordSimulation(ACCOUNT, key, run);

        assertThat(first.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.record().recordId()).isEqualTo(first.record().recordId());
        assertThat(first.record().status()).isEqualTo(ExperienceRecordStatus.COMPLETED);
        assertThat(first.record().result().score()).isEqualTo(95);
        assertThat(first.record().result().durationSeconds()).isEqualTo(156);
        assertThat(first.record().expiresAt()).isNull();
        assertThat(first.record().experience().simulationTemplate()).isEqualTo(SimulationTemplate.ROBOT_OBSTACLE);
        assertThat(mapper.records).hasSize(1);
    }

    @Test
    void anUnfinishedRunIsStoredAsUnfinished() {
        RecordResult result = service.recordSimulation(ACCOUNT, UUID.randomUUID(), run(1, 3, 108));

        assertThat(result.record().status()).isEqualTo(ExperienceRecordStatus.UNFINISHED);
        assertThat(result.record().result().levelsCompleted()).isEqualTo(1);
        assertThat(result.record().result().score()).isEqualTo(15);
    }

    @Test
    void theSameKeyWithAnotherResultIsAConflictAndKeysAreKeptPerAccount() {
        UUID key = UUID.randomUUID();
        service.recordSimulation(ACCOUNT, key, run(3, 0, 120));

        assertError(Error.IDEMPOTENCY_CONFLICT, () -> service.recordSimulation(ACCOUNT, key, run(3, 2, 120)));
        assertThat(service.recordSimulation(OTHER_ACCOUNT, key, run(3, 2, 120)).created()).isTrue();
    }

    @Test
    void resultsThatDoNotFitTheTemplateOrTheClockAreRejected() {
        OffsetDateTime end = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        // 关卡数和模板不符
        assertError(Error.RESULT_INVALID, () -> record(new SimulationResultCommand(ROBOT, end.minusMinutes(3), end, 150, 2, 2, 0)));
        // 通关数超过总数、失误为负、用时为 0
        assertError(Error.RESULT_INVALID, () -> record(new SimulationResultCommand(ROBOT, end.minusMinutes(3), end, 150, 4, 3, 0)));
        assertError(Error.RESULT_INVALID, () -> record(new SimulationResultCommand(ROBOT, end.minusMinutes(3), end, 150, 3, 3, -1)));
        assertError(Error.RESULT_INVALID, () -> record(new SimulationResultCommand(ROBOT, end.minusMinutes(3), end, 0, 3, 3, 0)));
        // 用时比起止时间长
        assertError(Error.RESULT_INVALID, () -> record(new SimulationResultCommand(ROBOT, end.minusMinutes(1), end, 120, 3, 3, 0)));
        // 结束时间在将来、开始时间在 30 天以前
        assertError(Error.RESULT_INVALID, () -> record(new SimulationResultCommand(ROBOT, end.plusMinutes(7), end.plusMinutes(10), 60, 3, 3, 0)));
        assertError(Error.RESULT_INVALID, () -> record(new SimulationResultCommand(ROBOT, end.minusDays(31), end.minusDays(31).plusMinutes(3), 60, 3, 3, 0)));
        // 缺字段是参数错误
        assertThatThrownBy(() -> record(new SimulationResultCommand(ROBOT, null, end, 60, 3, 3, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        // 游客在本机玩、几天后登录再补交可以
        assertThat(record(new SimulationResultCommand(ROBOT, end.minusDays(3), end.minusDays(3).plusMinutes(3), 170, 3, 3, 0))
                .created()).isTrue();
        assertThat(mapper.records).hasSize(1);
    }

    @Test
    void onlyPublishedSimulationsWithATemplateTakeResults() {
        assertError(Error.NOT_RECORDABLE, () -> record(run(DEMO)));
        assertError(Error.NOT_RECORDABLE, () -> record(run(SOFTWARE)));
        assertError(Error.NOT_RECORDABLE, () -> record(run(DRAFT)));
        assertError(Error.NOT_RECORDABLE, () -> record(run(NO_TEMPLATE)));
        assertError(Error.NOT_FOUND, () -> record(run(UUID.randomUUID())));
        assertThat(mapper.records).isEmpty();
    }

    @Test
    void aTrialStartsOnceRunsForItsMinutesAndCanBeEndedEarly() {
        RecordResult started = service.startTrial(ACCOUNT, SOFTWARE);
        RecordResult again = serviceAt(NOW.plusSeconds(60)).startTrial(ACCOUNT, SOFTWARE);

        assertThat(started.created()).isTrue();
        assertThat(again.created()).isFalse();
        assertThat(again.record().recordId()).isEqualTo(started.record().recordId());
        assertThat(started.record().status()).isEqualTo(ExperienceRecordStatus.ACTIVE);
        assertThat(started.record().expiresAt()).isEqualTo(started.record().startedAt().plusMinutes(25));
        assertThat(started.record().result()).isNull();
        assertThat(mapper.locks).containsExactly(
                "experience-trial:" + ACCOUNT + ":" + SOFTWARE,
                "experience-trial:" + ACCOUNT + ":" + SOFTWARE);

        ExperienceRecordView ended = serviceAt(NOW.plusSeconds(7 * 60)).endTrial(ACCOUNT, started.record().recordId());
        assertThat(ended.status()).isEqualTo(ExperienceRecordStatus.ENDED);
        assertThat(ended.endedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW.plusSeconds(7 * 60), ZoneOffset.UTC));
        // 重复结束不出错，时间不变。
        assertThat(serviceAt(NOW.plusSeconds(9 * 60)).endTrial(ACCOUNT, started.record().recordId()).endedAt())
                .isEqualTo(ended.endedAt());
        // 结束以后再开始是新的一条。
        assertThat(serviceAt(NOW.plusSeconds(10 * 60)).startTrial(ACCOUNT, SOFTWARE).created()).isTrue();
    }

    @Test
    void aTrialPastItsMinutesReadsExpiredAndStaysUnended() {
        RecordResult started = service.startTrial(ACCOUNT, SOFTWARE);
        ExperienceRecordService later = serviceAt(NOW.plusSeconds(26 * 60));

        assertThat(later.readRecord(ACCOUNT, started.record().recordId()).status())
                .isEqualTo(ExperienceRecordStatus.EXPIRED);
        ExperienceRecordView ended = later.endTrial(ACCOUNT, started.record().recordId());
        assertThat(ended.status()).isEqualTo(ExperienceRecordStatus.EXPIRED);
        assertThat(ended.endedAt()).isNull();
        assertThat(later.startTrial(ACCOUNT, SOFTWARE).created()).isTrue();
    }

    @Test
    void onlyTrialItemsWithMinutesStartAndOnlyTrialsEnd() {
        assertError(Error.NOT_RECORDABLE, () -> service.startTrial(ACCOUNT, ROBOT));
        assertError(Error.NOT_RECORDABLE, () -> service.startTrial(ACCOUNT, DEMO));
        assertError(Error.NOT_FOUND, () -> service.startTrial(ACCOUNT, UUID.randomUUID()));
        UUID run = record(run(3, 0, 100)).record().recordId();
        assertError(Error.NOT_TRIAL, () -> service.endTrial(ACCOUNT, run));
    }

    @Test
    void anotherAccountsRecordIsNotFoundAndListsAreTheAccountsOwnNewestFirst() {
        UUID older = record(new SimulationResultCommand(ROBOT,
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(1),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(1).plusMinutes(3), 150, 3, 3, 0))
                .record().recordId();
        UUID trial = service.startTrial(ACCOUNT, SOFTWARE).record().recordId();
        service.startTrial(OTHER_ACCOUNT, SOFTWARE);

        assertError(Error.NOT_FOUND, () -> service.readRecord(OTHER_ACCOUNT, older));
        assertError(Error.NOT_FOUND, () -> service.endTrial(OTHER_ACCOUNT, trial));

        ExperienceRecordPageView page = service.listRecords(ACCOUNT, null, 1, 1);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.items()).extracting(ExperienceRecordView::recordId).containsExactly(trial);
        assertThat(service.listRecords(ACCOUNT, null, 2, 1).items())
                .extracting(ExperienceRecordView::recordId).containsExactly(older);
        assertThat(service.listRecords(ACCOUNT, ROBOT, 1, 20).items())
                .extracting(ExperienceRecordView::recordId).containsExactly(older);
        assertThatThrownBy(() -> service.listRecords(ACCOUNT, null, 0, 20)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listRecords(ACCOUNT, null, 1, 51)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 工具 ----

    private ExperienceRecordService serviceAt(Instant instant) {
        return new ExperienceRecordService(
                mapper, TransactionOperations.withoutTransaction(), Clock.fixed(instant, ZoneOffset.UTC));
    }

    private RecordResult record(SimulationResultCommand command) {
        return service.recordSimulation(ACCOUNT, UUID.randomUUID(), command);
    }

    private static SimulationResultCommand run(int levelsCompleted, int mistakes, int seconds) {
        OffsetDateTime end = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusSeconds(10);
        return new SimulationResultCommand(ROBOT, end.minusSeconds(seconds + 20), end, seconds, levelsCompleted, 3, mistakes);
    }

    private static SimulationResultCommand run(UUID experienceId) {
        OffsetDateTime end = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
        return new SimulationResultCommand(experienceId, end.minusMinutes(3), end, 150, 3, 3, 0);
    }

    private static void assertError(Error error, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ExperienceRecordException.class,
                        exception -> assertThat(exception.error()).isEqualTo(error));
    }

    /** 按 ExperienceRecordMapper 的 SQL 条件写的内存实现。 */
    private static final class InMemoryRecords implements ExperienceRecordMapper {
        final Map<UUID, RecordableItemRow> items = new HashMap<>();
        final List<Stored> records = new ArrayList<>();
        final List<String> locks = new ArrayList<>();

        private static final class Stored {
            UUID id;
            UUID accountId;
            UUID experienceId;
            String kind;
            OffsetDateTime startedAt;
            OffsetDateTime endedAt;
            OffsetDateTime expiresAt;
            String outcome;
            Integer score;
            Integer durationSeconds;
            Integer levelsCompleted;
            Integer levelsTotal;
            Integer mistakes;
            UUID idempotencyKey;
            String requestHash;
        }

        private RecordRow row(Stored stored) {
            RecordableItemRow item = items.get(stored.experienceId);
            return new RecordRow(stored.id, item.experienceId(), item.type(), item.title(), null, null,
                    item.simulationTemplate(), stored.kind, stored.startedAt, stored.endedAt, stored.expiresAt,
                    stored.outcome, stored.score, stored.durationSeconds, stored.levelsCompleted,
                    stored.levelsTotal, stored.mistakes, stored.requestHash);
        }

        @Override
        public RecordableItemRow findItem(UUID experienceId) {
            return items.get(experienceId);
        }

        @Override
        public RecordRow findRecordByKey(UUID accountId, UUID idempotencyKey) {
            return records.stream()
                    .filter(r -> r.accountId.equals(accountId) && idempotencyKey.equals(r.idempotencyKey))
                    .findFirst().map(this::row).orElse(null);
        }

        @Override
        public int insertSimulationRecord(UUID id, UUID accountId, UUID experienceId, OffsetDateTime startedAt,
                OffsetDateTime endedAt, String outcome, int score, int durationSeconds, int levelsCompleted,
                int levelsTotal, int mistakes, UUID idempotencyKey, String requestHash, OffsetDateTime at) {
            if (findRecordByKey(accountId, idempotencyKey) != null) {
                return 0;
            }
            // V004 的约束
            assertThat(endedAt).isAfterOrEqualTo(startedAt);
            assertThat(levelsCompleted).isLessThanOrEqualTo(levelsTotal);
            assertThat("COMPLETED".equals(outcome)).isEqualTo(levelsCompleted == levelsTotal);
            Stored stored = new Stored();
            stored.id = id;
            stored.accountId = accountId;
            stored.experienceId = experienceId;
            stored.kind = "SIMULATION";
            stored.startedAt = startedAt;
            stored.endedAt = endedAt;
            stored.outcome = outcome;
            stored.score = score;
            stored.durationSeconds = durationSeconds;
            stored.levelsCompleted = levelsCompleted;
            stored.levelsTotal = levelsTotal;
            stored.mistakes = mistakes;
            stored.idempotencyKey = idempotencyKey;
            stored.requestHash = requestHash;
            records.add(stored);
            return 1;
        }

        @Override
        public Integer lockTrialStart(String lockKey) {
            locks.add(lockKey);
            return 1;
        }

        @Override
        public RecordRow findActiveTrial(UUID accountId, UUID experienceId, OffsetDateTime now) {
            return records.stream()
                    .filter(r -> r.accountId.equals(accountId) && r.experienceId.equals(experienceId))
                    .filter(r -> "TRIAL".equals(r.kind) && r.endedAt == null && r.expiresAt.isAfter(now))
                    .max(Comparator.comparing(r -> r.startedAt))
                    .map(this::row).orElse(null);
        }

        @Override
        public int insertTrial(UUID id, UUID accountId, UUID experienceId, OffsetDateTime at, OffsetDateTime expiresAt) {
            assertThat(expiresAt).isAfter(at);
            Stored stored = new Stored();
            stored.id = id;
            stored.accountId = accountId;
            stored.experienceId = experienceId;
            stored.kind = "TRIAL";
            stored.startedAt = at;
            stored.expiresAt = expiresAt;
            records.add(stored);
            return 1;
        }

        @Override
        public RecordRow findRecord(UUID accountId, UUID recordId) {
            return records.stream()
                    .filter(r -> r.accountId.equals(accountId) && r.id.equals(recordId))
                    .findFirst().map(this::row).orElse(null);
        }

        @Override
        public RecordRow lockRecord(UUID accountId, UUID recordId) {
            return findRecord(accountId, recordId);
        }

        @Override
        public int endTrial(UUID accountId, UUID recordId, OffsetDateTime at) {
            for (Stored r : records) {
                if (r.accountId.equals(accountId) && r.id.equals(recordId) && "TRIAL".equals(r.kind)
                        && r.endedAt == null && r.expiresAt.isAfter(at)) {
                    r.endedAt = at;
                    return 1;
                }
            }
            return 0;
        }

        private List<Stored> mine(UUID accountId, String experienceId) {
            return records.stream()
                    .filter(r -> r.accountId.equals(accountId))
                    .filter(r -> "ALL".equals(experienceId) || Objects.equals(r.experienceId.toString(), experienceId))
                    .sorted(Comparator.comparing((Stored r) -> r.startedAt).reversed())
                    .toList();
        }

        @Override
        public long countRecords(UUID accountId, String experienceId) {
            return mine(accountId, experienceId).size();
        }

        @Override
        public List<RecordRow> findRecords(UUID accountId, String experienceId, int limit, int offset) {
            return mine(accountId, experienceId).stream().skip(offset).limit(limit).map(this::row).toList();
        }
    }
}
