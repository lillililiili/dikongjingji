package com.lowaltitude.inspection.proservice.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.ops.common.OpsChangeView;
import com.lowaltitude.inspection.ops.common.OpsError;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.common.OpsText;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProviderProfileMapper;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProviderProfileMapper.ProfileRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服务商主页资料（S4-4，契约 openapi-proservice.yaml 1.2.0、openapi-ops-proservice.yaml 1.1.0）：
 * 服务商在工作台提交（经营主体、服务方式、授权有效期、可交付清单、本地团队标签），平台运营审核通过后 App 主页显示；
 * 驳回时主页仍显示上一份通过的。只有有在用服务商资料的机构能提交。提交和审核都记一条改动记录。
 */
public class ProviderProfileService {

    public static final int MAX_LEGAL_ENTITY = 60;
    public static final int MAX_SERVICE_MODES = 40;
    public static final int MAX_DELIVERABLES = 8;
    public static final int MAX_DELIVERABLE = 12;
    public static final int MAX_LOCAL_TEAM = 12;
    public static final int MAX_REJECT_REASON = 200;
    public static final int MAX_PAGE_SIZE = 50;
    public static final int CHANGE_HISTORY_LIMIT = 50;
    private static final String SEPARATOR = "\n";
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final ProviderProfileMapper mapper;
    private final ProServicePorts.ProviderProfiles providers;
    private final Clock clock;

    public ProviderProfileService(ProviderProfileMapper mapper, ProServicePorts.ProviderProfiles providers) {
        this(mapper, providers, Clock.systemUTC());
    }

    ProviderProfileService(ProviderProfileMapper mapper, ProServicePorts.ProviderProfiles providers, Clock clock) {
        this.mapper = mapper;
        this.providers = providers;
        this.clock = clock;
    }

    /** 工作台和运营端用的错误（经 OpsException 交给统一的异常处理）。 */
    public enum ProfileError implements OpsError {
        NOT_PROVIDER(403, "COMMON_FORBIDDEN", "这家机构没有服务商资料"),
        SUBMIT_VERSION_CONFLICT(409, "PRO_PROVIDER_PROFILE_VERSION_CONFLICT", "资料已有变化，请刷新后重试"),
        NOT_FOUND(404, OpsError.NOT_FOUND, "资源不存在"),
        REVIEW_VERSION_CONFLICT(409, OpsError.VERSION_CONFLICT, "数据已被他人修改，请刷新后重试"),
        NOTHING_TO_REVIEW(409, "OPS_STATUS_UNCHANGED", "没有待审核的资料");

        private final int status;
        private final String code;
        private final String message;

        ProfileError(int status, String code, String message) {
            this.status = status;
            this.code = code;
            this.message = message;
        }

        @Override
        public int httpStatus() {
            return status;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public String message() {
            return message;
        }
    }

    public enum ReviewStatus {
        NONE,
        PENDING,
        APPROVED,
        REJECTED
    }

    public enum OpsStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    public record ProfileView(String legalEntity, String serviceModes, LocalDate authorizationUntil,
                              List<String> deliverables, String localTeamLabel) {
    }

    /** 工作台看到的（契约 ProviderProfileReview）。 */
    public record ReviewView(UUID providerId, String providerName, ReviewStatus status, ProfileView published,
                             ProfileView submitted, String rejectReason, OffsetDateTime submittedAt,
                             OffsetDateTime reviewedAt, int version) {
    }

    /** 运营端列表的一行（契约 OpsProviderProfileRow）。 */
    public record OpsRowView(UUID providerId, String providerName, OpsStatus status, OffsetDateTime submittedAt,
                             OffsetDateTime reviewedAt) {
    }

    public record OpsPageView(int page, int size, long total, List<OpsRowView> items) {
    }

    /** 运营端详情（契约 OpsProviderProfile）。 */
    public record OpsDetailView(UUID providerId, String providerName, OpsStatus status, ProfileView published,
                                ProfileView submitted, String rejectReason, OffsetDateTime submittedAt,
                                OffsetDateTime reviewedAt, int version, List<OpsChangeView> changes) {
    }

    public record ProfileDraft(String legalEntity, String serviceModes, String authorizationUntil,
                               List<String> deliverables, String localTeamLabel, Integer version) {
    }

    // ---- App 主页（公开） ----

    @Transactional(readOnly = true)
    public Optional<ProfileView> published(UUID providerId) {
        ProfileRow row = providerId == null ? null : mapper.find(providerId);
        return row == null ? Optional.empty() : Optional.ofNullable(published(row));
    }

    // ---- 服务商工作台 ----

