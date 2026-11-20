package com.lowaltitude.inspection.rental.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.rental.application.RentalException.Error;
import com.lowaltitude.inspection.rental.application.RentalPorts.Address;
import com.lowaltitude.inspection.rental.application.RentalPorts.Addresses;
import com.lowaltitude.inspection.rental.application.RentalPorts.Offer;
import com.lowaltitude.inspection.rental.application.RentalPorts.Offers;
import com.lowaltitude.inspection.rental.application.RentalPorts.OpenPayment;
import com.lowaltitude.inspection.rental.application.RentalPorts.OpenedPayment;
import com.lowaltitude.inspection.rental.application.RentalPorts.PaymentLine;
import com.lowaltitude.inspection.rental.application.RentalPorts.Payments;
import com.lowaltitude.inspection.rental.application.RentalPorts.Qualifications;
import com.lowaltitude.inspection.rental.application.RentalPorts.RefundProgress;
import com.lowaltitude.inspection.rental.infrastructure.persistence.RentalMapper;
import com.lowaltitude.inspection.rental.infrastructure.persistence.RentalMapper.ContractRow;
import com.lowaltitude.inspection.rental.infrastructure.persistence.RentalMapper.FeeRow;
import com.lowaltitude.inspection.rental.infrastructure.persistence.RentalMapper.InquiryRow;
import com.lowaltitude.inspection.rental.infrastructure.persistence.RentalMapper.KeyRow;
import com.lowaltitude.inspection.rental.infrastructure.persistence.RentalMapper.OrderRow;
import com.lowaltitude.inspection.rental.infrastructure.persistence.RentalMapper.OrderSummaryRow;
import com.lowaltitude.inspection.rental.infrastructure.persistence.RentalMapper.QuoteRow;
import com.lowaltitude.inspection.rental.infrastructure.persistence.RentalMapper.RecordRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备租赁：询价、报价、确认合同、付租金、交接与归还（F3，契约 contracts/openapi-rental.yaml 1.0.0，
 * 规则见 docs/redesign/03-App实现任务计划.md §24.1）。
 *
 * <ul>
 *   <li>能租的是商城里交易方式为租赁、出租方登记了收款账户的供给；一张询价单是一个商家的一种设备，1–10 台、1–90 天，
 *       起租日不早于明天（北京时间），使用地点从自己的收货地址里选（快照收货人和手机号）。提交不产生费用。</li>
 *   <li>商家报价（日租金、天数、台数、其他费用行、合计、档期是否可用、有效期不超过 7 天、合同条款），可以改价（版本加 1）、
 *       可以拒绝；提交后 7 天没有报价、报价过了有效期，由定时任务关闭或标为过期。</li>
 *   <li>客户勾选核对后确认合同：报价是最新版、在有效期内、档期可用、起租日没过 → 生成租赁订单、合同快照和交易订单
 *       （来源 RENTAL，收款方是出租方，金额是合计，30 分钟内付款）。</li>
 *   <li>没付款就关闭的订单不保留：报价失效，再到账的钱整笔退回（不恢复，没付款的合同不占商家档期）。</li>
 *   <li>已付款 → 待交接；商家录入交接 → 客户确认 → 使用中（从确认交接起计租）；商家录入归还 → 客户确认 → 已结束。
 *       交接之前商家可以取消并整单原路退款；客户付款后不能自己取消。</li>
 * </ul>
 */
public class RentalService {

    static final int MAX_QUANTITY = 10;
    static final int MAX_DAYS = 90;
    static final int MAX_NOTE = 200;
    static final Duration QUOTE_WINDOW = Duration.ofDays(7);
    static final Duration MAX_QUOTE_VALIDITY = Duration.ofDays(7);
    static final Duration PAY_WINDOW = Duration.ofMinutes(30);
    static final int MAX_FEES = 5;
    static final int MAX_FEE_NAME = 20;
    static final int MAX_FEE_NOTE = 40;
    static final int MAX_TERMS = 2000;
    static final int MAX_REASON = 80;
    static final int MAX_RECORD_NOTE = 200;
    static final long MAX_AMOUNT_MINOR = 10_000_000_000L;
    static final int MAX_PAGE_SIZE = 50;
    static final int JOB_BATCH = 100;
    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    /** 交接前商家取消时退款的原因：买家在订单的退款记录里看到的就是这一句。 */
    static final String CANCEL_REFUND_REASON = "出租方取消租赁订单，租金原路退回";

    private static final Logger log = LoggerFactory.getLogger(RentalService.class);
    private static final DateTimeFormatter CONTRACT_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter PERIOD = DateTimeFormatter.ofPattern("M月d日");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final RentalMapper mapper;
    private final Offers offers;
    private final Addresses addresses;
    private final Payments payments;
    private final Qualifications qualifications;
    private final Clock clock;
    private final Supplier<String> contractNumbers;

    RentalService(RentalMapper mapper, Offers offers, Addresses addresses, Payments payments,
                  Qualifications qualifications, Clock clock) {
        this(mapper, offers, addresses, payments, qualifications, clock, null);
    }

    RentalService(RentalMapper mapper, Offers offers, Addresses addresses, Payments payments,
                  Qualifications qualifications, Clock clock, Supplier<String> contractNumbers) {
        this.mapper = mapper;
        this.offers = offers;
        this.addresses = addresses;
        this.payments = payments;
        this.qualifications = qualifications;
        this.clock = clock;
        this.contractNumbers = contractNumbers != null ? contractNumbers : this::newContractNo;
    }

    // ---- 客户：询价单 ----

