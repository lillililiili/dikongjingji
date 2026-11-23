package com.lowaltitude.inspection.experience.application;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.ExperienceRecordException.Error;
import com.lowaltitude.inspection.experience.application.ExperienceRecordRows.RecordRow;
import com.lowaltitude.inspection.experience.application.ExperienceRecordRows.RecordableItemRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.ExperienceRecordMapper;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 线上体验记录（S5-4，契约 contracts/openapi-experience.yaml 1.3.0）。只记登录账号自己的。
 *
 * <ul>
 *   <li>仿真成绩：仿真在 App 里跑，App 报关卡、失误和起止时间；服务端校验范围（关卡数要和模板一致、
 *       用时不超过起止时间、时间不在将来也不太久以前），按规则算分，不收 App 算的分。
 *       同一个幂等键重放返回同一条；游客在本机玩、登录后补交也按键去重。</li>
 *   <li>试用、试听：开始即记，到期时间 = 开始 + 项目的试用时长；还在试用中的再点开始返回同一条。
 *       可以提前结束；到期按时间算，不落库。</li>
 * </ul>
 */
public final class ExperienceRecordService {

    private static final int MAX_PAGE_SIZE = 50;
    /** App 的时钟可能比服务器快一点。 */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    /** 游客在本机玩过、登录后补交，最多补交 30 天内的。 */
    static final Duration MAX_RESULT_AGE = Duration.ofDays(30);
    /** 一次仿真最长 2 小时（迁移里 duration_seconds 的上限）。 */
    static final int MAX_DURATION_SECONDS = 7200;
    static final int MAX_MISTAKES = 999;

    private final ExperienceRecordMapper mapper;
    private final TransactionOperations transactions;
    private final Clock clock;

