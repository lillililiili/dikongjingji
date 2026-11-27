package com.lowaltitude.inspection.demand.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.demand.application.DemandException.Error;
import com.lowaltitude.inspection.demand.application.DemandPorts.CityDirectory;
import com.lowaltitude.inspection.demand.application.DemandPorts.Notifier;
import com.lowaltitude.inspection.demand.application.DemandPorts.Provider;
import com.lowaltitude.inspection.demand.application.DemandPorts.ProviderDirectory;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandMapper;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandMapper.DeliverableRow;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandMapper.DemandRow;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandMapper.InvitationRow;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandMapper.KeyRow;
import com.lowaltitude.inspection.demand.infrastructure.persistence.DemandMapper.QuoteRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 需求发布与报价比选（F1，契约 contracts/openapi-demand.yaml 1.0.0，规则见 docs/redesign/03-App实现任务计划.md §22.2）。
 *
 * <ul>
 *   <li>发布人是登录账号；默认组织是客户组织时需求记在组织名下，否则是个人。只有发布人自己能看、能改。</li>
 *   <li>草稿可以不完整；提交时校验城市、地点、范围、期望日期、交付物、邀请。提交后 72 小时截止报价。</li>
 *   <li>平台匹配：发布城市里有服务商资料的服务商都能看到并报价；仅邀请：只有被邀请的 1–3 家。
 *       服务商只看到需求内容，看不到发布人的账号、组织和联系方式。</li>
 *   <li>一家服务商对一份需求只有一份报价：截止前可以改（版本号加 1）或撤回（撤回后截止前可以再报）；被选中后不能改。</li>
 *   <li>截止前没有任何有效报价的需求由定时任务关闭（报价期内没有服务商报价）；有报价的，截止后不再收新报价，
 *       客户在每份报价的有效期内都可以选。</li>
 *   <li>选定一家：需求“已选定”，被选的报价“已选中”，其余“未选中”。取消只在选定之前。</li>
 *   <li>不产生费用：金额只做展示和比较，不建付款单。</li>
 * </ul>
 *
 * <p>加锁顺序：需求单 → 报价。客户选定、服务商报价和撤回、定时关闭都先锁需求单。
 */
public class DemandService {

    /** 报价截止：提交后 72 小时（§22.2 第 2 条）。 */
    static final Duration QUOTE_WINDOW = Duration.ofHours(72);
    /** 报价有效期最长 30 天。 */
    static final Duration MAX_QUOTE_VALIDITY = Duration.ofDays(30);
    static final int MAX_INVITATIONS = 3;
    static final int MAX_ADDRESS = 80;
    static final int MAX_SCOPE = 60;
    static final int MAX_NOTE = 500;
    static final int MAX_METHOD = 40;
    static final int MAX_SCHEDULE = 60;
    static final int MAX_QUOTE_DELIVERABLES = 120;
    /** 报价明细和对比项（1.3.0）。 */
    static final int MAX_QUOTE_LINES = 10;
    static final int MAX_LINE_NAME = 20;
    static final int MAX_LINE_UNIT = 4;
    static final int MAX_LINE_QUANTITY = 999;
    static final int MAX_COMPARE_TEXT = 20;
    static final int MAX_REPORT_HOURS = 720;
    static final int MAX_ARCHIVE_DAYS = 3650;
    static final int MAX_DURATION_DAYS = 3650;
    /** 金额上限：一亿元（分）。只做展示和比较，防止误输入。 */
    static final long MAX_AMOUNT_MINOR = 10_000_000_000L;
    static final int EXPIRE_BATCH = 100;
    private static final int MAX_PAGE_SIZE = 50;
    /** 服务商列表能筛的状态（契约 DemandStatus 去掉草稿）。 */
    private static final Set<String> PROVIDER_VISIBLE_STATUSES = Set.of("OPEN", "QUOTED", "CHOSEN", "CLOSED");
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";
    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    private final DemandMapper mapper;
    private final CityDirectory cities;
    private final ProviderDirectory providers;
    private final Notifier notifier;
    private final DemandPorts.ServiceOrders serviceOrders;
    private final Clock clock;

    DemandService(
            DemandMapper mapper,
            CityDirectory cities,
            ProviderDirectory providers,
            Notifier notifier,
            Clock clock
    ) {
        this(mapper, cities, providers, notifier, chosen -> null, clock);
    }

    DemandService(
            DemandMapper mapper,
            CityDirectory cities,
            ProviderDirectory providers,
            Notifier notifier,
            DemandPorts.ServiceOrders serviceOrders,
            Clock clock
    ) {
        this.mapper = mapper;
        this.cities = cities;
        this.providers = providers;
        this.notifier = notifier;
        this.serviceOrders = serviceOrders;
        this.clock = clock;
    }

    // ---- 客户：草稿与提交 ----