    /**
     * 提交询价单。同一个幂等键、同样的内容重放返回第一次的询价单（created 为 false）；换了内容拒绝。
     */
    @Transactional
    public RentalInquiryCreated submit(UUID accountId, RentalLessee lessee, UUID idempotencyKey,
                                       RentalInquiryDraft draft) {
        requireAccount(accountId);
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        if (lessee == null || blank(lessee.contactName())) {
            throw new IllegalArgumentException("lessee is required");
        }
        OffsetDateTime now = now();
        InquiryInput input = normalize(draft, now);
        String hash = hash(input);
        mapper.lockAccount(accountId);
        KeyRow replayed = mapper.findByKey(accountId, idempotencyKey);
        if (replayed != null) {
            if (!replayed.requestHash().equals(hash)) {
                throw new RentalException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new RentalInquiryCreated(readInquiry(accountId, replayed.id()), false);
        }
        Offer offer = offers.find(input.offerId()).orElseThrow(() -> new RentalException(Error.NOT_FOUND));
        Address address = addresses.find(accountId, input.addressId())
                .orElseThrow(() -> new RentalException(Error.ADDRESS_NOT_FOUND));
        if (!offer.rentable() || (offer.cityCode() != null && !offer.cityCode().equals(address.cityCode()))) {
            throw new RentalException(Error.OFFER_UNAVAILABLE);
        }
        if (!payments.merchantCanCollect(offer.merchantId())) {
            throw new RentalException(Error.PAYMENT_UNAVAILABLE);
        }
        boolean organization = lessee.organizationId() != null && !blank(lessee.organizationName());
        UUID inquiryId = UUID.randomUUID();
        mapper.insertInquiry(
                inquiryId,
                accountId,
                organization ? RentalLesseeKind.ORGANIZATION.name() : RentalLesseeKind.PERSONAL.name(),
                organization ? lessee.organizationId() : null,
                organization ? lessee.organizationName().trim() : null,
                lessee.contactName().trim(),
                offer.offerId(),
                offer.productId(),
                offer.productName(),
                offer.skuName(),
                offer.coverUrl(),
                offer.merchantId(),
                offer.merchantName(),
                offer.referenceAmountMinor() != null && offer.referenceAmountMinor() > 0
                        ? offer.referenceAmountMinor() : null,
                offer.priceUnit(),
                input.quantity(),
                input.startDate(),
                input.days(),
                input.handoverMode().name(),
                input.returnMode().name(),
                address.addressId(),
                address.recipientName(),
                address.phone(),
                address.provinceName(),
                address.cityCode(),
                address.cityName(),
                address.detail(),
                input.note(),
                now.plus(QUOTE_WINDOW),
                idempotencyKey,
                hash,
                now);
        mapper.insertEvent(inquiryId, null, "SUBMITTED", accountId, null, now);
        return new RentalInquiryCreated(readInquiry(accountId, inquiryId), true);
    }

    @Transactional(readOnly = true)
    public RentalInquiryView readInquiry(UUID accountId, UUID inquiryId) {
        requireAccount(accountId);
        InquiryRow row = mapper.findInquiry(accountId, require(inquiryId, "inquiry id"));
        if (row == null) {
            throw new RentalException(Error.NOT_FOUND);
        }
        return inquiryViews(List.of(row), false).getFirst();
    }

    @Transactional(readOnly = true)
    public RentalInquiryPageView listInquiries(UUID accountId, String statusCode, int page, int size) {
        requireAccount(accountId);
        requirePage(page, size);
        String status = parse(statusCode, RentalInquiryStatus::valueOf, "status").map(Enum::name).orElse(null);
        long total = mapper.countInquiries(accountId, status);
        List<InquiryRow> rows = total == 0 ? List.of()
                : mapper.findInquiryPage(accountId, status, size, (page - 1) * size);
        return new RentalInquiryPageView(page, size, total, (long) page * size < total, inquiryViews(rows, false));
    }

    /** 确认合同之前客户可以关闭询价；已经关闭的再关一次没有变化。 */
    @Transactional
    public RentalInquiryView closeInquiry(UUID accountId, UUID inquiryId) {
        requireAccount(accountId);
        InquiryRow row = mapper.lockInquiry(accountId, require(inquiryId, "inquiry id"));
        if (row == null) {
            throw new RentalException(Error.NOT_FOUND);
        }
        if (RentalInquiryStatus.CLOSED.name().equals(row.status())
                && RentalCloseReason.CUSTOMER.name().equals(row.closeReason())) {
            return readInquiry(accountId, inquiryId);
        }
        OffsetDateTime now = now();
        if (mapper.closeInquiry(inquiryId, RentalCloseReason.CUSTOMER.name(), now) == 0) {
            throw new RentalException(Error.STATE_INVALID);
        }
        mapper.insertEvent(inquiryId, null, "CLOSED", accountId, row.currentQuoteVersion(), now);
        return readInquiry(accountId, inquiryId);
    }

    /**
     * 确认合同：勾选核对、询价单版本（If-Match）对得上、报价是最新版且在有效期内、档期可用、起租日没过。
     * 建租赁订单、合同快照和交易订单，返回去收银台要的 ID。同一版报价已经确认过时原样返回。
     */
    @Transactional
    public RentalConfirmationView confirm(UUID accountId, UUID inquiryId, int version, Integer quoteVersion,
                                          Boolean agreed) {
        requireAccount(accountId);
        if (quoteVersion == null) {
            throw new IllegalArgumentException("quote version is required");
        }
        InquiryRow row = mapper.lockInquiry(accountId, require(inquiryId, "inquiry id"));
        if (row == null) {
            throw new RentalException(Error.NOT_FOUND);
        }
        if (RentalInquiryStatus.CONFIRMED.name().equals(row.status())) {
            if (quoteVersion.equals(row.currentQuoteVersion())) {
                OrderRow order = mapper.findOrdersOfInquiries(List.of(inquiryId)).getFirst();
                return new RentalConfirmationView(inquiryId, order.id(), order.tradeOrderId(), order.payBefore());
            }
            throw new RentalException(Error.STATE_INVALID);
        }
        if (!Boolean.TRUE.equals(agreed)) {
            throw new RentalException(Error.TERMS_NOT_AGREED);
        }
        if (RentalInquiryStatus.EXPIRED.name().equals(row.status())) {
            throw new RentalException(Error.QUOTE_EXPIRED);
        }
        if (!RentalInquiryStatus.QUOTED.name().equals(row.status())) {
            throw new RentalException(Error.STATE_INVALID);
        }
        if (row.version() != version) {
            throw new RentalException(Error.VERSION_CONFLICT);
        }
        if (!quoteVersion.equals(row.currentQuoteVersion())) {
            throw new RentalException(Error.QUOTE_CHANGED);
        }
        QuoteRow quote = mapper.findQuote(inquiryId, quoteVersion);
        OffsetDateTime now = now();
        if (!quote.validUntil().isAfter(now)) {
            throw new RentalException(Error.QUOTE_EXPIRED);
        }
        if (!quote.scheduleAvailable()) {
            throw new RentalException(Error.SCHEDULE_UNAVAILABLE);
        }
        if (row.startDate().isBefore(today(now))) {
            throw new RentalException(Error.START_DATE_PASSED);
        }
        UUID orderId = UUID.randomUUID();
        List<FeeRow> fees = mapper.findFees(List.of(quote.id()));
        LocalDate end = row.startDate().plusDays(quote.days() - 1L);
        List<PaymentLine> lines = new ArrayList<>();
        lines.add(new PaymentLine(row.productId(), row.offerId(), row.productName(),
                PERIOD.format(row.startDate()) + "–" + PERIOD.format(end) + " 共 " + quote.days() + " 天",
                row.coverUrl(), quote.dailyRentMinor() * quote.days(), quote.quantity()));
        // 交易订单的行要有正的单价：为 0 的费用行（如“送达与取回 · 含”）只写在合同里。费用行用名称算出稳定的 ID。
        for (FeeRow fee : fees) {
            if (fee.amountMinor() > 0) {
                lines.add(new PaymentLine(row.productId(),
                        UUID.nameUUIDFromBytes(("rental-fee:" + quote.id() + ":" + fee.name())
                                .getBytes(StandardCharsets.UTF_8)),
                        row.productName(), fee.name(), null, fee.amountMinor(), 1));
            }
        }
        OpenedPayment opened = payments.open(new OpenPayment(orderId, accountId, row.merchantId(), row.cityCode(),
                lines, now.plus(PAY_WINDOW).toInstant()));
        mapper.insertOrder(orderId, inquiryId, quote.id(), accountId, row.merchantId(), opened.tradeOrderId(),
                quote.totalMinor(), OffsetDateTime.ofInstant(opened.payBefore(), ZoneOffset.UTC), now);
        mapper.insertContract(orderId, contractNumbers.get(), inquiryId, quote.id(), now);
        if (mapper.confirmInquiry(inquiryId, version, now) == 0) {
            throw new RentalException(Error.VERSION_CONFLICT);
        }
        mapper.insertEvent(inquiryId, orderId, "CONFIRMED", accountId, quoteVersion, now);
        return new RentalConfirmationView(inquiryId, orderId, opened.tradeOrderId(),
                OffsetDateTime.ofInstant(opened.payBefore(), ZoneOffset.UTC));
    }

    // ---- 客户：租赁订单 ----

    @Transactional(readOnly = true)
    public RentalOrderView readOrder(UUID accountId, UUID orderId) {
        requireAccount(accountId);
        OrderRow row = mapper.findOrder(accountId, require(orderId, "order id"));
        if (row == null) {
            throw new RentalException(Error.NOT_FOUND);
        }
        return orderViews(List.of(row), false).getFirst();
    }

    @Transactional(readOnly = true)
    public RentalOrderPageView listOrders(UUID accountId, String statusCode, int page, int size) {
        requireAccount(accountId);
        requirePage(page, size);
        String status = parse(statusCode, RentalOrderStatus::valueOf, "status").map(Enum::name).orElse(null);
        long total = mapper.countOrders(accountId, status);
        List<OrderRow> rows = total == 0 ? List.of() : mapper.findOrderPage(accountId, status, size, (page - 1) * size);
        return new RentalOrderPageView(page, size, total, (long) page * size < total, orderViews(rows, false));
    }

    /** 客户确认商家录入的交接记录：使用中，从现在起计租。已经确认过的原样返回。 */
    @Transactional
    public RentalOrderView confirmHandover(UUID accountId, UUID orderId) {
        return confirmRecord(accountId, orderId, "HANDOVER", RentalOrderStatus.HANDOVER_RECORDED,
                RentalOrderStatus.IN_USE, "HANDOVER_CONFIRMED");
    }

    /** 客户确认商家录入的归还记录：订单结束。已经确认过的原样返回。 */
    @Transactional
    public RentalOrderView confirmReturn(UUID accountId, UUID orderId) {
        return confirmRecord(accountId, orderId, "RETURN", RentalOrderStatus.RETURN_RECORDED,
                RentalOrderStatus.ENDED, "RETURN_CONFIRMED");
    }

    private RentalOrderView confirmRecord(UUID accountId, UUID orderId, String kind, RentalOrderStatus from,
                                          RentalOrderStatus to, String event) {
        requireAccount(accountId);
        OrderRow row = mapper.lockOrder(accountId, require(orderId, "order id"));
        if (row == null) {
            throw new RentalException(Error.NOT_FOUND);
        }
        RentalOrderStatus status = RentalOrderStatus.valueOf(row.status());
        if (status == from) {
            OffsetDateTime now = now();
            mapper.confirmRecord(orderId, kind, accountId, now);
            mapper.moveOrder(orderId, from.name(), to.name(), now);
            mapper.insertEvent(row.inquiryId(), orderId, event, accountId, null, now);
        } else if (!alreadyPast(status, to)) {
            throw new RentalException(Error.STATE_INVALID);
        }
        return readOrder(accountId, orderId);
    }

    /** 订单已经走到 target 或之后（重复确认）。 */
    private static boolean alreadyPast(RentalOrderStatus status, RentalOrderStatus target) {
        List<RentalOrderStatus> line = List.of(RentalOrderStatus.IN_USE, RentalOrderStatus.RETURN_RECORDED,
                RentalOrderStatus.ENDED);
        return line.contains(status) && line.indexOf(status) >= line.indexOf(target);
    }

    // ---- 商家：询价单和报价 ----

    @Transactional(readOnly = true)
    public RentalInquiryPageView merchantInquiries(UUID merchantId, String statusCode, int page, int size) {
        requireMerchant(merchantId);
        requirePage(page, size);
        String status = parse(statusCode, RentalInquiryStatus::valueOf, "status").map(Enum::name).orElse(null);
        long total = mapper.countMerchantInquiries(merchantId, status);
        List<InquiryRow> rows = total == 0 ? List.of()
                : mapper.findMerchantInquiryPage(merchantId, status, size, (page - 1) * size);
        return new RentalInquiryPageView(page, size, total, (long) page * size < total, inquiryViews(rows, true));
    }

    @Transactional(readOnly = true)
    public RentalInquiryView merchantInquiry(UUID merchantId, UUID inquiryId) {
        requireMerchant(merchantId);
        InquiryRow row = mapper.findMerchantInquiry(merchantId, require(inquiryId, "inquiry id"));
        if (row == null) {
            throw new RentalException(Error.NOT_FOUND);
        }
        return inquiryViews(List.of(row), true).getFirst();
    }

    /**
     * 报价或改价：待报价、待确认、报价已过期的询价单可以报；每次是新的一版（内容和最新一版一样、还没过期时没有变化）。
     */
    @Transactional
    public RentalInquiryView quote(UUID merchantId, UUID operatorId, UUID inquiryId, RentalQuoteDraft draft) {
        requireMerchant(merchantId);
        requireAccount(operatorId);
        OffsetDateTime now = now();
        QuoteInput input = normalizeQuote(draft, now);
        InquiryRow row = mapper.lockMerchantInquiry(merchantId, require(inquiryId, "inquiry id"));
        if (row == null) {
            throw new RentalException(Error.NOT_FOUND);
        }
        RentalInquiryStatus status = RentalInquiryStatus.valueOf(row.status());
        if (status != RentalInquiryStatus.PENDING && status != RentalInquiryStatus.QUOTED
                && status != RentalInquiryStatus.EXPIRED) {
            throw new RentalException(Error.STATE_INVALID);
        }
        if (status == RentalInquiryStatus.QUOTED && sameAsCurrent(row, input)) {
            return merchantInquiry(merchantId, inquiryId);
        }
        int next = row.currentQuoteVersion() == null ? 1 : row.currentQuoteVersion() + 1;
        UUID quoteId = mapper.insertQuote(inquiryId, next, input.dailyRentMinor(), input.days(), input.quantity(),
                input.rentMinor(), input.totalMinor(), input.scheduleAvailable(), input.validUntil(), input.terms(),
                operatorId, now);
        int sort = 0;
        for (RentalFeeView fee : input.fees()) {
            mapper.insertFee(quoteId, fee.name(), fee.note(), fee.amountMinor(), sort);
            sort += 10;
        }
        mapper.markQuoted(inquiryId, next, now);
        mapper.insertEvent(inquiryId, null, next == 1 ? "QUOTED" : "QUOTE_REVISED", operatorId, next, now);
        return merchantInquiry(merchantId, inquiryId);
    }

    /** 拒绝询价（写原因）；已经拒绝的原样返回。 */
    @Transactional
    public RentalInquiryView decline(UUID merchantId, UUID operatorId, UUID inquiryId, String reasonText) {
        requireMerchant(merchantId);
        requireAccount(operatorId);
        String reason = required(reasonText, MAX_REASON, "reason");
        InquiryRow row = mapper.lockMerchantInquiry(merchantId, require(inquiryId, "inquiry id"));
        if (row == null) {
            throw new RentalException(Error.NOT_FOUND);
        }
        if (RentalInquiryStatus.DECLINED.name().equals(row.status())) {
            return merchantInquiry(merchantId, inquiryId);
        }
        OffsetDateTime now = now();
        if (mapper.declineInquiry(inquiryId, reason, now) == 0) {
            throw new RentalException(Error.STATE_INVALID);
        }
        mapper.insertEvent(inquiryId, null, "DECLINED", operatorId, row.currentQuoteVersion(), now);
        return merchantInquiry(merchantId, inquiryId);
    }

    // ---- 商家：租赁订单 ----

    @Transactional(readOnly = true)
    public RentalOrderPageView merchantOrders(UUID merchantId, String statusCode, int page, int size) {
        requireMerchant(merchantId);
        requirePage(page, size);
        String status = parse(statusCode, RentalOrderStatus::valueOf, "status").map(Enum::name).orElse(null);
        long total = mapper.countMerchantOrders(merchantId, status);
        List<OrderRow> rows = total == 0 ? List.of()
                : mapper.findMerchantOrderPage(merchantId, status, size, (page - 1) * size);
        return new RentalOrderPageView(page, size, total, (long) page * size < total, orderViews(rows, true));
    }

    @Transactional(readOnly = true)
    public RentalOrderView merchantOrder(UUID merchantId, UUID orderId) {
        requireMerchant(merchantId);
        OrderRow row = mapper.findMerchantOrder(merchantId, require(orderId, "order id"));
        if (row == null) {
            throw new RentalException(Error.NOT_FOUND);
        }
        return orderViews(List.of(row), true).getFirst();
    }

    /** 录入（或在客户确认之前改）交接记录：待交接 → 商家已录入交接。 */
    @Transactional
    public RentalOrderView recordHandover(UUID merchantId, UUID operatorId, UUID orderId, RentalRecordDraft draft) {
        return record(merchantId, operatorId, orderId, draft, "HANDOVER", RentalOrderStatus.AWAITING_HANDOVER,
                RentalOrderStatus.HANDOVER_RECORDED);
    }

    /** 录入（或在客户确认之前改）归还记录：使用中 → 商家已录入归还。 */
    @Transactional
    public RentalOrderView recordReturn(UUID merchantId, UUID operatorId, UUID orderId, RentalRecordDraft draft) {
        return record(merchantId, operatorId, orderId, draft, "RETURN", RentalOrderStatus.IN_USE,
                RentalOrderStatus.RETURN_RECORDED);
    }

    private RentalOrderView record(UUID merchantId, UUID operatorId, UUID orderId, RentalRecordDraft draft,
                                   String kind, RentalOrderStatus from, RentalOrderStatus recorded) {
        requireMerchant(merchantId);
        requireAccount(operatorId);
        OffsetDateTime now = now();
        if (draft == null) {
            throw new IllegalArgumentException("record is required");
        }
        String note = required(draft.note(), MAX_RECORD_NOTE, "note");
        OffsetDateTime occurredAt = draft.occurredAt() == null ? now : draft.occurredAt();
        if (occurredAt.isAfter(now.plusMinutes(5))) {
            throw new IllegalArgumentException("record time is in the future");
        }
        OrderRow row = mapper.lockMerchantOrder(merchantId, require(orderId, "order id"));
        if (row == null) {
            throw new RentalException(Error.NOT_FOUND);
        }
        RentalOrderStatus status = RentalOrderStatus.valueOf(row.status());
        if (status != from && status != recorded) {
            throw new RentalException(Error.STATE_INVALID);
        }
        if (occurredAt.isBefore(row.createdAt())) {
            throw new IllegalArgumentException("record time is before the contract was confirmed");
        }
        if (mapper.saveRecord(orderId, kind, occurredAt, note, operatorId, now) == 0) {
            throw new RentalException(Error.STATE_INVALID);
        }
        if (status == from) {
            mapper.moveOrder(orderId, from.name(), recorded.name(), now);
        }
        mapper.insertEvent(row.inquiryId(), orderId, kind + "_RECORDED", operatorId, null, now);
        return merchantOrder(merchantId, orderId);
    }

    /** 交接之前商家取消订单，整单原路退款（提交后交给渠道）。已经取消的原样返回。 */
    @Transactional
    public RentalOrderView cancel(UUID merchantId, UUID operatorId, UUID orderId, String reasonText) {
        requireMerchant(merchantId);
        requireAccount(operatorId);
        String reason = required(reasonText, MAX_REASON, "reason");
        OrderRow found = mapper.findMerchantOrder(merchantId, require(orderId, "order id"));
        if (found == null) {
            throw new RentalException(Error.NOT_FOUND);
        }
        payments.lockOrder(found.tradeOrderId());
        OrderRow row = mapper.lockMerchantOrder(merchantId, orderId);
        RentalOrderStatus status = RentalOrderStatus.valueOf(row.status());
        if (status == RentalOrderStatus.CANCELLED) {
            return merchantOrder(merchantId, orderId);
        }
        if (status != RentalOrderStatus.AWAITING_HANDOVER && status != RentalOrderStatus.HANDOVER_RECORDED) {
            throw new RentalException(Error.STATE_INVALID);
        }
        OffsetDateTime now = now();
        UUID refundId = payments.requestRefund(row.tradeOrderId(), row.totalMinor(), CANCEL_REFUND_REASON,
                UUID.nameUUIDFromBytes(("rental-cancel:" + orderId).getBytes(StandardCharsets.UTF_8)), operatorId);
        mapper.cancelOrder(orderId, reason, refundId, now);
        mapper.insertEvent(row.inquiryId(), orderId, "CANCELLED", operatorId, null, now);
        return merchantOrder(merchantId, orderId);
    }

    // ---- 付款结果（交易模块在改交易订单状态的同一个事务里调用，交易订单已锁） ----

    /**
     * 付款到账：待付款 → 待交接。已经处理过的直接收下。没付款就关闭了的不恢复（报价已失效、档期已释放），
     * 返回 false 让交易模块整笔原路退款。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean onPaymentConfirmed(UUID tradeOrderId, UUID rentalOrderId) {
        OrderRow row = mapper.lockPaidOrder(rentalOrderId, tradeOrderId);
        if (row == null) {
            log.error("trade order {} was paid for an unknown rental order {}", tradeOrderId, rentalOrderId);
            return false;
        }
        OffsetDateTime now = now();
        return switch (RentalOrderStatus.valueOf(row.status())) {
            case PENDING_PAYMENT -> {
                mapper.markPaid(rentalOrderId, now);
                mapper.insertEvent(row.inquiryId(), rentalOrderId, "PAYMENT_CONFIRMED", null, null, now);
                yield true;
            }
            case CLOSED -> {
                log.warn("rental order {} was paid after it closed; the payment will be refunded", rentalOrderId);
                mapper.insertEvent(row.inquiryId(), rentalOrderId, "LATE_PAYMENT_REFUSED", null, null, now);
                yield false;
            }
            default -> true;
        };
    }

    /** 交易订单没付款就关闭了（超时或取消付款）：租赁订单关闭。别的状态不动。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaymentClosed(UUID tradeOrderId, UUID rentalOrderId) {
        OrderRow row = mapper.lockPaidOrder(rentalOrderId, tradeOrderId);
        if (row == null) {
            log.error("trade order {} closed for an unknown rental order {}", tradeOrderId, rentalOrderId);
            return;
        }
        OffsetDateTime now = now();
        if (mapper.closeUnpaid(rentalOrderId, now) > 0) {
            mapper.insertEvent(row.inquiryId(), rentalOrderId, "PAYMENT_CLOSED", null, null, now);
        }
    }

    // ---- 定时任务 ----

    /** 提交 7 天还没有报价的询价单关闭；报价过了有效期的标为过期。返回处理的份数。 */
    @Transactional
    public int closeLapsed() {
        OffsetDateTime now = now();
        int changed = 0;
        for (InquiryRow row : mapper.lockUnquoted(now, JOB_BATCH)) {
            if (mapper.closeInquiry(row.id(), RentalCloseReason.NO_QUOTE.name(), now) > 0) {
                mapper.insertEvent(row.id(), null, "CLOSED_NO_QUOTE", null, null, now);
                changed++;
            }
        }
        for (InquiryRow row : mapper.lockLapsedQuotes(now, JOB_BATCH)) {
            if (mapper.expireInquiry(row.id(), now) > 0) {
                mapper.insertEvent(row.id(), null, "QUOTE_EXPIRED", null, row.currentQuoteVersion(), now);
                changed++;
            }
        }
        return changed;
    }

    // ---- 转换 ----

    private List<RentalInquiryView> inquiryViews(List<InquiryRow> rows, boolean forMerchant) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(InquiryRow::id).toList();
        Map<UUID, QuoteRow> quotes = mapper.findCurrentQuotes(ids).stream()
                .collect(Collectors.toMap(QuoteRow::inquiryId, Function.identity()));
        Map<UUID, List<RentalFeeView>> fees = feesOf(quotes.values().stream().map(QuoteRow::id).toList());
        Map<UUID, OrderSummaryRow> orders = mapper.findOrderSummaries(ids).stream()
                .collect(Collectors.toMap(OrderSummaryRow::inquiryId, Function.identity()));
        Set<UUID> reviewed = qualifications.reviewed(rows.stream().map(InquiryRow::merchantId).distinct().toList());
        OffsetDateTime now = now();
        return rows.stream().map(row -> {
            QuoteRow quote = quotes.get(row.id());
            OrderSummaryRow order = orders.get(row.id());
            RentalInquiryStatus status = RentalInquiryStatus.valueOf(row.status());
            boolean paid = order != null && !RentalOrderStatus.PENDING_PAYMENT.name().equals(order.status())
                    && !RentalOrderStatus.CLOSED.name().equals(order.status());
            int days = quote == null ? row.days() : quote.days();
            RentalQuoteView quoteView = quote == null ? null : new RentalQuoteView(
                    quote.quoteVersion(), quote.dailyRentMinor(), quote.days(), quote.quantity(), quote.rentMinor(),
                    fees.getOrDefault(quote.id(), List.of()), quote.totalMinor(), quote.scheduleAvailable(),
                    quote.validUntil(), !quote.validUntil().isAfter(now), quote.terms(), quote.quotedAt());
            boolean confirmable = status == RentalInquiryStatus.QUOTED && quote != null
                    && quote.validUntil().isAfter(now) && quote.scheduleAvailable()
                    && !row.startDate().isBefore(today(now));
            return new RentalInquiryView(
                    row.id(),
                    status,
                    row.closeReason() == null ? null : RentalCloseReason.valueOf(row.closeReason()),
                    row.declineReason(),
                    lessee(row.lesseeKind(), row.lesseeOrganizationName(), row.lesseeContactName()),
                    new RentalMerchantView(row.merchantId(), row.merchantName(), reviewed.contains(row.merchantId())),
                    new RentalItemView(row.offerId(), row.productId(), row.productName(), row.skuName(),
                            row.coverUrl(), row.referenceAmountMinor(), row.referenceUnit()),
                    quote == null ? row.quantity() : quote.quantity(),
                    row.startDate(),
                    row.startDate().plusDays(days - 1L),
                    days,
                    RentalHandoverMode.valueOf(row.handoverMode()),
                    RentalReturnMode.valueOf(row.returnMode()),
                    new RentalPlaceView(row.addressId(), row.recipientName(),
                            forMerchant && !paid ? mask(row.recipientPhone()) : row.recipientPhone(),
                            row.provinceName(), row.cityCode(), row.cityName(), row.addressDetail()),
                    row.note(),
                    row.createdAt(),
                    row.quoteDeadline(),
                    quoteView,
                    !forMerchant && confirmable,
                    !forMerchant && (status == RentalInquiryStatus.PENDING || status == RentalInquiryStatus.QUOTED
                            || status == RentalInquiryStatus.EXPIRED),
                    order == null ? null : new RentalOrderSummaryView(order.rentalOrderId(),
                            RentalOrderStatus.valueOf(order.status()), order.tradeOrderId()),
                    row.updatedAt(),
                    row.version());
        }).toList();
    }