    @Transactional(readOnly = true)
    public ReviewView mine(UUID providerId) {
        ProServicePorts.Provider provider = requireProvider(providerId);
        return review(provider, mapper.find(providerId));
    }

    @Transactional
    public ReviewView submit(UUID providerId, UUID operatorId, String operatorName, ProfileDraft draft) {
        ProServicePorts.Provider provider = requireProvider(providerId);
        if (draft == null || draft.version() == null || draft.version() < 0) {
            throw new IllegalArgumentException("version is required");
        }
        String operator = OpsText.operatorName(operatorName);
        Content content = normalize(draft);
        OffsetDateTime now = now();
        ProfileRow current = mapper.lock(providerId);
        if (current == null) {
            if (draft.version() != 0 || mapper.insert(providerId, content.legalEntity(), content.serviceModes(),
                    content.authorizationUntil(), content.deliverablesText(), content.localTeamLabel(), operatorId,
                    now) != 1) {
                throw new OpsException(ProfileError.SUBMIT_VERSION_CONFLICT);
            }
        } else {
            if (current.version() != draft.version()) {
                throw new OpsException(ProfileError.SUBMIT_VERSION_CONFLICT);
            }
            if ("PENDING".equals(current.status()) && content.sameAsSubmitted(current)) {
                return review(provider, current);
            }
            if (mapper.resubmit(providerId, content.legalEntity(), content.serviceModes(),
                    content.authorizationUntil(), content.deliverablesText(), content.localTeamLabel(), operatorId,
                    draft.version(), now) != 1) {
                throw new OpsException(ProfileError.SUBMIT_VERSION_CONFLICT);
            }
        }
        mapper.insertChange(providerId, "SUBMIT", "提交主页资料去审核（经营主体“" + content.legalEntity() + "”）",
                operatorId, operator, now);
        return review(provider, mapper.find(providerId));
    }

    // ---- 运营端审核 ----

    @Transactional(readOnly = true)
    public OpsPageView list(String status, int page, int size) {
        OpsText.page(page, size, MAX_PAGE_SIZE);
        OpsStatus filter = OpsText.optionalEnum(OpsStatus.class, status, "status");
        String code = filter == null ? null : filter.name();
        long total = mapper.count(code);
        List<ProfileRow> rows = mapper.findPage(code, size, (page - 1) * size);
        Map<UUID, String> names = names(rows.stream().map(ProfileRow::providerId).toList());
        return new OpsPageView(page, size, total, rows.stream()
                .map(row -> new OpsRowView(row.providerId(), names.getOrDefault(row.providerId(), "服务商"),
                        OpsStatus.valueOf(row.status()), row.submittedAt(), row.reviewedAt()))
                .toList());
    }

    @Transactional(readOnly = true)
    public OpsDetailView detail(UUID providerId) {
        ProfileRow row = providerId == null ? null : mapper.find(providerId);
        if (row == null) {
            throw new OpsException(ProfileError.NOT_FOUND);
        }
        return opsDetail(row);
    }

    @Transactional
    public OpsDetailView review(UUID providerId, String decision, String reasonText, Integer version,
                                UUID operatorId, String operatorName) {
        String operator = OpsText.operatorName(operatorName);
        if (version == null || version < 0) {
            throw new IllegalArgumentException("version is required");
        }
        boolean approve = switch (decision == null ? "" : decision.trim()) {
            case "APPROVE" -> true;
            case "REJECT" -> false;
            default -> throw new IllegalArgumentException("decision is invalid");
        };
        String reason = approve ? null : OpsText.required(reasonText, MAX_REJECT_REASON, "reason");
        ProfileRow row = providerId == null ? null : mapper.lock(providerId);
        if (row == null) {
            throw new OpsException(ProfileError.NOT_FOUND);
        }
        if (row.version() != version) {
            throw new OpsException(ProfileError.REVIEW_VERSION_CONFLICT);
        }
        if (!"PENDING".equals(row.status())) {
            throw new OpsException(ProfileError.NOTHING_TO_REVIEW);
        }
        OffsetDateTime now = now();
        int changed = approve ? mapper.approve(providerId, operatorId, version, now)
                : mapper.reject(providerId, reason, operatorId, version, now);
        if (changed != 1) {
            throw new OpsException(ProfileError.REVIEW_VERSION_CONFLICT);
        }
        mapper.insertChange(providerId, approve ? "APPROVE" : "REJECT",
                approve ? "审核通过，主页显示这一份资料" : "驳回：" + reason, operatorId, operator, now);
        return opsDetail(mapper.find(providerId));
    }

    // ---- 转换与校验 ----