    /**
     * 新建需求（草稿）；submit 为 true 时在同一步里提交（校验不过整步回滚，什么都不留）。
     * 同一个幂等键、同样的内容重放返回第一次的需求（created 为 false）；换了内容拒绝。
     */
    @Transactional
    public DemandCreated create(UUID accountId, DemandPublisher publisher, UUID idempotencyKey, DemandDraft draft,
                                boolean submit) {
        requireAccount(accountId);
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        Normalized normalized = normalize(draft);
        String requestHash = hash(normalized, submit);
        mapper.lockAccount(accountId);
        KeyRow replayed = mapper.findByKey(accountId, idempotencyKey);
        if (replayed != null) {
            if (!requestHash.equals(replayed.requestHash().trim())) {
                throw new DemandException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new DemandCreated(read(accountId, replayed.id()), false);
        }
        DemandPublisher who = publisher == null ? DemandPublisher.personal() : publisher;
        OffsetDateTime now = now();
        UUID demandId = UUID.randomUUID();
        mapper.insertDemand(demandId, accountId, who.organizationId(), who.organizationName(),
                normalized.type().name(), normalized.cityCode(), normalized.cityName(), normalized.address(),
                normalized.scope(), normalized.dueDate(), normalized.budgetMin(), normalized.budgetMax(),
                normalized.note(), normalized.quoteScope().name(), idempotencyKey, requestHash, now);
        writeLists(demandId, normalized);
        mapper.insertEvent(demandId, null, "CREATED", accountId, null, null, now);
        if (submit) {
            submitLocked(accountId, requireOwn(mapper.lockDemand(accountId, demandId)), now);
        }
        return new DemandCreated(read(accountId, demandId), true);
    }

    /** 改草稿：整份替换。提交过的需求不能改。 */
    @Transactional
    public DemandView updateDraft(UUID accountId, UUID demandId, DemandDraft draft) {
        requireAccount(accountId);
        Normalized normalized = normalize(draft);
        DemandRow row = requireOwn(mapper.lockDemand(accountId, demandId));
        if (!DemandStatus.DRAFT.name().equals(row.status())) {
            throw new DemandException(Error.STATE_INVALID);
        }
        OffsetDateTime now = now();
        mapper.updateDraft(demandId, accountId, normalized.type().name(), normalized.cityCode(),
                normalized.cityName(), normalized.address(), normalized.scope(), normalized.dueDate(),
                normalized.budgetMin(), normalized.budgetMax(), normalized.note(), normalized.quoteScope().name(), now);
        mapper.deleteDeliverables(demandId);
        mapper.deleteInvitations(demandId);
        writeLists(demandId, normalized);
        mapper.insertEvent(demandId, null, "UPDATED", accountId, null, null, now);
        return read(accountId, demandId);
    }

    /** 提交草稿。已经提交过的再提交一次没有变化。 */
    @Transactional
    public DemandView submit(UUID accountId, UUID demandId) {
        requireAccount(accountId);
        DemandRow row = requireOwn(mapper.lockDemand(accountId, demandId));
        if (DemandStatus.DRAFT.name().equals(row.status())) {
            submitLocked(accountId, row, now());
        }
        return read(accountId, demandId);
    }

    /**
     * 提交时的必填校验，按页面从上到下只报第一处：城市、服务地点、作业范围、期望完成日期（不能早于今天）、
     * 交付物（至少一项）、邀请（仅邀请时 1–3 家，并且都服务这个城市）。
     */
    private void submitLocked(UUID accountId, DemandRow row, OffsetDateTime now) {
        if (row.cityCode() == null) {
            throw new DemandException(Error.INCOMPLETE, "cityCode");
        }
        if (row.address() == null) {
            throw new DemandException(Error.INCOMPLETE, "address");
        }
        if (row.scopeText() == null) {
            throw new DemandException(Error.INCOMPLETE, "scope");
        }
        if (row.dueDate() != null && row.dueDate().isBefore(now.atZoneSameInstant(BEIJING).toLocalDate())) {
            throw new DemandException(Error.INCOMPLETE, "dueDate");
        }
        if (mapper.findDeliverables(List.of(row.id())).isEmpty()) {
            throw new DemandException(Error.INCOMPLETE, "deliverables");
        }
        if (DemandQuoteScope.INVITE.name().equals(row.quoteScope())) {
            List<UUID> invited = mapper.findInvitations(List.of(row.id())).stream()
                    .map(InvitationRow::providerId)
                    .toList();
            Set<UUID> serving = providers.providersIn(row.cityCode()).stream()
                    .map(Provider::providerId)
                    .collect(Collectors.toSet());
            if (invited.isEmpty() || invited.size() > MAX_INVITATIONS || !serving.containsAll(invited)) {
                throw new DemandException(Error.INCOMPLETE, "invitedProviderIds");
            }
        }
        mapper.submit(row.id(), now.plus(QUOTE_WINDOW), now);
        mapper.insertEvent(row.id(), null, "SUBMITTED", accountId, null, null, now);
    }

    /** 删除草稿（不能恢复）。提交过的需求不能删，只能取消。 */
    @Transactional
    public void deleteDraft(UUID accountId, UUID demandId) {
        requireAccount(accountId);
        DemandRow row = requireOwn(mapper.lockDemand(accountId, demandId));
        if (!DemandStatus.DRAFT.name().equals(row.status())) {
            throw new DemandException(Error.STATE_INVALID);
        }
        mapper.deleteDraft(accountId, demandId);
    }

    /**
     * 取消需求：只在选定之前（待报价、报价中）。服务商不再报价，已收到的报价随之关闭。
     * 已经取消的再取消一次没有变化。
     */
    @Transactional
    public DemandView cancel(UUID accountId, UUID demandId) {
        requireAccount(accountId);
        DemandRow row = requireOwn(mapper.lockDemand(accountId, demandId));
        DemandStatus status = DemandStatus.valueOf(row.status());
        if (status == DemandStatus.CLOSED && DemandCloseReason.CANCELLED.name().equals(row.closeReason())) {
            return read(accountId, demandId);
        }
        if (status != DemandStatus.OPEN && status != DemandStatus.QUOTED) {
            throw new DemandException(Error.STATE_INVALID);
        }
        OffsetDateTime now = now();
        mapper.close(demandId, DemandCloseReason.CANCELLED.name(), now);
        mapper.endActiveQuotes(demandId, DemandQuoteStatus.CLOSED.name(), now);
        mapper.insertEvent(demandId, null, "CANCELLED", accountId, null, null, now);
        return read(accountId, demandId);
    }

    // ---- 客户：读 ----

    @Transactional(readOnly = true)
    public DemandView read(UUID accountId, UUID demandId) {
        requireAccount(accountId);
        return toViews(List.of(requireOwn(mapper.findDemand(accountId, demandId))), now()).getFirst();
    }

    /** 我的需求：status 为空时全部（含草稿）。 */
    @Transactional(readOnly = true)
    public DemandPageView list(UUID accountId, String statusCode, int page, int size) {
        requireAccount(accountId);
        requirePage(page, size);
        String status = parse(statusCode, DemandStatus::valueOf, "demand status").map(Enum::name).orElse(null);
        long total = mapper.countDemands(accountId, status);
        List<DemandRow> rows = mapper.findDemandPage(accountId, status, size, Math.multiplyExact(page - 1, size));
        return new DemandPageView(page, size, total, (long) page * size < total, toViews(rows, now()));
    }

    /**
     * 报价比选：撤回的不列。ALL 按第一次报价的先后；PRICE 总价从低到高；DURATION 周期从短到长；相同时按先后。
     */
    @Transactional(readOnly = true)
    public DemandQuoteListView quotes(UUID accountId, UUID demandId, String sortCode) {
        requireAccount(accountId);
        DemandQuoteSort sort = parse(sortCode, DemandQuoteSort::valueOf, "quote sort").orElse(DemandQuoteSort.ALL);
        DemandRow demand = requireOwn(mapper.findDemand(accountId, demandId));
        OffsetDateTime now = now();
        List<QuoteRow> rows = mapper.findQuotes(demandId).stream()
                .filter(row -> !DemandQuoteStatus.WITHDRAWN.name().equals(row.status()))
                .toList();
        Map<UUID, Provider> names = providerMap(rows.stream().map(QuoteRow::providerId).toList());
        List<QuoteRow> ordered = new ArrayList<>(rows);
        Comparator<QuoteRow> bySubmission = Comparator.comparing(QuoteRow::firstSubmittedAt)
                .thenComparing(QuoteRow::id);
        ordered.sort(switch (sort) {
            case ALL -> bySubmission;
            case PRICE -> Comparator.comparingLong(QuoteRow::amountMinor).thenComparing(bySubmission);
            case DURATION -> Comparator.comparingInt(QuoteRow::durationDays).thenComparing(bySubmission);
        });
        boolean choosing = DemandStatus.OPEN.name().equals(demand.status())
                || DemandStatus.QUOTED.name().equals(demand.status());
        Map<UUID, List<DemandQuoteLineView>> lines = lineViews(rows.stream().map(QuoteRow::id).toList());
        List<DemandQuoteView> items = ordered.stream()
                .map(row -> {
                    DemandQuoteStatus status = quoteStatus(row, now);
                    Provider provider = names.get(row.providerId());
                    return new DemandQuoteView(
                            row.id(),
                            new DemandQuoteProviderView(row.providerId(),
                                    provider == null ? "服务商" : provider.name(),
                                    provider == null ? null : provider.certificationLabel(),
                                    provider != null && provider.qualificationReviewed()),
                            row.quoteVersion(),
                            status,
                            row.amountMinor(),
                            row.method(),
                            row.scheduleText(),
                            row.durationDays(),
                            row.deliverablesText(),
                            row.validUntil(),
                            row.firstSubmittedAt(),
                            row.revisedAt(),
                            choosing && status == DemandQuoteStatus.ACTIVE,
                            lines.getOrDefault(row.id(), List.of()),
                            row.teamText(),
                            row.nightWorkText(),
                            row.reportHours(),
                            row.archiveDays(),
                            row.firstVisitDate());
                })
                .toList();
        return new DemandQuoteListView(demandId, DemandStatus.valueOf(demand.status()), demand.version(), sort, items);
    }

    /** 某个城市里可以邀请的服务商（有服务商资料、服务这个城市）。type 只校验，现在的服务商资料没有分服务类型。 */
    @Transactional(readOnly = true)
    public DemandProviderListView invitableProviders(String cityCode, String typeCode) {
        String city = cityCode == null ? "" : cityCode.trim();
        if (!city.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        parse(typeCode, DemandType::valueOf, "demand type");
        return new DemandProviderListView(providers.providersIn(city).stream()
                .map(DemandService::toProviderView)
                .toList());
    }

    // ---- 客户：选定 ----

    /**
     * 选定一份报价。version 是页面看到的需求版本，quoteVersion 是页面看到的报价版本：报价改过、撤回了就拒绝，
     * 页面刷新后再选。报价要在有效期内。同一份报价同一版再选一次原样返回（不看需求版本）。
     */
    @Transactional
    public DemandView choose(UUID accountId, UUID demandId, int version, UUID quoteId, Integer quoteVersion) {
        requireAccount(accountId);
        if (quoteId == null || quoteVersion == null) {
            throw new IllegalArgumentException("quote id and version are required");
        }
        DemandRow demand = requireOwn(mapper.lockDemand(accountId, demandId));
        if (DemandStatus.CHOSEN.name().equals(demand.status()) && quoteId.equals(demand.chosenQuoteId())
                && quoteVersion.equals(demand.chosenQuoteVersion())) {
            return read(accountId, demandId);
        }
        if (!DemandStatus.OPEN.name().equals(demand.status()) && !DemandStatus.QUOTED.name().equals(demand.status())) {
            throw new DemandException(Error.STATE_INVALID);
        }
        if (demand.version() != version) {
            throw new DemandException(Error.VERSION_CONFLICT);
        }
        QuoteRow quote = mapper.findQuotes(demandId).stream()
                .filter(row -> row.id().equals(quoteId))
                .findFirst()
                .orElseThrow(() -> new DemandException(Error.NOT_FOUND));
        if (!DemandQuoteStatus.ACTIVE.name().equals(quote.status()) || quote.quoteVersion() != quoteVersion) {
            throw new DemandException(Error.QUOTE_CHANGED);
        }
        OffsetDateTime now = now();
        if (!quote.validUntil().isAfter(now)) {
            throw new DemandException(Error.QUOTE_EXPIRED);
        }
        mapper.choose(demandId, quoteId, quoteVersion, version, now);
        mapper.markChosen(demandId, quoteId, quoteVersion, now);
        mapper.endActiveQuotes(demandId, DemandQuoteStatus.NOT_CHOSEN.name(), now);
        mapper.insertEvent(demandId, quoteId, "CHOSEN", accountId, quoteVersion, quote.amountMinor(), now);
        // 1.3.0：四类服务选定后在同一个事务里生成通用服务订单（S4-3）；巡检有自己的预约和方案流程，不生成。
        if (DemandType.valueOf(demand.type()) != DemandType.INSPECTION) {
            UUID serviceOrderId = serviceOrders.open(chosenQuote(demand, quote));
            if (serviceOrderId != null) {
                mapper.linkServiceOrder(demandId, serviceOrderId);
            }
        }
        return read(accountId, demandId);
    }

    private DemandPorts.ChosenQuote chosenQuote(DemandRow demand, QuoteRow quote) {
        String providerName = Optional.ofNullable(providerMap(List.of(quote.providerId())).get(quote.providerId()))
                .map(Provider::name).orElse("服务商");
        List<DemandPorts.ChosenLine> lines = mapper.findQuoteLines(List.of(quote.id())).stream()
                .map(line -> new DemandPorts.ChosenLine(line.name(), line.quantity(), line.unit(),
                        line.unitAmountMinor(), line.amountMinor()))
                .toList();
        return new DemandPorts.ChosenQuote(demand.id(), quote.id(), quote.quoteVersion(), demand.accountId(),
                demand.organizationId(), demand.organizationName(), quote.providerId(), providerName, demand.type(),
                title(demand), demand.cityCode(), demand.cityName(), demand.address(), demand.scopeText(),
                demand.dueDate(), quote.amountMinor(), quote.method(), quote.scheduleText(), quote.durationDays(),
                quote.deliverablesText(), quote.teamText(), quote.nightWorkText(), quote.reportHours(),
                quote.archiveDays(), quote.firstVisitDate(), lines);
    }

    /** 我发布的需求按状态的份数（1.3.0，“需求与报价”页签上的数字）。 */
    @Transactional(readOnly = true)
    public DemandCountsView counts(UUID accountId) {
        requireAccount(accountId);
        Map<String, Long> byStatus = new java.util.HashMap<>();
        mapper.countByStatus(accountId).forEach(row -> byStatus.put(row.status(), row.count()));
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
        return new DemandCountsView(total,
                byStatus.getOrDefault(DemandStatus.DRAFT.name(), 0L),
                byStatus.getOrDefault(DemandStatus.OPEN.name(), 0L),
                byStatus.getOrDefault(DemandStatus.QUOTED.name(), 0L),
                byStatus.getOrDefault(DemandStatus.CHOSEN.name(), 0L),
                byStatus.getOrDefault(DemandStatus.CLOSED.name(), 0L));
    }

    // ---- 服务商 ----

    /** 服务商能看到的需求：还在收报价、平台匹配到它或邀请了它的，以及它报过价的。新的在前。 */
    @Transactional(readOnly = true)
    public ProviderDemandPageView listForProvider(UUID providerId, int page, int size) {
        return listForProvider(providerId, null, page, size);
    }

    /**
     * 服务商能看到的需求，1.2.0 起可按状态筛（工作台的“待报价 / 报价中 / 已选定 / 已关闭”）：
     * 只能在能看到的范围里再筛，草稿不是可选的状态。
     */
    @Transactional(readOnly = true)
    public ProviderDemandPageView listForProvider(UUID providerId, String status, int page, int size) {
        requireProvider(providerId);
        requirePage(page, size);
        String filter = null;
        if (status != null && !status.isBlank()) {
            filter = status.trim();
            if (!PROVIDER_VISIBLE_STATUSES.contains(filter)) {
                throw new IllegalArgumentException("status is invalid");
            }
        }
        List<String> served = List.copyOf(providers.citiesServed(providerId));
        long total = mapper.countProviderDemands(providerId, served, filter);
        List<DemandRow> rows = mapper.findProviderDemands(providerId, served, filter, size,
                Math.multiplyExact(page - 1, size));
        return new ProviderDemandPageView(page, size, total, (long) page * size < total,
                toProviderViews(providerId, rows, now()));
    }

    @Transactional(readOnly = true)
    public ProviderDemandView readForProvider(UUID providerId, UUID demandId) {
        requireProvider(providerId);
        DemandRow row = demandId == null ? null : mapper.findSubmittedDemand(demandId);
        if (row == null || !visible(providerId, row, mapper.findProviderQuotes(providerId, List.of(demandId)))) {
            throw new DemandException(Error.NOT_FOUND);
        }
        return toProviderViews(providerId, List.of(row), now()).getFirst();
    }

    /**
     * 报价或改价：截止前、需求还在收报价时。第一次报价（或撤回后重新报价）给发布人发一条站内信，需求变成“报价中”。
     * 内容和现在的有效报价一样时没有变化（重复提交不加版本）。
     */
    @Transactional
    public ProviderDemandView submitQuote(UUID providerId, UUID operatorId, UUID demandId, QuoteDraft draft) {
        requireProvider(providerId);
        if (operatorId == null) {
            throw new DemandException(Error.FORBIDDEN);
        }
        // 只有有服务商资料（在用）的服务商能报价；资料停用后，以前的邀请也不再能报价。
        if (providers.find(List.of(providerId)).isEmpty()) {
            throw new DemandException(Error.FORBIDDEN);
        }
        OffsetDateTime now = now();
        QuoteContent content = normalizeQuote(draft, now);
        DemandRow demand = demandId == null ? null : mapper.lockSubmittedDemand(demandId);
        QuoteRow existing = demand == null ? null : mapper.lockProviderQuote(demandId, providerId);
        if (demand == null || !visible(providerId, demand, existing == null ? List.of() : List.of(existing))) {
            throw new DemandException(Error.NOT_FOUND);
        }
        if (existing != null && (DemandQuoteStatus.CHOSEN.name().equals(existing.status())
                || DemandQuoteStatus.NOT_CHOSEN.name().equals(existing.status())
                || DemandQuoteStatus.CLOSED.name().equals(existing.status()))) {
            throw new DemandException(Error.QUOTE_LOCKED);
        }
        if (!quoting(demand, now)) {
            throw new DemandException(Error.QUOTING_CLOSED);
        }
        boolean newOffer;
        UUID quoteId;
        int quoteVersion;
        if (existing == null) {
            quoteId = mapper.insertQuote(demandId, providerId, content.amountMinor(), content.method(),
                    content.schedule(), content.durationDays(), content.deliverables(), content.validUntil(),
                    operatorId, now);
            quoteVersion = 1;
            newOffer = true;
        } else {
            boolean active = DemandQuoteStatus.ACTIVE.name().equals(existing.status());
            if (active && content.sameAs(existing, mapper.findQuoteLines(List.of(existing.id())))) {
                return readForProvider(providerId, demandId);
            }
            mapper.reviseQuote(existing.id(), content.amountMinor(), content.method(), content.schedule(),
                    content.durationDays(), content.deliverables(), content.validUntil(), operatorId, now);
            quoteId = existing.id();
            quoteVersion = existing.quoteVersion() + 1;
            newOffer = !active;
        }
        mapper.updateQuoteDetails(quoteId, content.team(), content.nightWork(), content.reportHours(),
                content.archiveDays(), content.firstVisitDate());
        mapper.deleteQuoteLines(quoteId);
        int lineSort = 0;
        for (QuoteLineContent line : content.lines()) {
            mapper.insertQuoteLine(quoteId, lineSort, line.name(), line.quantity(), line.unit(),
                    line.unitAmountMinor(), line.amountMinor());
            lineSort += 10;
        }
        mapper.insertEvent(demandId, quoteId, newOffer ? "QUOTED" : "QUOTE_REVISED", operatorId, quoteVersion,
                content.amountMinor(), now);
        if (newOffer) {
            mapper.changeStatus(demandId, DemandStatus.OPEN.name(), DemandStatus.QUOTED.name(), now);
            notifier.quoteReceived(demand.accountId(), demandId, title(demand),
                    "demand-quote:" + quoteId + ":" + quoteVersion);
        }
        return readForProvider(providerId, demandId);
    }

    /**
     * 撤回报价：选定之前都可以。撤回后这份需求没有有效报价了就回到“待报价”（过了截止时间的由定时任务关闭）。
     * 已经撤回的再撤回一次没有变化。
     */
    @Transactional
    public ProviderDemandView withdrawQuote(UUID providerId, UUID operatorId, UUID demandId) {
        requireProvider(providerId);
        if (operatorId == null) {
            throw new DemandException(Error.FORBIDDEN);
        }
        DemandRow demand = demandId == null ? null : mapper.lockSubmittedDemand(demandId);
        QuoteRow quote = demand == null ? null : mapper.lockProviderQuote(demandId, providerId);
        if (quote == null) {
            throw new DemandException(Error.NOT_FOUND);
        }
        switch (DemandQuoteStatus.valueOf(quote.status())) {
            case WITHDRAWN -> {
                return readForProvider(providerId, demandId);
            }
            case ACTIVE -> {
                // 往下走。
            }
            default -> throw new DemandException(Error.QUOTE_LOCKED);
        }
        OffsetDateTime now = now();
        mapper.withdrawQuote(quote.id(), now);
        mapper.insertEvent(demandId, quote.id(), "QUOTE_WITHDRAWN", operatorId, quote.quoteVersion(),
                quote.amountMinor(), now);
        if (mapper.countActiveQuotes(demandId) == 0) {
            mapper.changeStatus(demandId, DemandStatus.QUOTED.name(), DemandStatus.OPEN.name(), now);
        }
        return readForProvider(providerId, demandId);
    }

    // ---- 定时任务 ----

    /** 过了报价截止时间、还没有有效报价的需求关闭（报价期内没有服务商报价），给发布人发站内信。返回关了几份。 */
    @Transactional
    public int closeExpired() {
        OffsetDateTime now = now();
        int closed = 0;
        for (DemandRow row : mapper.lockExpiredOpen(now, EXPIRE_BATCH)) {
            if (mapper.close(row.id(), DemandCloseReason.NO_QUOTES.name(), now) > 0) {
                mapper.endActiveQuotes(row.id(), DemandQuoteStatus.CLOSED.name(), now);
                mapper.insertEvent(row.id(), null, "CLOSED_NO_QUOTES", null, null, null, now);
                notifier.closedWithoutQuotes(row.accountId(), row.id(), title(row));
                closed++;
            }
        }
        return closed;
    }

    // ---- 规则 ----

    private static boolean quoting(DemandRow demand, OffsetDateTime now) {
        return (DemandStatus.OPEN.name().equals(demand.status()) || DemandStatus.QUOTED.name().equals(demand.status()))
                && demand.quoteDeadline() != null
                && now.isBefore(demand.quoteDeadline());
    }

    /**
     * 服务商能不能看到这份需求：报过价的一直能看到（看结果）；否则需求还在收报价，并且邀请了它，
     * 或者是平台匹配、在它服务的城市。
     */
    private boolean visible(UUID providerId, DemandRow demand, List<QuoteRow> ownQuotes) {
        if (!ownQuotes.isEmpty()) {
            return true;
        }
        if (!DemandStatus.OPEN.name().equals(demand.status()) && !DemandStatus.QUOTED.name().equals(demand.status())) {
            return false;
        }
        if (mapper.countInvitation(demand.id(), providerId) > 0) {
            return true;
        }
        return DemandQuoteScope.MATCH.name().equals(demand.quoteScope())
                && providers.citiesServed(providerId).contains(demand.cityCode());
    }

    private static DemandQuoteStatus quoteStatus(QuoteRow row, OffsetDateTime now) {
        DemandQuoteStatus status = DemandQuoteStatus.valueOf(row.status());
        return status == DemandQuoteStatus.ACTIVE && !row.validUntil().isAfter(now) ? DemandQuoteStatus.EXPIRED : status;
    }

    /** 需求没有单独的标题字段：用“服务类型 + 需求”，如“巡检需求”。 */
    static String title(DemandRow row) {
        return DemandType.valueOf(row.type()).label() + "需求";
    }

    // ---- 转换 ----

    private List<DemandView> toViews(List<DemandRow> rows, OffsetDateTime now) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(DemandRow::id).toList();
        Map<UUID, List<DeliverableRow>> deliverables = mapper.findDeliverables(ids).stream()
                .collect(Collectors.groupingBy(DeliverableRow::demandId));
        Map<UUID, List<InvitationRow>> invitations = mapper.findInvitations(ids).stream()
                .collect(Collectors.groupingBy(InvitationRow::demandId));
        Map<UUID, List<QuoteRow>> quotes = mapper.findQuotesOf(ids).stream()
                .collect(Collectors.groupingBy(QuoteRow::demandId));
        List<UUID> providerIds = new ArrayList<>();
        invitations.values().forEach(list -> list.forEach(row -> providerIds.add(row.providerId())));
        rows.forEach(row -> quotes.getOrDefault(row.id(), List.of()).stream()
                .filter(quote -> quote.id().equals(row.chosenQuoteId()))
                .forEach(quote -> providerIds.add(quote.providerId())));
        Map<UUID, Provider> names = providerMap(providerIds);
        return rows.stream()
                .map(row -> toView(row,
                        deliverables.getOrDefault(row.id(), List.of()),
                        invitations.getOrDefault(row.id(), List.of()),
                        quotes.getOrDefault(row.id(), List.of()),
                        names,
                        now))
                .toList();
    }

    private static DemandView toView(
            DemandRow row,
            List<DeliverableRow> deliverables,
            List<InvitationRow> invitations,
            List<QuoteRow> quotes,
            Map<UUID, Provider> names,
            OffsetDateTime now
    ) {
        DemandStatus status = DemandStatus.valueOf(row.status());
        DemandChosenView chosen = quotes.stream()
                .filter(quote -> status == DemandStatus.CHOSEN && quote.id().equals(row.chosenQuoteId()))
                .findFirst()
                .map(quote -> new DemandChosenView(quote.id(), row.chosenQuoteVersion(),
                        Optional.ofNullable(names.get(quote.providerId())).map(Provider::name).orElse("服务商"),
                        quote.amountMinor(), row.chosenAt()))
                .orElse(null);
        // 收到的报价：撤回的不算。
        int quoteCount = (int) quotes.stream()
                .filter(quote -> !DemandQuoteStatus.WITHDRAWN.name().equals(quote.status()))
                .count();
        return new DemandView(
                row.id(),
                status,
                row.closeReason() == null ? null : DemandCloseReason.valueOf(row.closeReason()),
                DemandType.valueOf(row.type()),
                title(row),
                row.organizationId() == null
                        ? new DemandPublisherView(DemandPublisherKind.PERSONAL, null)
                        : new DemandPublisherView(DemandPublisherKind.ORGANIZATION, row.organizationName()),
                row.cityCode(),
                row.cityName(),
                row.address(),
                row.scopeText(),
                row.dueDate(),
                row.budgetMinMinor(),
                row.budgetMaxMinor(),
                deliverableViews(deliverables),
                row.note(),
                DemandQuoteScope.valueOf(row.quoteScope()),
                invitations.stream()
                        .map(invitation -> Optional.ofNullable(names.get(invitation.providerId()))
                                .map(DemandService::toProviderView)
                                .orElse(new DemandProviderView(invitation.providerId(), "服务商", null, null,
                                        false)))
                        .toList(),
                row.submittedAt(),
                row.quoteDeadline(),
                quoting(row, now),
                quoteCount,
                chosen,
                status == DemandStatus.DRAFT,
                status == DemandStatus.OPEN || status == DemandStatus.QUOTED,
                status == DemandStatus.DRAFT,
                row.createdAt(),
                row.updatedAt(),
                row.version(),
                status == DemandStatus.CHOSEN ? row.serviceOrderId() : null,
                row.inspectionReservationId());
    }

    private Map<UUID, List<DemandQuoteLineView>> lineViews(List<UUID> quoteIds) {
        if (quoteIds.isEmpty()) {
            return Map.of();
        }
        return mapper.findQuoteLines(quoteIds).stream()
                .collect(Collectors.groupingBy(DemandMapper.QuoteLineRow::quoteId, Collectors.mapping(
                        line -> new DemandQuoteLineView(line.name(), line.quantity(), line.unit(),
                                line.unitAmountMinor(), line.amountMinor()),
                        Collectors.toList())));
    }

    private List<ProviderDemandView> toProviderViews(UUID providerId, List<DemandRow> rows, OffsetDateTime now) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(DemandRow::id).toList();
        Map<UUID, List<DeliverableRow>> deliverables = mapper.findDeliverables(ids).stream()
                .collect(Collectors.groupingBy(DeliverableRow::demandId));
        Map<UUID, Set<UUID>> invited = mapper.findInvitations(ids).stream()
                .collect(Collectors.groupingBy(InvitationRow::demandId,
                        Collectors.mapping(InvitationRow::providerId, Collectors.toSet())));
        Map<UUID, QuoteRow> mine = mapper.findProviderQuotes(providerId, ids).stream()
                .collect(Collectors.toMap(QuoteRow::demandId, Function.identity(), (left, right) -> left));
        Map<UUID, List<DemandQuoteLineView>> lines = lineViews(mine.values().stream().map(QuoteRow::id).toList());
        return rows.stream()
                .map(row -> {
                    QuoteRow quote = mine.get(row.id());
                    return new ProviderDemandView(
                            row.id(),
                            DemandStatus.valueOf(row.status()),
                            DemandType.valueOf(row.type()),
                            title(row),
                            row.cityCode(),
                            row.cityName(),
                            row.address(),
                            row.scopeText(),
                            row.dueDate(),
                            row.budgetMinMinor(),
                            row.budgetMaxMinor(),
                            deliverableViews(deliverables.getOrDefault(row.id(), List.of())),
                            row.note(),
                            invited.getOrDefault(row.id(), Set.of()).contains(providerId),
                            row.submittedAt(),
                            row.quoteDeadline(),
                            quoting(row, now),
                            quote == null ? null : new ProviderQuoteView(
                                    quote.id(),
                                    quote.quoteVersion(),
                                    quoteStatus(quote, now),
                                    quote.amountMinor(),
                                    quote.method(),
                                    quote.scheduleText(),
                                    quote.durationDays(),
                                    quote.deliverablesText(),
                                    quote.validUntil(),
                                    quote.firstSubmittedAt(),
                                    quote.revisedAt(),
                                    lines.getOrDefault(quote.id(), List.of()),
                                    quote.teamText(),
                                    quote.nightWorkText(),
                                    quote.reportHours(),
                                    quote.archiveDays(),
                                    quote.firstVisitDate()),
                            quote != null && quote.id().equals(row.chosenQuoteId())
                                    ? row.inspectionReservationId() : null);
                })
                .toList();
    }