    private List<RentalOrderView> orderViews(List<OrderRow> rows, boolean forMerchant) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rows.stream().map(OrderRow::id).toList();
        Map<UUID, ContractRow> contracts = mapper.findContracts(ids).stream()
                .collect(Collectors.toMap(ContractRow::orderId, Function.identity()));
        Map<UUID, List<RentalFeeView>> fees = feesOf(contracts.values().stream().map(ContractRow::quoteId).toList());
        Map<String, RecordRow> records = mapper.findRecords(ids).stream()
                .collect(Collectors.toMap(record -> record.orderId() + ":" + record.kind(), Function.identity()));
        List<UUID> refundIds = rows.stream().map(OrderRow::refundId).filter(Objects::nonNull).toList();
        Map<UUID, RefundProgress> refunds = refundIds.isEmpty() ? Map.of() : payments.refundStates(refundIds);
        Set<UUID> reviewed = qualifications.reviewed(
                contracts.values().stream().map(ContractRow::lessorId).distinct().toList());
        return rows.stream().map(row -> {
            ContractRow contract = contracts.get(row.id());
            RentalOrderStatus status = RentalOrderStatus.valueOf(row.status());
            boolean paid = row.paidAt() != null;
            RefundProgress refund = row.refundId() == null ? null : refunds.get(row.refundId());
            return new RentalOrderView(
                    row.id(),
                    row.inquiryId(),
                    status,
                    contractView(contract, fees.getOrDefault(contract.quoteId(), List.of()), forMerchant && !paid,
                            reviewed.contains(contract.lessorId())),
                    new RentalPaymentView(row.tradeOrderId(), row.totalMinor(), row.payBefore(), row.paidAt()),
                    recordView(records.get(row.id() + ":HANDOVER")),
                    recordView(records.get(row.id() + ":RETURN")),
                    row.rentStartedAt(),
                    row.endedAt(),
                    row.closedAt(),
                    row.cancelledAt(),
                    row.cancelReason(),
                    row.refundId() == null ? null : new RentalRefundView(row.refundId(),
                            refund == null ? row.totalMinor() : refund.amountMinor(),
                            refund == null ? "REQUESTED" : refund.status()),
                    !forMerchant && status == RentalOrderStatus.HANDOVER_RECORDED,
                    !forMerchant && status == RentalOrderStatus.RETURN_RECORDED,
                    row.createdAt(),
                    row.updatedAt(),
                    row.version());
        }).toList();
    }

    private static RentalContractView contractView(ContractRow row, List<RentalFeeView> fees, boolean maskPhone,
                                                   boolean lessorReviewed) {
        return new RentalContractView(
                row.contractNo(),
                row.confirmedAt(),
                row.quoteVersion(),
                lessee(row.lesseeKind(), row.lesseeOrganizationName(), row.lesseeContactName()),
                new RentalMerchantView(row.lessorId(), row.lessorName(), lessorReviewed),
                row.productName(),
                row.skuName(),
                row.quantity(),
                row.startDate(),
                row.endDate(),
                row.days(),
                row.dailyRentMinor(),
                row.rentMinor(),
                fees,
                row.totalMinor(),
                RentalHandoverMode.valueOf(row.handoverMode()),
                RentalReturnMode.valueOf(row.returnMode()),
                new RentalPlaceView(null, row.recipientName(),
                        maskPhone ? mask(row.recipientPhone()) : row.recipientPhone(), row.provinceName(),
                        row.cityCode(), row.cityName(), row.addressDetail()),
                row.terms());
    }

    private static RentalRecordView recordView(RecordRow row) {
        return row == null ? null
                : new RentalRecordView(row.occurredAt(), row.note(), row.recordedAt(), row.confirmedAt());
    }

    private Map<UUID, List<RentalFeeView>> feesOf(List<UUID> quoteIds) {
        if (quoteIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<RentalFeeView>> fees = new LinkedHashMap<>();
        for (FeeRow fee : mapper.findFees(quoteIds)) {
            fees.computeIfAbsent(fee.quoteId(), id -> new ArrayList<>())
                    .add(new RentalFeeView(fee.name(), fee.note(), fee.amountMinor()));
        }
        return fees;
    }

    private static RentalLesseeView lessee(String kind, String organizationName, String contactName) {
        return new RentalLesseeView(RentalLesseeKind.valueOf(kind), organizationName, contactName);
    }

    /** 手机号打码：138****1234。 */
    static String mask(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private boolean sameAsCurrent(InquiryRow row, QuoteInput input) {
        QuoteRow current = mapper.findQuote(row.id(), row.currentQuoteVersion());
        if (current == null || !current.validUntil().isAfter(now())) {
            return false;
        }
        List<RentalFeeView> fees = feesOf(List.of(current.id())).getOrDefault(current.id(), List.of());
        return current.dailyRentMinor() == input.dailyRentMinor()
                && current.days() == input.days()
                && current.quantity() == input.quantity()
                && current.scheduleAvailable() == input.scheduleAvailable()
                && current.validUntil().toInstant().equals(input.validUntil().toInstant())
                && current.terms().equals(input.terms())
                && fees.equals(input.fees());
    }

    // ---- 校验 ----

    private InquiryInput normalize(RentalInquiryDraft draft, OffsetDateTime now) {
        if (draft == null || draft.offerId() == null || draft.addressId() == null) {
            throw new IllegalArgumentException("offer and address are required");
        }
        int quantity = range(draft.quantity(), 1, MAX_QUANTITY, "quantity");
        int days = range(draft.days(), 1, MAX_DAYS, "days");
        LocalDate start;
        try {
            start = LocalDate.parse(required(draft.startDate(), 10, "start date"));
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("start date is invalid");
        }
        if (!start.isAfter(today(now))) {
            throw new IllegalArgumentException("start date must be tomorrow or later");
        }
        RentalHandoverMode handover = parse(draft.handoverMode(), RentalHandoverMode::valueOf, "handover mode")
                .orElseThrow(() -> new IllegalArgumentException("handover mode is required"));
        RentalReturnMode giveBack = parse(draft.returnMode(), RentalReturnMode::valueOf, "return mode")
                .orElseThrow(() -> new IllegalArgumentException("return mode is required"));
        return new InquiryInput(draft.offerId(), quantity, start, days, handover, giveBack, draft.addressId(),
                text(draft.note(), MAX_NOTE, "note", true));
    }

    private static QuoteInput normalizeQuote(RentalQuoteDraft draft, OffsetDateTime now) {
        if (draft == null) {
            throw new IllegalArgumentException("quote is required");
        }
        if (draft.dailyRentMinor() == null || draft.dailyRentMinor() < 1 || draft.dailyRentMinor() > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("daily rent is invalid");
        }
        int days = range(draft.days(), 1, MAX_DAYS, "days");
        int quantity = range(draft.quantity(), 1, MAX_QUANTITY, "quantity");
        if (draft.scheduleAvailable() == null) {
            throw new IllegalArgumentException("schedule availability is required");
        }
        if (draft.validUntil() == null || !draft.validUntil().isAfter(now)
                || draft.validUntil().isAfter(now.plus(MAX_QUOTE_VALIDITY))) {
            throw new IllegalArgumentException("validity is invalid");
        }
        String terms = required(draft.terms(), MAX_TERMS, "terms", true);
        List<RentalFeeDraft> feeDrafts = draft.fees() == null ? List.of() : draft.fees();
        if (feeDrafts.size() > MAX_FEES) {
            throw new IllegalArgumentException("too many fees");
        }
        List<RentalFeeView> fees = new ArrayList<>();
        for (RentalFeeDraft fee : feeDrafts) {
            if (fee == null || fee.amountMinor() == null || fee.amountMinor() < 0
                    || fee.amountMinor() > MAX_AMOUNT_MINOR) {
                throw new IllegalArgumentException("fee is invalid");
            }
            String name = required(fee.name(), MAX_FEE_NAME, "fee name");
            if (name.equals("租金") || fees.stream().anyMatch(existing -> existing.name().equals(name))) {
                throw new IllegalArgumentException("fee names must be distinct");
            }
            fees.add(new RentalFeeView(name, text(fee.note(), MAX_FEE_NOTE, "fee note", false), fee.amountMinor()));
        }
        long rent = Math.multiplyExact(Math.multiplyExact(draft.dailyRentMinor(), (long) days), (long) quantity);
        long total = rent;
        for (RentalFeeView fee : fees) {
            total = Math.addExact(total, fee.amountMinor());
        }
        if (total > MAX_AMOUNT_MINOR) {
            throw new IllegalArgumentException("total is too large");
        }
        return new QuoteInput(draft.dailyRentMinor(), days, quantity, rent, fees, total,
                draft.scheduleAvailable(), draft.validUntil(), terms);
    }

    private static int range(Integer value, int min, int max, String what) {
        if (value == null || value < min || value > max) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return value;
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

    /** 去掉首尾空白，空的当作没填；不能超长、不能有控制字符（多行的可以换行）。 */
    private static String text(String value, int max, String what, boolean multiline) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        boolean control = trimmed.chars().anyMatch(ch -> Character.isISOControl(ch) && !(multiline && ch == '\n'));
        if (trimmed.length() > max || control) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return trimmed;
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
            throw new RentalException(Error.FORBIDDEN);
        }
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String hash(InquiryInput input) {
        String text = String.join("|", input.offerId().toString(), String.valueOf(input.quantity()),
                input.startDate().toString(), String.valueOf(input.days()), input.handoverMode().name(),
                input.returnMode().name(), input.addressId().toString(), Objects.toString(input.note(), ""));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** 合同编号：RC + 北京日期 + 6 位随机数，如 RC20261119048213。 */
    private String newContractNo() {
        return "RC" + CONTRACT_DATE.format(today(now())) + String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static LocalDate today(OffsetDateTime now) {
        return now.atZoneSameInstant(BEIJING).toLocalDate();
    }

    private record InquiryInput(
            UUID offerId,
            int quantity,
            LocalDate startDate,
            int days,
            RentalHandoverMode handoverMode,
            RentalReturnMode returnMode,
            UUID addressId,
            String note
    ) {
    }

    private record QuoteInput(
            long dailyRentMinor,
            int days,
            int quantity,
            long rentMinor,
            List<RentalFeeView> fees,
            long totalMinor,
            boolean scheduleAvailable,
            OffsetDateTime validUntil,
            String terms
    ) {
    }
}