    private OpsDetailView opsDetail(ProfileRow row) {
        String name = names(List.of(row.providerId())).getOrDefault(row.providerId(), "服务商");
        List<OpsChangeView> changes = mapper.findChanges(row.providerId(), CHANGE_HISTORY_LIMIT).stream()
                .map(change -> new OpsChangeView(change.changeId(), "PROVIDER_PROFILE", change.providerId(),
                        change.action(), change.summary(), change.operatorName(), change.changedAt()))
                .toList();
        return new OpsDetailView(row.providerId(), name, OpsStatus.valueOf(row.status()), published(row),
                submitted(row), row.rejectReason(), row.submittedAt(), row.reviewedAt(), row.version(), changes);
    }

    private static ReviewView review(ProServicePorts.Provider provider, ProfileRow row) {
        if (row == null) {
            return new ReviewView(provider.providerId(), provider.name(), ReviewStatus.NONE, null, null, null, null,
                    null, 0);
        }
        return new ReviewView(provider.providerId(), provider.name(), ReviewStatus.valueOf(row.status()),
                published(row), submitted(row), row.rejectReason(), row.submittedAt(), row.reviewedAt(),
                row.version());
    }

    private static ProfileView published(ProfileRow row) {
        if (row.publishedLegalEntity() == null) {
            return null;
        }
        return new ProfileView(row.publishedLegalEntity(), row.publishedServiceModes(),
                row.publishedAuthorizationUntil(), deliverables(row.publishedDeliverables()),
                row.publishedLocalTeamLabel());
    }

    private static ProfileView submitted(ProfileRow row) {
        return new ProfileView(row.submittedLegalEntity(), row.submittedServiceModes(),
                row.submittedAuthorizationUntil(), deliverables(row.submittedDeliverables()),
                row.submittedLocalTeamLabel());
    }

    private static List<String> deliverables(String text) {
        return text == null || text.isEmpty() ? List.of() : List.of(text.split(SEPARATOR));
    }

    private Map<UUID, String> names(List<UUID> providerIds) {
        return providerIds.stream().distinct()
                .map(providers::find)
                .flatMap(Optional::stream)
                .collect(Collectors.toMap(ProServicePorts.Provider::providerId, ProServicePorts.Provider::name,
                        (left, right) -> left));
    }

    private ProServicePorts.Provider requireProvider(UUID providerId) {
        return (providerId == null ? Optional.<ProServicePorts.Provider>empty() : providers.find(providerId))
                .orElseThrow(() -> new OpsException(ProfileError.NOT_PROVIDER));
    }

    record Content(String legalEntity, String serviceModes, LocalDate authorizationUntil, List<String> deliverables,
                   String localTeamLabel) {

        String deliverablesText() {
            return String.join(SEPARATOR, deliverables);
        }

        boolean sameAsSubmitted(ProfileRow row) {
            return legalEntity.equals(row.submittedLegalEntity()) && serviceModes.equals(row.submittedServiceModes())
                    && Objects.equals(authorizationUntil, row.submittedAuthorizationUntil())
                    && deliverablesText().equals(row.submittedDeliverables())
                    && Objects.equals(localTeamLabel, row.submittedLocalTeamLabel());
        }
    }

    private Content normalize(ProfileDraft draft) {
        String legalEntity = singleLine(OpsText.required(draft.legalEntity(), MAX_LEGAL_ENTITY, "legalEntity"));
        String serviceModes = singleLine(OpsText.required(draft.serviceModes(), MAX_SERVICE_MODES, "serviceModes"));
        LocalDate until = null;
        String untilText = draft.authorizationUntil() == null ? "" : draft.authorizationUntil().trim();
        if (!untilText.isEmpty()) {
            try {
                until = LocalDate.parse(untilText);
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("authorizationUntil is invalid");
            }
            if (until.isBefore(LocalDate.now(clock.withZone(BEIJING)))) {
                throw new IllegalArgumentException("authorizationUntil is in the past");
            }
        }
        List<String> items = draft.deliverables() == null ? List.of() : draft.deliverables();
        if (items.size() > MAX_DELIVERABLES) {
            throw new IllegalArgumentException("deliverables are invalid");
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String item : items) {
            distinct.add(singleLine(OpsText.required(item, MAX_DELIVERABLE, "deliverables")));
        }
        if (distinct.size() != items.size()) {
            throw new IllegalArgumentException("deliverables repeat");
        }
        String team = OpsText.optional(draft.localTeamLabel(), MAX_LOCAL_TEAM, "localTeamLabel");
        return new Content(legalEntity, serviceModes, until, new ArrayList<>(distinct), team == null ? null
                : singleLine(team));
    }

    private static String singleLine(String value) {
        if (value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException("text must be a single line");
        }
        return value;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
