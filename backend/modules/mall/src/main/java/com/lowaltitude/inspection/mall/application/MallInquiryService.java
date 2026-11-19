package com.lowaltitude.inspection.mall.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.mall.application.MallInquiryException.Error;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInquiryMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInquiryMapper.InquiryRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInquiryMapper.InvitationRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInquiryMapper.KeyRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInquiryMapper.OfferRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInquiryMapper.OrderQuoteRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallInquiryMapper.QuoteRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 询价商品：询价单、商家报价、比选、按报价下单（F4，契约 contracts/openapi-mall-inquiry.yaml 1.0.0，
 * 规则见 docs/redesign/03-App实现任务计划.md §25.1）。
 *
 * <ul>
 *   <li>询价单：一件询价商品，配置和用途说明（必填）、1–99 件、交付城市、期望交付日期（可不填），
 *       发给这件商品在这个城市的 1–3 家询价供给（每家商家一个，商家要登记了收款账户）。提交不产生费用。</li>
 *   <li>提交后 72 小时内收报价；截止时一家都没报的自动关闭，报过但都失效了的也关闭。</li>
 *   <li>商家报价：单价、型号配置、交期（天）、包含内容、有效期（不超过 7 天）；可以改价（新的一版），可以拒绝。
 *       商家只看到商品、配置说明、数量、城市和日期，看不到客户的身份和联系方式。</li>
 *   <li>比选后选定一份：询价单“已选定”，其余报价失效。按选定的报价下一张普通商城订单（交易模块），一份报价同时只能有一张
 *       没关闭的订单（交易模块按 {@link #orderQuote} 给出的上一张订单判断）。</li>
 * </ul>
 *
 * <p>加锁顺序：询价单 → 邀请 → 报价。
 */
public class MallInquiryService {

    static final int MAX_REQUIREMENT = 200;
    static final int MAX_QUANTITY = 99;
    static final int MAX_MERCHANTS = 3;
    static final Duration QUOTE_WINDOW = Duration.ofHours(72);
    static final Duration MAX_QUOTE_VALIDITY = Duration.ofDays(7);
    static final int MAX_MODEL = 60;
    static final int MAX_INCLUDES = 120;
    static final int MAX_LEAD_DAYS = 365;
    static final int MAX_REASON = 80;
    static final long MAX_AMOUNT_MINOR = 10_000_000_000L;
    static final int MAX_PAGE_SIZE = 50;
    static final int JOB_BATCH = 100;
    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";

    private final MallInquiryMapper mapper;
    private final MallInquiryPorts.MerchantPayments payments;
    private final Clock clock;

    MallInquiryService(MallInquiryMapper mapper, MallInquiryPorts.MerchantPayments payments, Clock clock) {
        this.mapper = mapper;
        this.payments = payments;
        this.clock = clock;
    }

    // ---- 询价供给 ----

    /** 这件询价商品在这个城市可以发询价的供给；canInquire 为 false 的商家没开通在线收款。 */
    @Transactional(readOnly = true)
    public MallInquiryOfferListView offers(UUID productId, String cityCode) {
        String city = requireCity(cityCode);
        require(productId, "product id");
        return new MallInquiryOfferListView(productId, city, mapper.findInquiryOffers(productId, city).stream()
                .map(offer -> new MallInquiryOfferView(offer.offerId(), merchant(offer.merchantId(),
                        offer.merchantName(), offer.merchantKind()), blankToNull(offer.deliveryNote()),
                        payments.canCollect(offer.merchantId())))
                .toList());
    }

    // ---- 客户：询价单 ----

    /** 提交询价单。同一个幂等键、同样的内容重放返回第一次的询价单；换了内容拒绝。 */
    @Transactional
    public MallInquiryCreated submit(UUID accountId, MallInquiryBuyer buyer, UUID idempotencyKey,
                                     MallInquiryDraft draft) {
        requireAccount(accountId);
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        if (buyer == null || blankToNull(buyer.contactName()) == null) {
            throw new IllegalArgumentException("buyer is required");
        }
        OffsetDateTime now = now();
        Input input = normalize(draft, now);
        String hash = hash(input);
        mapper.lockAccount(accountId);
        KeyRow replayed = mapper.findByKey(accountId, idempotencyKey);
        if (replayed != null) {
            if (!replayed.requestHash().equals(hash)) {
                throw new MallInquiryException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new MallInquiryCreated(read(accountId, replayed.id()), false);
        }
        Map<UUID, OfferRow> found = mapper.findOffers(input.offerIds()).stream()
                .collect(Collectors.toMap(OfferRow::offerId, Function.identity()));
        List<OfferRow> offers = new ArrayList<>();
        for (UUID offerId : input.offerIds()) {
            OfferRow offer = found.get(offerId);
            if (offer == null || !offer.productId().equals(input.productId()) || !inquirable(offer, input.cityCode())) {
                throw new MallInquiryException(Error.OFFER_UNAVAILABLE);
            }
            offers.add(offer);
        }
        if (offers.stream().map(OfferRow::merchantId).distinct().count() != offers.size()) {
            throw new IllegalArgumentException("offers must be of different merchants");
        }
        for (OfferRow offer : offers) {
            if (!payments.canCollect(offer.merchantId())) {
                throw new MallInquiryException(Error.MERCHANT_UNAVAILABLE);
            }
        }
        boolean organization = buyer.organizationId() != null && blankToNull(buyer.organizationName()) != null;
        UUID inquiryId = UUID.randomUUID();
        OfferRow first = offers.getFirst();
        mapper.insertInquiry(inquiryId, accountId,
                organization ? MallInquiryBuyerKind.ORGANIZATION.name() : MallInquiryBuyerKind.PERSONAL.name(),
                organization ? buyer.organizationId() : null,
                organization ? buyer.organizationName().trim() : null,
                buyer.contactName().trim(),
                first.productId(), first.productName(), first.coverUrl(), input.requirement(), input.quantity(),
                input.cityCode(), input.expectedDate(), now.plus(QUOTE_WINDOW), idempotencyKey, hash, now);
        int sort = 0;
        for (OfferRow offer : offers) {
            mapper.insertInvitation(inquiryId, offer.offerId(), offer.merchantId(), offer.merchantName(),
                    offer.merchantKind(), sort, now);
            sort += 10;
        }
        mapper.insertEvent(inquiryId, null, "SUBMITTED", accountId, null, now);
        return new MallInquiryCreated(read(accountId, inquiryId), true);
    }

    @Transactional(readOnly = true)
    public MallInquiryView read(UUID accountId, UUID inquiryId) {
        requireAccount(accountId);
        InquiryRow row = mapper.findInquiry(accountId, require(inquiryId, "inquiry id"));
        if (row == null) {
            throw new MallInquiryException(Error.NOT_FOUND);
        }
        return views(List.of(row)).getFirst();
    }

    /** 询价单和各家报价（每家最新一版，不含已撤回的），按 sort 排。 */
    @Transactional(readOnly = true)
    public MallInquiryDetailView detail(UUID accountId, UUID inquiryId, String sortCode) {
        MallInquirySort sort = parse(sortCode, MallInquirySort::valueOf, "sort").orElse(MallInquirySort.ALL);
        MallInquiryView inquiry = read(accountId, inquiryId);
        OffsetDateTime now = now();
        Map<UUID, InvitationRow> invitations = mapper.findInvitations(List.of(inquiryId)).stream()
                .collect(Collectors.toMap(InvitationRow::merchantId, Function.identity()));
        boolean choosing = inquiry.status() == MallInquiryStatus.OPEN || inquiry.status() == MallInquiryStatus.QUOTED;
        Comparator<QuoteRow> byTime = Comparator.comparing(QuoteRow::quotedAt).thenComparing(QuoteRow::id);
        List<QuoteRow> quotes = new ArrayList<>(mapper.findQuotes(List.of(inquiryId)).stream()
                .filter(quote -> !MallInquiryQuoteStatus.WITHDRAWN.name().equals(quote.status()))
                .toList());
        quotes.sort(switch (sort) {
            case ALL -> byTime;
            case PRICE -> Comparator.comparingLong(QuoteRow::totalMinor).thenComparing(byTime);
            case LEAD_TIME -> Comparator.comparingInt(QuoteRow::leadDays).thenComparing(byTime);
        });
        List<MallInquiryQuoteView> views = quotes.stream().map(quote -> {
            MallInquiryQuoteStatus status = quoteStatus(quote, now);
            InvitationRow invitation = invitations.get(quote.merchantId());
            return new MallInquiryQuoteView(quote.id(), quote.quoteVersion(), quote.offerId(),
                    merchant(quote.merchantId(), invitation.merchantName(), invitation.merchantKind()), status,
                    quote.unitAmountMinor(), quote.quantity(), quote.totalMinor(), quote.modelText(), quote.leadDays(),
                    quote.includesText(), quote.validUntil(), quote.quotedAt(),
                    choosing && status == MallInquiryQuoteStatus.ACTIVE);
        }).toList();
        return new MallInquiryDetailView(inquiry, sort, views);
    }

    @Transactional(readOnly = true)
    public MallInquiryPageView list(UUID accountId, String statusCode, int page, int size) {
        requireAccount(accountId);
        requirePage(page, size);
        String status = parse(statusCode, MallInquiryStatus::valueOf, "status").map(Enum::name).orElse(null);
        long total = mapper.countInquiries(accountId, status);
        List<InquiryRow> rows = total == 0 ? List.of()
                : mapper.findInquiryPage(accountId, status, size, (page - 1) * size);
        return new MallInquiryPageView(page, size, total, (long) page * size < total, views(rows));
    }

    /**
     * 选定一份报价：询价单版本（If-Match）、报价是这家最新的有效一版、没过有效期。其余有效报价失效。
     * 同一份已经选定时原样返回。截止之后在报价有效期内仍可以选。
     */
    @Transactional
    public MallInquiryView choose(UUID accountId, UUID inquiryId, int version, UUID quoteId, Integer quoteVersion) {
        requireAccount(accountId);
        if (quoteId == null || quoteVersion == null) {
            throw new IllegalArgumentException("quote id and version are required");
        }
        InquiryRow row = mapper.lockInquiry(accountId, require(inquiryId, "inquiry id"));
        if (row == null) {
            throw new MallInquiryException(Error.NOT_FOUND);
        }
        QuoteRow quote = mapper.findQuote(inquiryId, quoteId);
        if (MallInquiryStatus.CHOSEN.name().equals(row.status()) && quoteId.equals(row.chosenQuoteId())
                && quote != null && quote.quoteVersion() == quoteVersion) {
            return read(accountId, inquiryId);
        }
        if (!MallInquiryStatus.OPEN.name().equals(row.status()) && !MallInquiryStatus.QUOTED.name().equals(row.status())) {
            throw new MallInquiryException(Error.STATE_INVALID);
        }
        if (row.version() != version) {
            throw new MallInquiryException(Error.VERSION_CONFLICT);
        }
        if (quote == null) {
            throw new MallInquiryException(Error.NOT_FOUND);
        }
        OffsetDateTime now = now();
        if (!MallInquiryQuoteStatus.ACTIVE.name().equals(quote.status()) || quote.quoteVersion() != quoteVersion) {
            throw new MallInquiryException(
                    MallInquiryQuoteStatus.EXPIRED.name().equals(quote.status()) ? Error.QUOTE_EXPIRED : Error.QUOTE_CHANGED);
        }
        if (!quote.validUntil().isAfter(now)) {
            throw new MallInquiryException(Error.QUOTE_EXPIRED);
        }
        mapper.markChosen(inquiryId, quoteId, now);
        mapper.lapseActive(inquiryId, now);
        if (mapper.choose(inquiryId, quoteId, version, now) == 0) {
            throw new MallInquiryException(Error.VERSION_CONFLICT);
        }
        mapper.insertEvent(inquiryId, quoteId, "CHOSEN", accountId, null, now);
        return read(accountId, inquiryId);
    }

    /** 选定之前关闭询价单，有效的报价随之失效。已经由自己关闭的原样返回。 */
    @Transactional
    public MallInquiryView close(UUID accountId, UUID inquiryId) {
        requireAccount(accountId);
        InquiryRow row = mapper.lockInquiry(accountId, require(inquiryId, "inquiry id"));
        if (row == null) {
            throw new MallInquiryException(Error.NOT_FOUND);
        }
        if (MallInquiryStatus.CLOSED.name().equals(row.status())
                && MallInquiryCloseReason.CUSTOMER.name().equals(row.closeReason())) {
            return read(accountId, inquiryId);
        }
        OffsetDateTime now = now();
        if (mapper.close(inquiryId, MallInquiryCloseReason.CUSTOMER.name(), now) == 0) {
            throw new MallInquiryException(Error.STATE_INVALID);
        }
        mapper.lapseActive(inquiryId, now);
        mapper.insertEvent(inquiryId, null, "CLOSED", accountId, null, now);
        return read(accountId, inquiryId);
    }

    // ---- 商家 ----

    @Transactional(readOnly = true)
    public MallMerchantInquiryPageView merchantInquiries(UUID merchantId, String statusCode, int page, int size) {
        requireMerchant(merchantId);
        requirePage(page, size);
        String status = parse(statusCode, MallInquiryStatus::valueOf, "status").map(Enum::name).orElse(null);
        long total = mapper.countMerchantInquiries(merchantId, status);
        List<InquiryRow> rows = total == 0 ? List.of()
                : mapper.findMerchantInquiryPage(merchantId, status, size, (page - 1) * size);
        return new MallMerchantInquiryPageView(page, size, total, (long) page * size < total,
                merchantViews(merchantId, rows));
    }

    @Transactional(readOnly = true)
    public MallMerchantInquiryView merchantInquiry(UUID merchantId, UUID inquiryId) {
        requireMerchant(merchantId);
        InquiryRow row = mapper.findMerchantInquiry(merchantId, require(inquiryId, "inquiry id"));
        if (row == null) {
            throw new MallInquiryException(Error.NOT_FOUND);
        }
        return merchantViews(merchantId, List.of(row)).getFirst();
    }

    /**
     * 报价或改价（截止之前）。每次是新的一版；内容和最新的有效一版一样时没有变化。拒绝过的不能再报。
     */
    @Transactional
    public MallMerchantInquiryView quote(UUID merchantId, UUID operatorId, UUID inquiryId,
                                         MallInquiryQuoteDraft draft) {
        requireMerchant(merchantId);
        requireAccount(operatorId);
        OffsetDateTime now = now();
        QuoteInput input = normalizeQuote(draft, now);
        InquiryRow row = mapper.lockMerchantInquiry(merchantId, require(inquiryId, "inquiry id"));
        if (row == null) {
            throw new MallInquiryException(Error.NOT_FOUND);
        }
        if (!MallInquiryStatus.OPEN.name().equals(row.status()) && !MallInquiryStatus.QUOTED.name().equals(row.status())) {
            throw new MallInquiryException(Error.STATE_INVALID);
        }
        if (!row.quoteDeadline().isAfter(now)) {
            throw new MallInquiryException(Error.QUOTING_CLOSED);
        }
        InvitationRow invitation = mapper.lockInvitation(inquiryId, merchantId);
        if (MallInquiryInvitationStatus.DECLINED.name().equals(invitation.status())) {
            throw new MallInquiryException(Error.STATE_INVALID);
        }
        QuoteRow latest = mapper.lockLatestQuote(inquiryId, merchantId);
        if (latest != null && MallInquiryQuoteStatus.ACTIVE.name().equals(latest.status())
                && latest.validUntil().isAfter(now) && sameContent(latest, input)) {
            return merchantInquiry(merchantId, inquiryId);
        }
        mapper.supersede(inquiryId, merchantId, now);
        int next = latest == null ? 1 : latest.quoteVersion() + 1;
        UUID quoteId = mapper.insertQuote(inquiryId, invitation.offerId(), merchantId, next, input.unitAmountMinor(),
                row.quantity(), Math.multiplyExact(input.unitAmountMinor(), (long) row.quantity()), input.model(),
                input.leadDays(), input.includes(), input.validUntil(), operatorId, now);
        mapper.markInvitation(inquiryId, merchantId, MallInquiryInvitationStatus.QUOTED.name(), null, now);
        mapper.changeStatus(inquiryId, MallInquiryStatus.OPEN.name(), MallInquiryStatus.QUOTED.name(), now);
        mapper.insertEvent(inquiryId, quoteId, next == 1 ? "QUOTED" : "QUOTE_REVISED", operatorId, null, now);
        return merchantInquiry(merchantId, inquiryId);
    }

    /** 拒绝（写原因）：报过的有效报价随之撤回。已经拒绝的原样返回。选定、关闭之后不能拒绝。 */
    @Transactional
    public MallMerchantInquiryView decline(UUID merchantId, UUID operatorId, UUID inquiryId, String reasonText) {
        requireMerchant(merchantId);
        requireAccount(operatorId);
        String reason = required(reasonText, MAX_REASON, "reason");
        InquiryRow row = mapper.lockMerchantInquiry(merchantId, require(inquiryId, "inquiry id"));
        if (row == null) {
            throw new MallInquiryException(Error.NOT_FOUND);
        }
        InvitationRow invitation = mapper.lockInvitation(inquiryId, merchantId);
        if (MallInquiryInvitationStatus.DECLINED.name().equals(invitation.status())) {
            return merchantInquiry(merchantId, inquiryId);
        }
        if (!MallInquiryStatus.OPEN.name().equals(row.status()) && !MallInquiryStatus.QUOTED.name().equals(row.status())) {
            throw new MallInquiryException(Error.STATE_INVALID);
        }
        OffsetDateTime now = now();
        mapper.markInvitation(inquiryId, merchantId, MallInquiryInvitationStatus.DECLINED.name(), reason, now);
        mapper.withdraw(inquiryId, merchantId, now);
        if (mapper.countActiveQuotes(inquiryId) == 0) {
            mapper.changeStatus(inquiryId, MallInquiryStatus.QUOTED.name(), MallInquiryStatus.OPEN.name(), now);
        }
        mapper.insertEvent(inquiryId, null, "DECLINED", operatorId, null, now);
        return merchantInquiry(merchantId, inquiryId);
    }

    // ---- 下单（统一交易模块经结算端口调用） ----

    /**
     * 按报价下单时用的报价：要是这个账号的、它的询价单选定的那一份、还在有效期内。数量是询价单的，单价是报价的。
     */
    @Transactional(readOnly = true)
    public MallInquiryOrderQuote orderQuote(UUID accountId, UUID quoteId) {
        requireAccount(accountId);
        OrderQuoteRow row = mapper.findOrderQuote(accountId, require(quoteId, "inquiry quote id"));
        if (row == null) {
            throw new MallInquiryException(Error.NOT_FOUND);
        }
        if (!MallInquiryStatus.CHOSEN.name().equals(row.inquiryStatus()) || !quoteId.equals(row.chosenQuoteId())
                || !MallInquiryQuoteStatus.CHOSEN.name().equals(row.quoteStatus())) {
            throw new MallInquiryException(Error.QUOTE_NOT_CHOSEN);
        }
        if (!row.validUntil().isAfter(now())) {
            throw new MallInquiryException(Error.QUOTE_EXPIRED);
        }
        return new MallInquiryOrderQuote(row.quoteId(), row.offerId(), row.quantity(), row.unitAmountMinor(),
                row.modelText(), row.lastOrderId());
    }

    /** 下单成功后（在交易模块的同一个事务里）记下这张订单。 */
    @Transactional
    public void recordOrder(UUID accountId, UUID quoteId, UUID orderId) {
        OrderQuoteRow row = mapper.findOrderQuote(accountId, quoteId);
        if (row == null || orderId == null) {
            throw new MallInquiryException(Error.NOT_FOUND);
        }
        OffsetDateTime now = now();
        if (mapper.recordOrder(quoteId, orderId, now) == 0) {
            throw new MallInquiryException(Error.QUOTE_NOT_CHOSEN);
        }
        mapper.insertEvent(row.inquiryId(), quoteId, "ORDERED", accountId, orderId, now);
    }

    // ---- 定时任务 ----

    /**
     * 有效报价过了有效期的标为过期；截止之后没有有效报价的询价单关闭（一份都没报过是 NO_QUOTES，报过但都失效了是
     * QUOTES_LAPSED）；截止之前报价都过期了的回到待报价。返回关闭的份数。
     */
    @Transactional
    public int closeLapsed() {
        OffsetDateTime now = now();
        int closed = 0;
        for (InquiryRow row : mapper.lockDue(now, JOB_BATCH)) {
            for (UUID quoteId : mapper.expireQuotes(row.id(), now)) {
                mapper.insertEvent(row.id(), quoteId, "QUOTE_EXPIRED", null, null, now);
            }
            if (mapper.countActiveQuotes(row.id()) > 0) {
                continue;
            }
            if (!row.quoteDeadline().isAfter(now)) {
                boolean none = mapper.countQuotes(row.id()) == 0;
                if (mapper.close(row.id(), none ? MallInquiryCloseReason.NO_QUOTES.name()
                        : MallInquiryCloseReason.QUOTES_LAPSED.name(), now) > 0) {
                    mapper.insertEvent(row.id(), null, none ? "CLOSED_NO_QUOTES" : "CLOSED_QUOTES_LAPSED", null,
                            null, now);
                    closed++;
                }
            } else {
                mapper.changeStatus(row.id(), MallInquiryStatus.QUOTED.name(), MallInquiryStatus.OPEN.name(), now);
            }
        }
        return closed;
    }

    // ---- 转换 ----

    private List<MallInquiryView> views(List<InquiryRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(InquiryRow::id).toList();
        Map<UUID, List<InvitationRow>> invitations = mapper.findInvitations(ids).stream()
                .collect(Collectors.groupingBy(InvitationRow::inquiryId));
        Map<UUID, List<QuoteRow>> quotes = mapper.findQuotes(ids).stream()
                .collect(Collectors.groupingBy(QuoteRow::inquiryId));
        OffsetDateTime now = now();
        return rows.stream().map(row -> {
            MallInquiryStatus status = MallInquiryStatus.valueOf(row.status());
            List<InvitationRow> sent = invitations.getOrDefault(row.id(), List.of());
            List<QuoteRow> received = quotes.getOrDefault(row.id(), List.of()).stream()
                    .filter(quote -> !MallInquiryQuoteStatus.WITHDRAWN.name().equals(quote.status()))
                    .toList();
            MallInquiryChosenView chosen = null;
            if (status == MallInquiryStatus.CHOSEN) {
                QuoteRow quote = received.stream().filter(candidate -> candidate.id().equals(row.chosenQuoteId()))
                        .findFirst().orElseThrow();
                InvitationRow invitation = sent.stream()
                        .filter(candidate -> candidate.merchantId().equals(quote.merchantId())).findFirst().orElseThrow();
                chosen = new MallInquiryChosenView(quote.id(), quote.quoteVersion(), quote.offerId(),
                        merchant(invitation.merchantId(), invitation.merchantName(), invitation.merchantKind()),
                        quote.unitAmountMinor(), quote.quantity(), quote.totalMinor(), quote.validUntil(),
                        row.chosenAt(), quote.lastOrderId());
            }
            boolean open = status == MallInquiryStatus.OPEN || status == MallInquiryStatus.QUOTED;
            return new MallInquiryView(
                    row.id(),
                    status,
                    row.closeReason() == null ? null : MallInquiryCloseReason.valueOf(row.closeReason()),
                    new MallInquiryBuyerView(MallInquiryBuyerKind.valueOf(row.buyerKind()),
                            row.buyerOrganizationName(), row.buyerContactName()),
                    product(row),
                    row.requirement(),
                    row.quantity(),
                    row.cityCode(),
                    row.expectedDate(),
                    sent.stream().map(invitation -> new MallInquiryMerchantView(invitation.offerId(),
                            merchant(invitation.merchantId(), invitation.merchantName(), invitation.merchantKind()),
                            MallInquiryInvitationStatus.valueOf(invitation.status()), invitation.declineReason()))
                            .toList(),
                    row.createdAt(),
                    row.quoteDeadline(),
                    open && row.quoteDeadline().isAfter(now),
                    received.size(),
                    chosen,
                    open,
                    row.updatedAt(),
                    row.version());
        }).toList();
    }

    private List<MallMerchantInquiryView> merchantViews(UUID merchantId, List<InquiryRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(InquiryRow::id).toList();
        Map<UUID, InvitationRow> invitations = mapper.findInvitations(ids).stream()
                .filter(invitation -> invitation.merchantId().equals(merchantId))
                .collect(Collectors.toMap(InvitationRow::inquiryId, Function.identity()));
        Map<UUID, QuoteRow> mine = mapper.findQuotes(ids).stream()
                .filter(quote -> quote.merchantId().equals(merchantId))
                .collect(Collectors.toMap(QuoteRow::inquiryId, Function.identity(),
                        (a, b) -> a.quoteVersion() >= b.quoteVersion() ? a : b));
        OffsetDateTime now = now();
        return rows.stream().map(row -> {
            InvitationRow invitation = invitations.get(row.id());
            QuoteRow quote = mine.get(row.id());
            MallInquiryStatus status = MallInquiryStatus.valueOf(row.status());
            boolean open = (status == MallInquiryStatus.OPEN || status == MallInquiryStatus.QUOTED)
                    && row.quoteDeadline().isAfter(now)
                    && !MallInquiryInvitationStatus.DECLINED.name().equals(invitation.status());
            return new MallMerchantInquiryView(row.id(), status, product(row), invitation.offerId(),
                    row.requirement(), row.quantity(), row.cityCode(), row.expectedDate(), row.createdAt(),
                    row.quoteDeadline(), open, MallInquiryInvitationStatus.valueOf(invitation.status()),
                    invitation.declineReason(),
                    quote == null ? null : new MallMerchantQuoteView(quote.id(), quote.quoteVersion(),
                            quoteStatus(quote, now), quote.unitAmountMinor(), quote.quantity(), quote.totalMinor(),
                            quote.modelText(), quote.leadDays(), quote.includesText(), quote.validUntil(),
                            quote.quotedAt()));
        }).toList();
    }

    /** 落库是有效、但已过有效期的读成已过期（定时任务稍后才落库）。 */
    private static MallInquiryQuoteStatus quoteStatus(QuoteRow quote, OffsetDateTime now) {
        MallInquiryQuoteStatus status = MallInquiryQuoteStatus.valueOf(quote.status());
        return status == MallInquiryQuoteStatus.ACTIVE && !quote.validUntil().isAfter(now)
                ? MallInquiryQuoteStatus.EXPIRED
                : status;
    }

    private static MallInquiryProductView product(InquiryRow row) {
        return new MallInquiryProductView(row.productId(), row.productName(),
                MallCatalogReadService.secureMedia(row.coverUrl(), row.productName()));
    }

    private static MallMerchantView merchant(UUID merchantId, String name, String kind) {
        return new MallMerchantView(merchantId, name, MallMerchantKind.valueOf(kind));
    }

    private static boolean sameContent(QuoteRow quote, QuoteInput input) {
        return quote.unitAmountMinor() == input.unitAmountMinor()
                && quote.modelText().equals(input.model())
                && quote.leadDays() == input.leadDays()
                && Objects.equals(quote.includesText(), input.includes())
                && quote.validUntil().toInstant().equals(input.validUntil().toInstant());
    }

    /** 已上架、在售的询价商品供给，在这个城市（或全国）卖。 */
    private static boolean inquirable(OfferRow offer, String cityCode) {
        return MallSaleMode.INQUIRY.name().equals(offer.saleMode())
                && "PUBLISHED".equals(offer.productStatus())
                && "ACTIVE".equals(offer.skuStatus())
                && "ACTIVE".equals(offer.offerStatus())
                && (offer.cityCode() == null || offer.cityCode().equals(cityCode));
    }

    // ---- 校验 ----

    private Input normalize(MallInquiryDraft draft, OffsetDateTime now) {
        if (draft == null) {
            throw new IllegalArgumentException("inquiry is required");
        }
        UUID productId = require(draft.productId(), "product id");
        String requirement = required(draft.requirement(), MAX_REQUIREMENT, "requirement", true);
        if (draft.quantity() == null || draft.quantity() < 1 || draft.quantity() > MAX_QUANTITY) {
            throw new IllegalArgumentException("quantity is invalid");
        }
        String city = requireCity(draft.cityCode());
        LocalDate expected = null;
        if (blankToNull(draft.expectedDate()) != null) {
            try {
                expected = LocalDate.parse(draft.expectedDate().trim());
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("expected date is invalid");
            }
            if (!expected.isAfter(now.atZoneSameInstant(BEIJING).toLocalDate())) {
                throw new IllegalArgumentException("expected date must be tomorrow or later");
            }
        }
        List<UUID> offerIds = draft.offerIds() == null ? List.of() : draft.offerIds();
        if (offerIds.isEmpty() || offerIds.size() > MAX_MERCHANTS || offerIds.stream().anyMatch(Objects::isNull)
                || new LinkedHashSet<>(offerIds).size() != offerIds.size()) {
            throw new IllegalArgumentException("pick 1 to 3 different offers");
        }
        return new Input(productId, requirement, draft.quantity(), city, expected, List.copyOf(offerIds));
    }

    private static QuoteInput normalizeQuote(MallInquiryQuoteDraft draft, OffsetDateTime now) {
        if (draft == null || draft.unitAmountMinor() == null || draft.unitAmountMinor() < 1
                || draft.unitAmountMinor() > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("unit amount is invalid");
        }
        if (draft.leadDays() == null || draft.leadDays() < 1 || draft.leadDays() > MAX_LEAD_DAYS) {
            throw new IllegalArgumentException("lead days are invalid");
        }
        if (draft.validUntil() == null || !draft.validUntil().isAfter(now)
                || draft.validUntil().isAfter(now.plus(MAX_QUOTE_VALIDITY))) {
            throw new IllegalArgumentException("validity is invalid");
        }
        return new QuoteInput(draft.unitAmountMinor(), required(draft.model(), MAX_MODEL, "model", false),
                draft.leadDays(), text(draft.includes(), MAX_INCLUDES, "includes", false), draft.validUntil());
    }

    private static String required(String value, int max, String what) {
        return required(value, max, what, false);
    }

    private static String required(String value, int max, String what, boolean multiline) {
        String text = text(value, max, what, multiline);
        if (text == null) {
            throw new IllegalArgumentException(what + " is required");
        }
        return text;
    }

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

    private static String requireCity(String cityCode) {
        String city = cityCode == null ? "" : cityCode.trim();
        if (!city.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return city;
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

    private static <T> T require(T value, String what) {
        if (value == null) {
            throw new IllegalArgumentException(what + " is required");
        }
        return value;
    }

    private static void requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new IllegalArgumentException("account is required");
        }
    }

    private static void requireMerchant(UUID merchantId) {
        if (merchantId == null) {
            throw new MallInquiryException(Error.FORBIDDEN);
        }
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String hash(Input input) {
        String text = String.join("|", input.productId().toString(), input.requirement(),
                String.valueOf(input.quantity()), input.cityCode(), Objects.toString(input.expectedDate(), ""),
                input.offerIds().stream().map(UUID::toString).collect(Collectors.joining(",")));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private record Input(UUID productId, String requirement, int quantity, String cityCode, LocalDate expectedDate,
                         List<UUID> offerIds) {
    }

    private record QuoteInput(long unitAmountMinor, String model, int leadDays, String includes,
                              OffsetDateTime validUntil) {
    }
}