    public ExperienceRecordService(ExperienceRecordMapper mapper, TransactionOperations transactions, Clock clock) {
        this.mapper = mapper;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * 成绩分数：通关的关卡按比例给 90 分，全部通关再加 10 分，每次失误扣 5 分，最低 0 分。
     * App 显示游客成绩时用同一条规则（features/experience/domain/simulation-score.ts）。
     */
    static int score(int levelsCompleted, int levelsTotal, int mistakes) {
        int base = Math.round(90f * levelsCompleted / levelsTotal);
        int bonus = levelsCompleted == levelsTotal ? 10 : 0;
        return Math.max(0, Math.min(100, base + bonus - 5 * mistakes));
    }

    public RecordResult recordSimulation(UUID accountId, UUID idempotencyKey, SimulationResultCommand command) {
        if (idempotencyKey == null || command == null || command.experienceId() == null
                || command.startedAt() == null || command.endedAt() == null || command.durationSeconds() == null
                || command.levelsCompleted() == null || command.levelsTotal() == null || command.mistakes() == null) {
            throw new IllegalArgumentException("simulation result is incomplete");
        }
        String requestHash = hash(command);
        RecordRow replayed = mapper.findRecordByKey(accountId, idempotencyKey);
        if (replayed != null) {
            return replay(replayed, requestHash);
        }
        RecordableItemRow item = requireItem(command.experienceId());
        SimulationTemplate template = templateOf(item);
        if (template == null) {
            throw new ExperienceRecordException(Error.NOT_RECORDABLE);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        validate(command, template, now);
        int levelsCompleted = command.levelsCompleted();
        int levelsTotal = command.levelsTotal();
        String outcome = levelsCompleted == levelsTotal
                ? ExperienceRecordStatus.COMPLETED.name()
                : ExperienceRecordStatus.UNFINISHED.name();
        int inserted = mapper.insertSimulationRecord(
                UUID.randomUUID(), accountId, item.experienceId(),
                command.startedAt(), command.endedAt(), outcome,
                score(levelsCompleted, levelsTotal, command.mistakes()), command.durationSeconds(),
                levelsCompleted, levelsTotal, command.mistakes(),
                idempotencyKey, requestHash, now);
        RecordRow stored = mapper.findRecordByKey(accountId, idempotencyKey);
        if (stored == null) {
            throw new IllegalStateException("simulation record was not stored");
        }
        if (inserted == 0) {
            // 同一个键的另一次请求刚好先插进去了。
            return replay(stored, requestHash);
        }
        return new RecordResult(toView(stored, now), true);
    }

    public RecordResult startTrial(UUID accountId, UUID experienceId) {
        if (experienceId == null) {
            throw new IllegalArgumentException("experienceId is required");
        }
        RecordableItemRow item = requireItem(experienceId);
        ExperienceType type = ExperienceType.valueOf(item.type());
        if (type != ExperienceType.SOFTWARE_TRIAL && type != ExperienceType.COURSE_TRIAL
                || item.trialMinutes() == null || item.trialMinutes() <= 0) {
            throw new ExperienceRecordException(Error.NOT_RECORDABLE);
        }
        return transactions.execute(status -> {
            mapper.lockTrialStart("experience-trial:" + accountId + ":" + experienceId);
            OffsetDateTime now = OffsetDateTime.now(clock);
            RecordRow active = mapper.findActiveTrial(accountId, experienceId, now);
            if (active != null) {
                return new RecordResult(toView(active, now), false);
            }
            UUID recordId = UUID.randomUUID();
            mapper.insertTrial(recordId, accountId, experienceId, now, now.plusMinutes(item.trialMinutes()));
            return new RecordResult(toView(mapper.findRecord(accountId, recordId), now), true);
        });
    }

    /** 提前结束试用。已结束、已到期的原样返回（重复点不出错）。 */
    public ExperienceRecordView endTrial(UUID accountId, UUID recordId) {
        return transactions.execute(status -> {
            RecordRow row = mapper.lockRecord(accountId, recordId);
            if (row == null) {
                throw new ExperienceRecordException(Error.NOT_FOUND);
            }
            if (!"TRIAL".equals(row.kind())) {
                throw new ExperienceRecordException(Error.NOT_TRIAL);
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (mapper.endTrial(accountId, recordId, now) == 0) {
                return toView(row, now);
            }
            return toView(mapper.findRecord(accountId, recordId), now);
        });
    }

    public ExperienceRecordView readRecord(UUID accountId, UUID recordId) {
        RecordRow row = mapper.findRecord(accountId, recordId);
        if (row == null) {
            throw new ExperienceRecordException(Error.NOT_FOUND);
        }
        return toView(row, OffsetDateTime.now(clock));
    }

    /** 本人的记录，最近开始的在前；experienceId 为空表示所有项目。 */
    public ExperienceRecordPageView listRecords(UUID accountId, UUID experienceId, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page or size is invalid");
        }
        String filter = experienceId == null ? "ALL" : experienceId.toString();
        long total = mapper.countRecords(accountId, filter);
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<ExperienceRecordView> items = mapper.findRecords(accountId, filter, size, (page - 1) * size).stream()
                .map(row -> toView(row, now))
                .toList();
        return new ExperienceRecordPageView(page, size, total, (long) page * size < total, items);
    }

    // ---- 规则 ----

    private RecordableItemRow requireItem(UUID experienceId) {
        RecordableItemRow item = mapper.findItem(experienceId);
        if (item == null || "DELETED".equals(item.status())) {
            throw new ExperienceRecordException(Error.NOT_FOUND);
        }
        if (!"PUBLISHED".equals(item.status())) {
            throw new ExperienceRecordException(Error.NOT_RECORDABLE);
        }
        return item;
    }

    private static SimulationTemplate templateOf(RecordableItemRow item) {
        if (!ExperienceType.SIMULATION.name().equals(item.type()) || item.simulationTemplate() == null) {
            return null;
        }
        return SimulationTemplate.valueOf(item.simulationTemplate());
    }

    private static void validate(SimulationResultCommand command, SimulationTemplate template, OffsetDateTime now) {
        int levelsTotal = command.levelsTotal();
        int levelsCompleted = command.levelsCompleted();
        int mistakes = command.mistakes();
        int duration = command.durationSeconds();
        long spanSeconds = Duration.between(command.startedAt(), command.endedAt()).getSeconds();
        boolean valid = levelsTotal == template.levels()
                && levelsCompleted >= 0 && levelsCompleted <= levelsTotal
                && mistakes >= 0 && mistakes <= MAX_MISTAKES
                && duration >= 1 && duration <= MAX_DURATION_SECONDS
                && spanSeconds >= 0
                // 用时只算在页面上的时间，可以比起止时间短，不能比它长（留几秒取整的余量）。
                && duration <= spanSeconds + 5
                && !command.endedAt().isAfter(now.plus(CLOCK_SKEW))
                && !command.startedAt().isBefore(now.minus(MAX_RESULT_AGE));
        if (!valid) {
            throw new ExperienceRecordException(Error.RESULT_INVALID);
        }
    }

    private static RecordResult replay(RecordRow row, String requestHash) {
        if (!requestHash.equals(row.requestHash())) {
            throw new ExperienceRecordException(Error.IDEMPOTENCY_CONFLICT);
        }
        return new RecordResult(toView(row, row.endedAt()), false);
    }

    static ExperienceRecordStatus statusOf(RecordRow row, OffsetDateTime now) {
        if ("SIMULATION".equals(row.kind())) {
            return ExperienceRecordStatus.valueOf(row.outcome());
        }
        if (row.endedAt() != null) {
            return ExperienceRecordStatus.ENDED;
        }
        return now.isBefore(row.expiresAt()) ? ExperienceRecordStatus.ACTIVE : ExperienceRecordStatus.EXPIRED;
    }

    private static ExperienceRecordView toView(RecordRow row, OffsetDateTime now) {
        SimulationResultView result = "SIMULATION".equals(row.kind())
                ? new SimulationResultView(
                        row.score(), row.durationSeconds(), row.levelsCompleted(), row.levelsTotal(), row.mistakes())
                : null;
        return new ExperienceRecordView(
                row.recordId(),
                new ExperienceRecordItemView(
                        row.experienceId(),
                        ExperienceType.valueOf(row.type()),
                        row.title(),
                        secureMedia(row.coverUrl(), row.title()),
                        row.productId(),
                        row.simulationTemplate() == null ? null : SimulationTemplate.valueOf(row.simulationTemplate())),
                statusOf(row, now),
                row.startedAt(),
                row.endedAt(),
                row.expiresAt(),
                result);
    }

    private static ExperienceMediaView secureMedia(String url, String alt) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    ? new ExperienceMediaView(uri.toString(), alt)
                    : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /** 同一个幂等键重放时比对内容：时间统一到秒、按 UTC 写。 */
    static String hash(SimulationResultCommand command) {
        String canonical = String.join("|",
                command.experienceId().toString(),
                command.startedAt().toInstant().truncatedTo(ChronoUnit.SECONDS).toString(),
                command.endedAt().toInstant().truncatedTo(ChronoUnit.SECONDS).toString(),
                command.durationSeconds().toString(),
                command.levelsCompleted().toString(),
                command.levelsTotal().toString(),
                command.mistakes().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** created：这一次新建了记录（201）；否则是重放或者已有的试用（200）。 */
    public record RecordResult(ExperienceRecordView record, boolean created) {
    }

    public record SimulationResultCommand(
            UUID experienceId,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            Integer durationSeconds,
            Integer levelsCompleted,
            Integer levelsTotal,
            Integer mistakes
    ) {
    }
}

record ExperienceRecordItemView(
        UUID experienceId,
        ExperienceType type,
        String title,
        ExperienceMediaView coverImage,
        UUID productId,
        SimulationTemplate simulationTemplate
) {
}

record SimulationResultView(
        int score,
        int durationSeconds,
        int levelsCompleted,
        int levelsTotal,
        int mistakes
) {
}

record ExperienceRecordView(
        UUID recordId,
        ExperienceRecordItemView experience,
        ExperienceRecordStatus status,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        OffsetDateTime expiresAt,
        SimulationResultView result
) {
}

record ExperienceRecordPageView(
        int page,
        int size,
        long total,
        boolean hasMore,
        List<ExperienceRecordView> items
) {
}