    private static List<DemandDeliverableView> deliverableViews(List<DeliverableRow> rows) {
        return rows.stream()
                .map(row -> DemandDeliverable.valueOf(row.code()))
                .map(code -> new DemandDeliverableView(code, code.label()))
                .toList();
    }

    private static DemandProviderView toProviderView(Provider provider) {
        return new DemandProviderView(provider.providerId(), provider.name(), provider.certificationLabel(),
                provider.description(), provider.qualificationReviewed());
    }

    private Map<UUID, Provider> providerMap(List<UUID> ids) {
        List<UUID> distinct = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return providers.find(distinct).stream()
                .collect(Collectors.toMap(Provider::providerId, Function.identity(), (left, right) -> left));
    }

    // ---- 校验 ----

    /** 草稿的内容：长度、格式、交付物和类型对得上、邀请最多 3 家并且都是有服务商资料的服务商。 */
    private Normalized normalize(DemandDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("demand is required");
        }
        DemandType type = parse(draft.type(), DemandType::valueOf, "demand type")
                .orElseThrow(() -> new IllegalArgumentException("demand type is required"));
        String cityCode = blankToNull(draft.cityCode());
        String cityName = null;
        if (cityCode != null) {
            cityName = cities.cityName(cityCode)
                    .orElseThrow(() -> new IllegalArgumentException("city is not in the platform directory"));
        }
        String address = text(draft.address(), MAX_ADDRESS, "address", false);
        String scope = text(draft.scope(), MAX_SCOPE, "scope", false);
        String note = text(draft.note(), MAX_NOTE, "note", true);
        LocalDate dueDate = null;
        if (blankToNull(draft.dueDate()) != null) {
            try {
                dueDate = LocalDate.parse(draft.dueDate().trim());
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("due date is invalid");
            }
        }
        Long min = draft.budgetMinMinor();
        Long max = draft.budgetMaxMinor();
        if ((min != null && (min < 0 || min > MAX_AMOUNT_MINOR)) || (max != null && (max < 1 || max > MAX_AMOUNT_MINOR))
                || (min != null && max != null && min > max)) {
            throw new IllegalArgumentException("budget is invalid");
        }
        List<DemandDeliverable> deliverables = new ArrayList<>();
        for (String code : draft.deliverables() == null ? List.<String>of() : draft.deliverables()) {
            DemandDeliverable deliverable = parse(code, DemandDeliverable::valueOf, "deliverable")
                    .orElseThrow(() -> new IllegalArgumentException("deliverable is invalid"));
            if (!type.deliverables().contains(deliverable) || deliverables.contains(deliverable)) {
                throw new IllegalArgumentException("deliverable does not fit the demand type");
            }
            deliverables.add(deliverable);
        }
        DemandQuoteScope scopeMode = parse(draft.quoteScope(), DemandQuoteScope::valueOf, "quote scope")
                .orElse(DemandQuoteScope.MATCH);
        List<UUID> invited = List.of();
        if (scopeMode == DemandQuoteScope.INVITE && draft.invitedProviderIds() != null) {
            if (draft.invitedProviderIds().stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("invited providers are invalid");
            }
            invited = List.copyOf(new LinkedHashSet<>(draft.invitedProviderIds()));
            if (invited.size() != draft.invitedProviderIds().size() || invited.size() > MAX_INVITATIONS) {
                throw new IllegalArgumentException("invited providers are invalid");
            }
            if (!invited.isEmpty() && providers.find(invited).size() != invited.size()) {
                throw new IllegalArgumentException("invited provider is not a provider");
            }
        }
        return new Normalized(type, cityCode, cityName, address, scope, dueDate, min, max, deliverables, note,
                scopeMode, invited);
    }

    private void writeLists(UUID demandId, Normalized normalized) {
        int sort = 0;
        for (DemandDeliverable deliverable : normalized.deliverables()) {
            mapper.insertDeliverable(demandId, deliverable.name(), sort);
            sort += 10;
        }
        sort = 0;
        for (UUID providerId : normalized.invited()) {
            mapper.insertInvitation(demandId, providerId, sort);
            sort += 10;
        }
    }

    private static QuoteContent normalizeQuote(QuoteDraft draft, OffsetDateTime now) {
        if (draft == null || draft.amountMinor() == null || draft.amountMinor() < 1
                || draft.amountMinor() > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("quote amount is invalid");
        }
        if (draft.durationDays() == null || draft.durationDays() < 1 || draft.durationDays() > MAX_DURATION_DAYS) {
            throw new IllegalArgumentException("duration is invalid");
        }
        if (draft.validUntil() == null || !draft.validUntil().isAfter(now)
                || draft.validUntil().isAfter(now.plus(MAX_QUOTE_VALIDITY))) {
            throw new IllegalArgumentException("validity is invalid");
        }
        List<QuoteLineContent> lines = quoteLines(draft.lines(), draft.amountMinor());
        if (draft.reportHours() != null && (draft.reportHours() < 1 || draft.reportHours() > MAX_REPORT_HOURS)) {
            throw new IllegalArgumentException("report hours are invalid");
        }
        if (draft.archiveDays() != null && (draft.archiveDays() < 0 || draft.archiveDays() > MAX_ARCHIVE_DAYS)) {
            throw new IllegalArgumentException("archive days are invalid");
        }
        LocalDate firstVisit = null;
        if (blankToNull(draft.firstVisitDate()) != null) {
            try {
                firstVisit = LocalDate.parse(draft.firstVisitDate().trim());
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("first visit date is invalid");
            }
        }
        return new QuoteContent(
                draft.amountMinor(),
                required(draft.method(), MAX_METHOD, "method"),
                required(draft.schedule(), MAX_SCHEDULE, "schedule"),
                draft.durationDays(),
                required(draft.deliverables(), MAX_QUOTE_DELIVERABLES, "deliverables"),
                draft.validUntil(),
                lines,
                text(draft.team(), MAX_COMPARE_TEXT, "team", true),
                text(draft.nightWork(), MAX_COMPARE_TEXT, "night work", true),
                draft.reportHours(),
                draft.archiveDays(),
                firstVisit);
    }

    /** 报价明细：最多 10 行；有明细时合计等于总价；按次计的行“数量 × 单价 = 金额”。 */
    private static List<QuoteLineContent> quoteLines(List<QuoteLineDraft> drafts, long amountMinor) {
        if (drafts == null || drafts.isEmpty()) {
            return List.of();
        }
        if (drafts.size() > MAX_QUOTE_LINES) {
            throw new IllegalArgumentException("quote lines are invalid");
        }
        List<QuoteLineContent> lines = new ArrayList<>();
        long sum = 0;
        for (QuoteLineDraft draft : drafts) {
            if (draft == null || draft.amountMinor() == null || draft.amountMinor() < 1
                    || draft.amountMinor() > MAX_AMOUNT_MINOR) {
                throw new IllegalArgumentException("quote line amount is invalid");
            }
            String name = required(draft.name(), MAX_LINE_NAME, "line name");
            boolean counted = draft.quantity() != null || draft.unit() != null || draft.unitAmountMinor() != null;
            String unit = null;
            if (counted) {
                unit = required(draft.unit(), MAX_LINE_UNIT, "line unit");
                if (draft.quantity() == null || draft.quantity() < 1 || draft.quantity() > MAX_LINE_QUANTITY
                        || draft.unitAmountMinor() == null || draft.unitAmountMinor() < 1
                        || Math.multiplyExact((long) draft.quantity(), draft.unitAmountMinor())
                        != draft.amountMinor()) {
                    throw new IllegalArgumentException("quote line is invalid");
                }
            }
            lines.add(new QuoteLineContent(name, counted ? draft.quantity() : null, unit,
                    counted ? draft.unitAmountMinor() : null, draft.amountMinor()));
            sum = Math.addExact(sum, draft.amountMinor());
        }
        if (sum != amountMinor) {
            throw new IllegalArgumentException("quote lines do not add up to the amount");
        }
        return List.copyOf(lines);
    }

    private static String required(String value, int max, String what) {
        String text = text(value, max, what, false);
        if (text == null) {
            throw new IllegalArgumentException(what + " is required");
        }
        return text;
    }

    /** 去掉首尾空白，空的当作没填；不能超长、不能有控制字符（补充说明可以换行）。 */
    private static String text(String value, int max, String what, boolean multiline) {
        String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        boolean control = trimmed.chars().anyMatch(ch -> Character.isISOControl(ch) && !(multiline && ch == '\n'));
        if (trimmed.length() > max || control) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return trimmed;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static <E> Optional<E> parse(String code, Function<String, E> parser, String what) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parser.apply(code.trim()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(what + " is invalid");
        }
    }

    private static DemandRow requireOwn(DemandRow row) {
        if (row == null) {
            throw new DemandException(Error.NOT_FOUND);
        }
        return row;
    }

    private static void requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("account is required");
        }
    }

    private static void requireProvider(UUID providerId) {
        if (providerId == null) {
            throw new DemandException(Error.FORBIDDEN);
        }
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
    }

    private static String hash(Normalized normalized, boolean submit) {
        String text = String.join("|",
                normalized.type().name(),
                Objects.toString(normalized.cityCode(), ""),
                Objects.toString(normalized.address(), ""),
                Objects.toString(normalized.scope(), ""),
                Objects.toString(normalized.dueDate(), ""),
                Objects.toString(normalized.budgetMin(), ""),
                Objects.toString(normalized.budgetMax(), ""),
                normalized.deliverables().stream().map(Enum::name).collect(Collectors.joining(",")),
                Objects.toString(normalized.note(), ""),
                normalized.quoteScope().name(),
                normalized.invited().stream().map(UUID::toString).collect(Collectors.joining(",")),
                Boolean.toString(submit));
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock);
    }

    private record Normalized(
            DemandType type,
            String cityCode,
            String cityName,
            String address,
            String scope,
            LocalDate dueDate,
            Long budgetMin,
            Long budgetMax,
            List<DemandDeliverable> deliverables,
            String note,
            DemandQuoteScope quoteScope,
            List<UUID> invited
    ) {
    }

    private record QuoteContent(
            long amountMinor,
            String method,
            String schedule,
            int durationDays,
            String deliverables,
            OffsetDateTime validUntil,
            List<QuoteLineContent> lines,
            String team,
            String nightWork,
            Integer reportHours,
            Integer archiveDays,
            LocalDate firstVisitDate
    ) {
        boolean sameAs(QuoteRow row, List<DemandMapper.QuoteLineRow> rowLines) {
            List<QuoteLineContent> existing = rowLines.stream()
                    .map(line -> new QuoteLineContent(line.name(), line.quantity(), line.unit(),
                            line.unitAmountMinor(), line.amountMinor()))
                    .toList();
            return amountMinor == row.amountMinor()
                    && method.equals(row.method())
                    && schedule.equals(row.scheduleText())
                    && durationDays == row.durationDays()
                    && deliverables.equals(row.deliverablesText())
                    && validUntil.isEqual(row.validUntil())
                    && lines.equals(existing)
                    && Objects.equals(team, row.teamText())
                    && Objects.equals(nightWork, row.nightWorkText())
                    && Objects.equals(reportHours, row.reportHours())
                    && Objects.equals(archiveDays, row.archiveDays())
                    && Objects.equals(firstVisitDate, row.firstVisitDate());
        }
    }

    private record QuoteLineContent(String name, Integer quantity, String unit, Long unitAmountMinor,
                                    long amountMinor) {
    }
}
