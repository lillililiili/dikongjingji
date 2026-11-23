package com.lowaltitude.inspection.serviceorder.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.PaymentStatus;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderCustomerKind;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderDeliveryStatus;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderEventType;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderParty;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderRefundKind;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderStatus;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderType;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderException.Error;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.DownloadIntentView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.OpeningLine;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.PlanDraft;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderActionsView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderCustomerView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderDeliveryView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderEventView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderFileContent;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderFileView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderLineView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderOpening;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderPageView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderPaymentStartView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderPaymentView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderPlanView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderProviderView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderQuoteView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderRefundView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderSummaryView;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderViews.ServiceOrderView;
import com.lowaltitude.inspection.serviceorder.infrastructure.persistence.ServiceOrderMapper;
import com.lowaltitude.inspection.serviceorder.infrastructure.persistence.ServiceOrderMapper.DeliveryRow;
import com.lowaltitude.inspection.serviceorder.infrastructure.persistence.ServiceOrderMapper.EventRow;
import com.lowaltitude.inspection.serviceorder.infrastructure.persistence.ServiceOrderMapper.FileRow;
import com.lowaltitude.inspection.serviceorder.infrastructure.persistence.ServiceOrderMapper.LineRow;
import com.lowaltitude.inspection.serviceorder.infrastructure.persistence.ServiceOrderMapper.OrderRow;
import com.lowaltitude.inspection.serviceorder.infrastructure.persistence.ServiceOrderMapper.PaymentRow;
import com.lowaltitude.inspection.serviceorder.infrastructure.persistence.ServiceOrderMapper.PlanRow;
import com.lowaltitude.inspection.serviceorder.infrastructure.persistence.ServiceOrderMapper.RefundRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 通用服务订单（S4-3，docs/redesign/03-App实现任务计划.md §28，契约 openapi-service-order.yaml 1.0.0）。
 *
 * <p>状态只由这里按规则改：服务商出方案、客户确认、交易模块回告付款、服务商开工和交付、客户验收或退回、
 * 开工前取消、开工后协商终止。凡是同时动交易订单和服务订单的路径，先锁交易订单再锁服务订单。
 * 要向渠道查单关单的路径（取消一张正在付款的订单）不能在事务里做，由 {@link Transactions} 分段执行。
 */
public class ServiceOrderService {

    private static final Logger log = LoggerFactory.getLogger(ServiceOrderService.class);

    /** 一次付款尝试的付款时限。 */
    static final Duration PAY_WINDOW = Duration.ofMinutes(15);
    /** 客户下载链接的有效期。 */
    static final Duration DOWNLOAD_TTL = Duration.ofMinutes(10);
    static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    /** 还没交付的文件最多几个；一次交付最多几个。 */
    static final int MAX_STAGED_FILES = 10;
    static final int MAX_DELIVERY_FILES = 10;
    static final int MAX_FILE_NAME = 120;
    static final int MAX_LINES = 10;
    static final int MAX_TERMS = 5;
    static final int MAX_TERM = 60;
    static final int MAX_SCHEDULE = 60;
    static final int MAX_PLAN_NOTE = 500;
    static final int MAX_DELIVERY_NOTE = 1000;
    static final int MAX_CANCEL_REASON = 80;
    static final int MAX_REVIEW_REASON = 200;
    static final int MAX_TERMINATE_REASON = 200;
    static final int MAX_PAGE_SIZE = 50;
    static final String DEFAULT_LINE_NAME = "服务费用";
    static final String CUSTOMER_CANCEL_REASON = "客户取消";
    static final String CANCEL_REFUND_REASON = "服务订单开工前取消，整笔原路退款";
    static final String TERMINATE_REFUND_REASON = "服务订单协商终止，服务商退款";
    static final String DOWNLOAD_PATH = "/api/v1/service-order-files/";
    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter ORDER_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String TERM_SEPARATOR = "\n";

    private final ServiceOrderMapper mapper;
    private final ServiceOrderPorts.Payments payments;
    private final ServiceOrderPorts.People people;
    private final ServiceOrderPorts.Files files;
    private final Transactions transactions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    ServiceOrderService(
            ServiceOrderMapper mapper,
            ServiceOrderPorts.Payments payments,
            ServiceOrderPorts.People people,
            ServiceOrderPorts.Files files,
            Transactions transactions,
            Clock clock
    ) {
        this.mapper = mapper;
        this.payments = payments;
        this.people = people;
        this.files = files;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** 在新事务里执行一段（取消、上传这种要在事务外调渠道或存文件的路径分段用）。 */
    public interface Transactions {

        <T> T inTransaction(Supplier<T> work);
    }

    // ---- 建单（需求模块选定报价时，在它的事务里） ----

    /** 同一份需求已经有订单时返回它（不建第二张）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID open(ServiceOrderOpening opening) {
        Objects.requireNonNull(opening, "opening");
        UUID existing = mapper.findIdByDemand(require(opening.demandId(), "demand id"));
        if (existing != null) {
            return existing;
        }
        ServiceOrderType type = ServiceOrderType.valueOf(opening.type());
        if (opening.amountMinor() < 1) {
            throw new IllegalArgumentException("amount must be positive");
        }
        List<OpeningLine> lines = opening.lines() == null || opening.lines().isEmpty()
                ? List.of(new OpeningLine(DEFAULT_LINE_NAME, null, null, null, opening.amountMinor()))
                : opening.lines();
        if (lines.size() > MAX_LINES || lines.stream().mapToLong(OpeningLine::amountMinor).sum() != opening.amountMinor()) {
            throw new IllegalArgumentException("quote lines do not add up to the amount");
        }
        boolean organization = opening.organizationId() != null && opening.organizationName() != null;
        String contact = people.displayName(opening.accountId()).filter(name -> !name.isBlank()).orElse("经办人");
        UUID orderId = UUID.randomUUID();
        OffsetDateTime now = now();
        mapper.insertOrder(orderId, newOrderNo(now), opening.demandId(), require(opening.quoteId(), "quote id"),
                opening.quoteVersion(), require(opening.accountId(), "account id"),
                (organization ? ServiceOrderCustomerKind.ORGANIZATION : ServiceOrderCustomerKind.PERSONAL).name(),
                organization ? opening.organizationId() : null, organization ? opening.organizationName() : null,
                truncate(contact, 80), require(opening.providerId(), "provider id"), opening.providerName(), type.name(),
                opening.title(), opening.cityCode(), opening.cityName(), opening.address(), opening.scope(),
                opening.dueDate(), opening.amountMinor(), opening.method(), opening.schedule(), opening.durationDays(),
                opening.deliverables(), opening.team(), opening.nightWork(), opening.reportHours(),
                opening.archiveDays(), opening.firstVisitDate(), now);
        int sort = 0;
        for (OpeningLine line : lines) {
            mapper.insertLine(orderId, sort, line.name(), line.quantity(), line.unit(), line.unitAmountMinor(),
                    line.amountMinor());
            sort += 10;
        }
        mapper.insertEvent(orderId, ServiceOrderEventType.CREATED.name(), opening.accountId(), null, null, now);
        return orderId;
    }

    // ---- 读 ----

    @Transactional(readOnly = true)
    public ServiceOrderView readMine(UUID accountId, UUID orderId) {
        return view(requireOwn(mapper.findOrder(requireAccount(accountId), require(orderId, "order id"))), false);
    }

    @Transactional(readOnly = true)
    public ServiceOrderPageView listMine(UUID accountId, String statusCode, int page, int size) {
        requireAccount(accountId);
        requirePage(page, size);
        String status = status(statusCode);
        long total = mapper.countOrders(accountId, status);
        List<OrderRow> rows = mapper.findOrderPage(accountId, status, size, Math.multiplyExact(page - 1, size));
        return page(page, size, total, rows);
    }

    @Transactional(readOnly = true)
    public ServiceOrderView readForProvider(UUID providerId, UUID orderId) {
        return view(requireOwn(mapper.findProviderOrder(requireProvider(providerId), require(orderId, "order id"))),
                true);
    }

    @Transactional(readOnly = true)
    public ServiceOrderPageView listForProvider(UUID providerId, String statusCode, int page, int size) {
        requireProvider(providerId);
        requirePage(page, size);
        String status = status(statusCode);
        long total = mapper.countProviderOrders(providerId, status);
        List<OrderRow> rows = mapper.findProviderOrderPage(providerId, status, size,
                Math.multiplyExact(page - 1, size));
        return page(page, size, total, rows);
    }

    // ---- 服务商：方案 ----

    /** 出方案或改方案（确认之前）。内容和最新一版一样时没有变化。 */
    @Transactional
    public ServiceOrderView issuePlan(UUID providerId, UUID operatorId, UUID orderId, int version, PlanDraft draft) {
        requireProvider(providerId);
        requireAccount(operatorId);
        PlanContent content = normalizePlan(draft);
        OrderRow order = requireOwn(mapper.lockProviderOrder(providerId, require(orderId, "order id")));
        ServiceOrderStatus status = ServiceOrderStatus.valueOf(order.status());
        if (status != ServiceOrderStatus.AWAITING_PLAN && status != ServiceOrderStatus.PLAN_PENDING) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        requireVersion(order, version);
        Integer current = order.planVersion();
        if (current != null) {
            PlanRow latest = mapper.findPlan(orderId, current);
            if (latest != null && content.sameAs(latest)) {
                return readForProvider(providerId, orderId);
            }
        }
        int next = current == null ? 1 : current + 1;
        OffsetDateTime now = now();
        mapper.insertPlan(orderId, next, order.amountMinor(), content.schedule(), content.termsText(), content.note(),
                operatorId, now);
        if (mapper.setPlan(orderId, next, now) != 1) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        mapper.insertEvent(orderId, (current == null ? ServiceOrderEventType.PLAN_ISSUED
                : ServiceOrderEventType.PLAN_REVISED).name(), operatorId, next, null, now);
        return readForProvider(providerId, orderId);
    }

    // ---- 客户：确认方案、付款 ----

    @Transactional
    public ServiceOrderView confirmPlan(UUID accountId, UUID orderId, int version, Integer planVersion,
                                        Boolean agreed) {
        requireAccount(accountId);
        if (planVersion == null || agreed == null) {
            throw new IllegalArgumentException("plan version and agreement are required");
        }
        OrderRow order = requireOwn(mapper.lockOrder(accountId, require(orderId, "order id")));
        ServiceOrderStatus status = ServiceOrderStatus.valueOf(order.status());
        if (status != ServiceOrderStatus.PLAN_PENDING) {
            PlanRow confirmed = order.planVersion() == null ? null : mapper.findPlan(orderId, order.planVersion());
            if (confirmed != null && confirmed.confirmedAt() != null && planVersion.equals(order.planVersion())) {
                return readMine(accountId, orderId);
            }
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        requireVersion(order, version);
        if (!planVersion.equals(order.planVersion())) {
            throw new ServiceOrderException(Error.PLAN_CHANGED);
        }
        if (!agreed) {
            throw new ServiceOrderException(Error.TERMS_NOT_AGREED);
        }
        OffsetDateTime now = now();
        String name = displayName(accountId);
        if (mapper.confirmPlan(orderId, planVersion, accountId, name, now) != 1
                || mapper.moveOrder(orderId, status.name(), ServiceOrderStatus.AWAITING_PAYMENT.name(), now) != 1) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        mapper.insertEvent(orderId, ServiceOrderEventType.PLAN_CONFIRMED.name(), accountId, planVersion, null, now);
        return readMine(accountId, orderId);
    }

    /** 发起付款：还有没关闭的交易订单就沿用，否则新建一张（15 分钟）。 */
    @Transactional
    public ServiceOrderPaymentStartView startPayment(UUID accountId, UUID orderId) {
        requireAccount(accountId);
        OrderRow order = requireOwn(mapper.lockOrder(accountId, require(orderId, "order id")));
        if (ServiceOrderStatus.valueOf(order.status()) != ServiceOrderStatus.AWAITING_PAYMENT) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        PaymentRow pending = pendingPayment(mapper.findPayments(orderId));
        if (pending != null) {
            return new ServiceOrderPaymentStartView(orderId, pending.tradeOrderId(), pending.amountMinor(),
                    pending.payBefore());
        }
        if (!payments.merchantCanCollect(order.providerId())) {
            throw new ServiceOrderException(Error.PAYMENT_UNAVAILABLE);
        }
        OffsetDateTime now = now();
        UUID attemptId = UUID.randomUUID();
        ServiceOrderPorts.OpenedPayment opened = payments.open(new ServiceOrderPorts.OpenPayment(
                attemptId,
                orderId,
                accountId,
                order.providerId(),
                order.cityCode(),
                truncate(order.title() + " · " + order.providerName(), 160),
                "方案 v" + order.planVersion(),
                order.amountMinor(),
                now.toInstant().plus(PAY_WINDOW)));
        OffsetDateTime payBefore = opened.payBefore().atOffset(ZoneOffset.UTC);
        mapper.insertPayment(attemptId, orderId, opened.tradeOrderId(), order.amountMinor(), payBefore, now);
        return new ServiceOrderPaymentStartView(orderId, opened.tradeOrderId(), order.amountMinor(), payBefore);
    }

    // ---- 交易回调（交易模块在改交易订单状态的同一个事务里调用，交易订单已锁） ----

    /**
     * 付款到账：服务订单还在待付款、还没有别的已付尝试时收下 → 待开工；已经收过这一笔的直接收下。
     * 已经取消、已经付过（别的尝试）时不收，返回 false 让交易模块整笔原路退款。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean onPaymentConfirmed(UUID tradeOrderId, UUID attemptId) {
        PaymentRow attempt = mapper.findPayment(attemptId, tradeOrderId);
        if (attempt == null) {
            log.error("trade order {} was paid for an unknown service order payment {}", tradeOrderId, attemptId);
            return false;
        }
        OrderRow order = mapper.lockOrderById(attempt.orderId());
        if (order == null) {
            log.error("service order payment {} has no order", attemptId);
            return false;
        }
        if (PaymentStatus.PAID.name().equals(attempt.status())) {
            return true;
        }
        OffsetDateTime now = now();
        boolean otherPaid = mapper.findPayments(order.id()).stream()
                .anyMatch(row -> PaymentStatus.PAID.name().equals(row.status()));
        if (ServiceOrderStatus.valueOf(order.status()) == ServiceOrderStatus.AWAITING_PAYMENT && !otherPaid
                && !attempt.refused() && mapper.markPaymentPaid(attemptId, now) == 1) {
            mapper.moveOrder(order.id(), ServiceOrderStatus.AWAITING_PAYMENT.name(),
                    ServiceOrderStatus.AWAITING_START.name(), now);
            mapper.insertEvent(order.id(), ServiceOrderEventType.PAID.name(), null, order.planVersion(), null, now);
            return true;
        }
        log.warn("service order {} refused payment {} in status {}; the payment will be refunded", order.id(),
                attemptId, order.status());
        mapper.closePayment(attemptId, true, now);
        mapper.insertEvent(order.id(), "LATE_PAYMENT_REFUSED", null, null, null, now);
        return false;
    }

    /** 交易订单没付款就关闭了（超时或取消）：这次尝试关闭，服务订单仍待付款。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaymentClosed(UUID tradeOrderId, UUID attemptId) {
        PaymentRow attempt = mapper.findPayment(attemptId, tradeOrderId);
        if (attempt == null) {
            log.error("trade order {} closed for an unknown service order payment {}", tradeOrderId, attemptId);
            return;
        }
        mapper.closePayment(attemptId, false, now());
    }

    // ---- 取消（开工前）和协商终止（开工后） ----

    /** 客户取消：不在事务里调用（正在付款的交易订单要先向渠道关单）。 */
    public ServiceOrderView cancelByCustomer(UUID accountId, UUID orderId, int version, String reasonText) {
        requireAccount(accountId);
        require(orderId, "order id");
        String reason = optional(reasonText, MAX_CANCEL_REASON);
        cancel(ServiceOrderParty.CUSTOMER, () -> mapper.findOrder(accountId, orderId),
                () -> mapper.lockOrder(accountId, orderId), accountId, version,
                reason == null ? CUSTOMER_CANCEL_REASON : reason);
        return transactions.inTransaction(() -> readMine(accountId, orderId));
    }

    /** 服务商取消（开工前，写原因）：不在事务里调用。 */
    public ServiceOrderView cancelByProvider(UUID providerId, UUID operatorId, UUID orderId, int version,
                                             String reasonText) {
        requireProvider(providerId);
        requireAccount(operatorId);
        require(orderId, "order id");
        String reason = required(reasonText, MAX_CANCEL_REASON, "reason");
        cancel(ServiceOrderParty.PROVIDER, () -> mapper.findProviderOrder(providerId, orderId),
                () -> mapper.lockProviderOrder(providerId, orderId), operatorId, version, reason);
        return transactions.inTransaction(() -> readForProvider(providerId, orderId));
    }

    private void cancel(ServiceOrderParty party, Supplier<OrderRow> find, Supplier<OrderRow> lock, UUID actorId,
                        int version, String reason) {
        boolean versionChecked = false;
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean firstPass = !versionChecked;
            PaymentRow open = transactions.inTransaction(() -> {
                OrderRow order = requireOwn(find.get());
                ServiceOrderStatus status = ServiceOrderStatus.valueOf(order.status());
                if (status == ServiceOrderStatus.CANCELLED) {
                    return null;
                }
                if (!status.beforeStart()) {
                    throw new ServiceOrderException(Error.STATE_INVALID);
                }
                if (firstPass) {
                    requireVersion(order, version);
                }
                return status == ServiceOrderStatus.AWAITING_PAYMENT ? pendingPayment(mapper.findPayments(order.id()))
                        : null;
            });
            versionChecked = true;
            if (open != null) {
                // 正在付的那张先关掉；查单发现已经付了的，回调已经把订单记成待开工，下面按已付款整笔退款。
                OrderRow order = transactions.inTransaction(find::get);
                payments.cancelUnpaid(order.accountId(), open.tradeOrderId());
                continue;
            }
            boolean done = transactions.inTransaction(() -> {
                OrderRow seen = requireOwn(find.get());
                PaymentRow paid = paidPayment(mapper.findPayments(seen.id()));
                if (paid != null) {
                    payments.lockOrder(paid.tradeOrderId());
                }
                OrderRow order = requireOwn(lock.get());
                ServiceOrderStatus status = ServiceOrderStatus.valueOf(order.status());
                if (status == ServiceOrderStatus.CANCELLED) {
                    return true;
                }
                if (!status.beforeStart()) {
                    throw new ServiceOrderException(Error.STATE_INVALID);
                }
                if (status == ServiceOrderStatus.AWAITING_PAYMENT
                        && pendingPayment(mapper.findPayments(order.id())) != null) {
                    // 读和锁之间又发起了一次付款：回到上面先关掉它。
                    return false;
                }
                OffsetDateTime now = now();
                if (status == ServiceOrderStatus.AWAITING_START) {
                    PaymentRow paidNow = paidPayment(mapper.findPayments(order.id()));
                    if (paidNow == null) {
                        throw new ServiceOrderException(Error.STATE_INVALID);
                    }
                    refund(order, paidNow, ServiceOrderRefundKind.CANCEL, order.amountMinor(), CANCEL_REFUND_REASON,
                            "service-order-cancel:" + order.id(), actorId, now);
                }
                if (mapper.closeOrder(order.id(), status.name(), ServiceOrderStatus.CANCELLED.name(), party.name(),
                        reason, now) != 1) {
                    throw new ServiceOrderException(Error.STATE_INVALID);
                }
                mapper.insertEvent(order.id(), ServiceOrderEventType.CANCELLED.name(), actorId, null, null, now);
                return true;
            });
            if (done) {
                return;
            }
        }
        throw new ServiceOrderException(Error.VERSION_CONFLICT);
    }

    /** 协商终止（开工后）：退款金额 0 到实付，大于 0 时原路退款。已经终止的原样返回。 */
    @Transactional
    public ServiceOrderView terminate(UUID providerId, UUID operatorId, UUID orderId, int version,
                                      Long refundAmountMinor, String reasonText) {
        requireProvider(providerId);
        requireAccount(operatorId);
        String reason = required(reasonText, MAX_TERMINATE_REASON, "reason");
        if (refundAmountMinor == null || refundAmountMinor < 0) {
            throw new IllegalArgumentException("refund amount is invalid");
        }
        OrderRow seen = requireOwn(mapper.findProviderOrder(providerId, require(orderId, "order id")));
        PaymentRow paid = paidPayment(mapper.findPayments(orderId));
        if (paid != null) {
            payments.lockOrder(paid.tradeOrderId());
        }
        OrderRow order = requireOwn(mapper.lockProviderOrder(providerId, seen.id()));
        ServiceOrderStatus status = ServiceOrderStatus.valueOf(order.status());
        if (status == ServiceOrderStatus.TERMINATED) {
            return readForProvider(providerId, orderId);
        }
        if (!status.inService() || paid == null) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        requireVersion(order, version);
        long refunded = mapper.findRefunds(orderId).stream().mapToLong(RefundRow::amountMinor).sum();
        if (refundAmountMinor > paid.amountMinor() - refunded) {
            throw new ServiceOrderException(Error.REFUND_NOT_ALLOWED);
        }
        OffsetDateTime now = now();
        if (refundAmountMinor > 0) {
            refund(order, paid, ServiceOrderRefundKind.TERMINATE, refundAmountMinor, TERMINATE_REFUND_REASON,
                    "service-order-terminate:" + orderId, operatorId, now);
        }
        if (mapper.closeOrder(orderId, status.name(), ServiceOrderStatus.TERMINATED.name(),
                ServiceOrderParty.PROVIDER.name(), reason, now) != 1) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        mapper.insertEvent(orderId, ServiceOrderEventType.TERMINATED.name(), operatorId, null, null, now);
        return readForProvider(providerId, orderId);
    }

    private void refund(OrderRow order, PaymentRow paid, ServiceOrderRefundKind kind, long amountMinor, String reason,
                        String key, UUID actorId, OffsetDateTime now) {
        UUID refundId = payments.requestRefund(paid.tradeOrderId(), amountMinor, reason,
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)), actorId);
        mapper.insertRefund(order.id(), refundId, kind.name(), amountMinor, reason, actorId, now);
    }

    // ---- 服务商：开工、文件、交付 ----

    @Transactional
    public ServiceOrderView start(UUID providerId, UUID operatorId, UUID orderId, int version) {
        requireProvider(providerId);
        requireAccount(operatorId);
        OrderRow order = requireOwn(mapper.lockProviderOrder(providerId, require(orderId, "order id")));
        ServiceOrderStatus status = ServiceOrderStatus.valueOf(order.status());
        if (order.startedAt() != null) {
            return readForProvider(providerId, orderId);
        }
        if (status != ServiceOrderStatus.AWAITING_START) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        requireVersion(order, version);
        OffsetDateTime now = now();
        if (mapper.moveOrder(orderId, status.name(), ServiceOrderStatus.IN_SERVICE.name(), now) != 1) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        mapper.insertEvent(orderId, ServiceOrderEventType.STARTED.name(), operatorId, null, null, now);
        return readForProvider(providerId, orderId);
    }

    /** 上传一个交付文件（还没交付）。不在事务里调用：先存文件，再在事务里记一行，记不上就删掉文件。 */
    public ServiceOrderFileView uploadFile(UUID providerId, UUID operatorId, UUID orderId, String originalName,
                                           byte[] content) {
        requireProvider(providerId);
        requireAccount(operatorId);
        require(orderId, "order id");
        String name = fileName(originalName);
        String contentType = sniff(content, name);
        transactions.inTransaction(() -> {
            requireUploadable(requireOwn(mapper.findProviderOrder(providerId, orderId)));
            return null;
        });
        UUID fileId = UUID.randomUUID();
        String key = "orders/" + orderId + "/files/" + fileId;
        try {
            files.put(key, content);
        } catch (ServiceOrderPorts.FileStoreUnavailableException unavailable) {
            throw new ServiceOrderException(Error.FILE_STORE_UNAVAILABLE, unavailable);
        }
        try {
            return transactions.inTransaction(() -> {
                OrderRow order = requireOwn(mapper.lockProviderOrder(providerId, orderId));
                requireUploadable(order);
                if (mapper.countStagedFiles(orderId) >= MAX_STAGED_FILES) {
                    throw new ServiceOrderException(Error.FILE_INVALID);
                }
                OffsetDateTime now = now();
                mapper.insertFile(fileId, orderId, name, contentType, content.length, sha256(content), key,
                        operatorId, now);
                return new ServiceOrderFileView(fileId, name, contentType, content.length, now);
            });
        } catch (RuntimeException failure) {
            deleteQuietly(key);
            throw failure;
        }
    }

    /** 删掉一个还没交付的文件。 */
    public void removeFile(UUID providerId, UUID orderId, UUID fileId) {
        requireProvider(providerId);
        String key = transactions.inTransaction(() -> {
            OrderRow order = requireOwn(mapper.lockProviderOrder(providerId, require(orderId, "order id")));
            FileRow file = mapper.findFile(order.id(), require(fileId, "file id"));
            if (file == null) {
                throw new ServiceOrderException(Error.NOT_FOUND);
            }
            if (file.deliveryId() != null || mapper.deleteStagedFile(orderId, fileId) != 1) {
                throw new ServiceOrderException(Error.STATE_INVALID);
            }
            return file.storageKey();
        });
        deleteQuietly(key);
    }

    /** 服务商下载本机构订单的一个文件（已交付的、还没交付的都可以）。 */
    @Transactional(readOnly = true)
    public ServiceOrderFileContent readProviderFile(UUID providerId, UUID orderId, UUID fileId) {
        OrderRow order = requireOwn(mapper.findProviderOrder(requireProvider(providerId), require(orderId, "order id")));
        FileRow file = mapper.findFile(order.id(), require(fileId, "file id"));
        if (file == null) {
            throw new ServiceOrderException(Error.NOT_FOUND);
        }
        return content(file);
    }

    /** 标记完成并提交交付：交付说明必填，fileIds 是这次交付的已上传文件（0–10 个）。 */
    @Transactional
    public ServiceOrderView deliver(UUID providerId, UUID operatorId, UUID orderId, int version, String noteText,
                                    List<UUID> fileIds) {
        requireProvider(providerId);
        requireAccount(operatorId);
        String note = required(noteText, MAX_DELIVERY_NOTE, "note");
        List<UUID> chosen = fileIds == null ? List.of() : fileIds;
        if (chosen.size() > MAX_DELIVERY_FILES || chosen.stream().anyMatch(Objects::isNull)
                || new HashSet<>(chosen).size() != chosen.size()) {
            throw new ServiceOrderException(Error.FILE_INVALID);
        }
        OrderRow order = requireOwn(mapper.lockProviderOrder(providerId, require(orderId, "order id")));
        ServiceOrderStatus status = ServiceOrderStatus.valueOf(order.status());
        if (status != ServiceOrderStatus.IN_SERVICE && status != ServiceOrderStatus.REWORK) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        requireVersion(order, version);
        Set<UUID> staged = new HashSet<>();
        for (FileRow file : mapper.findFiles(orderId)) {
            if (file.deliveryId() == null) {
                staged.add(file.id());
            }
        }
        if (!staged.containsAll(chosen)) {
            throw new ServiceOrderException(Error.FILE_INVALID);
        }
        List<DeliveryRow> deliveries = mapper.findDeliveries(orderId);
        int round = deliveries.isEmpty() ? 1 : deliveries.getLast().round() + 1;
        UUID deliveryId = UUID.randomUUID();
        OffsetDateTime now = now();
        mapper.insertDelivery(deliveryId, orderId, round, note, operatorId, now);
        if (!chosen.isEmpty() && mapper.attachFiles(orderId, deliveryId, chosen) != chosen.size()) {
            throw new ServiceOrderException(Error.FILE_INVALID);
        }
        if (mapper.moveOrder(orderId, status.name(), ServiceOrderStatus.DELIVERED.name(), now) != 1) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        mapper.insertEvent(orderId, ServiceOrderEventType.DELIVERED.name(), operatorId, null, round, now);
        return readForProvider(providerId, orderId);
    }

    // ---- 客户：验收、退回整改、下载 ----

    @Transactional
    public ServiceOrderView accept(UUID accountId, UUID orderId, int version, Integer round) {
        requireAccount(accountId);
        if (round == null) {
            throw new IllegalArgumentException("round is required");
        }
        OrderRow order = requireOwn(mapper.lockOrder(accountId, require(orderId, "order id")));
        List<DeliveryRow> deliveries = mapper.findDeliveries(orderId);
        DeliveryRow latest = deliveries.isEmpty() ? null : deliveries.getLast();
        ServiceOrderStatus status = ServiceOrderStatus.valueOf(order.status());
        if (status == ServiceOrderStatus.COMPLETED && latest != null && latest.round() == round
                && ServiceOrderDeliveryStatus.ACCEPTED.name().equals(latest.status())) {
            return readMine(accountId, orderId);
        }
        DeliveryRow submitted = reviewable(order, status, deliveries, version, round);
        OffsetDateTime now = now();
        if (mapper.reviewDelivery(submitted.id(), ServiceOrderDeliveryStatus.ACCEPTED.name(), accountId,
                displayName(accountId), null, now) != 1
                || mapper.moveOrder(orderId, status.name(), ServiceOrderStatus.COMPLETED.name(), now) != 1) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        mapper.insertEvent(orderId, ServiceOrderEventType.ACCEPTED.name(), accountId, null, round, now);
        return readMine(accountId, orderId);
    }

    @Transactional
    public ServiceOrderView rework(UUID accountId, UUID orderId, int version, Integer round, String reasonText) {
        requireAccount(accountId);
        if (round == null) {
            throw new IllegalArgumentException("round is required");
        }
        String reason = required(reasonText, MAX_REVIEW_REASON, "reason");
        OrderRow order = requireOwn(mapper.lockOrder(accountId, require(orderId, "order id")));
        ServiceOrderStatus status = ServiceOrderStatus.valueOf(order.status());
        DeliveryRow submitted = reviewable(order, status, mapper.findDeliveries(orderId), version, round);
        OffsetDateTime now = now();
        if (mapper.reviewDelivery(submitted.id(), ServiceOrderDeliveryStatus.RETURNED.name(), accountId,
                displayName(accountId), reason, now) != 1
                || mapper.moveOrder(orderId, status.name(), ServiceOrderStatus.REWORK.name(), now) != 1) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        mapper.insertEvent(orderId, ServiceOrderEventType.RETURNED.name(), accountId, null, round, now);
        return readMine(accountId, orderId);
    }

    private DeliveryRow reviewable(OrderRow order, ServiceOrderStatus status, List<DeliveryRow> deliveries,
                                   int version, int round) {
        if (status != ServiceOrderStatus.DELIVERED) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
        requireVersion(order, version);
        DeliveryRow latest = deliveries.isEmpty() ? null : deliveries.getLast();
        if (latest == null || latest.round() != round
                || !ServiceOrderDeliveryStatus.SUBMITTED.name().equals(latest.status())) {
            throw new ServiceOrderException(Error.DELIVERY_CHANGED);
        }
        return latest;
    }

    /** 客户下载已交付文件的短时链接（10 分钟）。 */
    @Transactional
    public DownloadIntentView createDownload(UUID accountId, UUID orderId, UUID fileId) {
        requireAccount(accountId);
        OrderRow order = requireOwn(mapper.findOrder(accountId, require(orderId, "order id")));
        FileRow file = mapper.findFile(order.id(), require(fileId, "file id"));
        if (file == null || file.deliveryId() == null) {
            throw new ServiceOrderException(Error.NOT_FOUND);
        }
        OffsetDateTime now = now();
        mapper.deleteExpiredDownloads(now.minusDays(1));
        UUID token = UUID.randomUUID();
        OffsetDateTime expiresAt = now.plus(DOWNLOAD_TTL);
        mapper.insertDownload(token, file.id(), accountId, now, expiresAt);
        return new DownloadIntentView(DOWNLOAD_PATH + token, expiresAt);
    }

    @Transactional(readOnly = true)
    public ServiceOrderFileContent download(UUID token) {
        FileRow file = mapper.findDownload(require(token, "token"), now());
        if (file == null) {
            throw new ServiceOrderException(Error.NOT_FOUND);
        }
        return content(file);
    }

    // ---- 转换 ----

    private ServiceOrderPageView page(int page, int size, long total, List<OrderRow> rows) {
        Set<UUID> reviewed = people.qualificationReviewed(rows.stream().map(OrderRow::providerId).distinct().toList());
        List<ServiceOrderSummaryView> items = rows.stream()
                .map(row -> new ServiceOrderSummaryView(
                        row.id(), row.orderNo(), ServiceOrderStatus.valueOf(row.status()),
                        ServiceOrderType.valueOf(row.type()), row.title(), row.scopeText(), row.cityName(),
                        customer(row), new ServiceOrderProviderView(row.providerId(), row.providerName(),
                                reviewed.contains(row.providerId())),
                        row.amountMinor(), row.planVersion(), row.paidAt(), row.latestDeliveryAt(), row.createdAt(),
                        row.updatedAt()))
                .toList();
        return new ServiceOrderPageView(page, size, total, (long) page * size < total, items);
    }

    private ServiceOrderView view(OrderRow order, boolean forProvider) {
        UUID orderId = order.id();
        ServiceOrderStatus status = ServiceOrderStatus.valueOf(order.status());
        List<ServiceOrderLineView> lines = mapper.findLines(orderId).stream()
                .map(ServiceOrderService::line)
                .toList();
        PlanRow planRow = order.planVersion() == null ? null : mapper.findPlan(orderId, order.planVersion());
        ServiceOrderPlanView plan = planRow == null ? null : new ServiceOrderPlanView(
                planRow.planVersion(), planRow.amountMinor(), planRow.scheduleText(), terms(planRow.termsText()),
                planRow.note(), planRow.issuedAt(), planRow.confirmedAt(), planRow.confirmedByName());
        List<PaymentRow> attempts = mapper.findPayments(orderId);
        PaymentRow pending = pendingPayment(attempts);
        PaymentRow paid = paidPayment(attempts);
        List<RefundRow> refundRows = mapper.findRefunds(orderId);
        Map<UUID, String> refundStates = refundRows.isEmpty() ? Map.of()
                : payments.refundStates(refundRows.stream().map(RefundRow::tradeRefundId).toList());
        List<ServiceOrderRefundView> refunds = refundRows.stream()
                .map(row -> new ServiceOrderRefundView(row.tradeRefundId(), ServiceOrderRefundKind.valueOf(row.kind()),
                        row.amountMinor(), row.reason(), refundStates.getOrDefault(row.tradeRefundId(), "REQUESTED"),
                        row.requestedAt()))
                .toList();
        boolean collectable = payments.merchantCanCollect(order.providerId());
        ServiceOrderPaymentView payment = new ServiceOrderPaymentView(
                order.amountMinor(),
                collectable,
                order.paidAt(),
                pending == null ? null : pending.tradeOrderId(),
                pending == null ? null : pending.payBefore(),
                paid == null ? null : paid.tradeOrderId(),
                refundRows.stream().mapToLong(RefundRow::amountMinor).sum(),
                refunds);
        List<FileRow> fileRows = mapper.findFiles(orderId);
        List<ServiceOrderDeliveryView> deliveries = mapper.findDeliveries(orderId).stream()
                .map(row -> new ServiceOrderDeliveryView(
                        row.round(), ServiceOrderDeliveryStatus.valueOf(row.status()), row.note(),
                        fileRows.stream().filter(file -> row.id().equals(file.deliveryId()))
                                .map(ServiceOrderService::file).toList(),
                        row.deliveredAt(), row.reviewNote(), row.reviewedAt(), row.reviewedByName()))
                .toList();
        List<ServiceOrderFileView> staged = forProvider
                ? fileRows.stream().filter(file -> file.deliveryId() == null).map(ServiceOrderService::file).toList()
                : List.of();
        List<ServiceOrderEventView> events = mapper.findEvents(orderId).stream()
                .filter(row -> isPublicEvent(row.eventType()))
                .map(row -> new ServiceOrderEventView(ServiceOrderEventType.valueOf(row.eventType()), row.occurredAt(),
                        row.planVersion(), row.round()))
                .toList();
        boolean reviewed = people.qualificationReviewed(List.of(order.providerId())).contains(order.providerId());
        return new ServiceOrderView(
                orderId,
                order.orderNo(),
                status,
                ServiceOrderType.valueOf(order.type()),
                order.title(),
                order.demandId(),
                customer(order),
                new ServiceOrderProviderView(order.providerId(), order.providerName(), reviewed),
                order.cityCode(),
                order.cityName(),
                order.address(),
                order.scopeText(),
                order.dueDate(),
                order.amountMinor(),
                lines,
                new ServiceOrderQuoteView(order.quoteId(), order.quoteVersion(), order.quoteMethod(),
                        order.quoteSchedule(), order.quoteDurationDays(), order.quoteDeliverables(), order.quoteTeam(),
                        order.quoteNightWork(), order.quoteReportHours(), order.quoteArchiveDays(),
                        order.quoteFirstVisitDate()),
                plan,
                payment,
                deliveries,
                staged,
                events,
                order.startedAt(),
                order.completedAt(),
                order.closedAt(),
                order.closedBy() == null ? null : ServiceOrderParty.valueOf(order.closedBy()),
                order.closeReason(),
                actions(status, forProvider, collectable),
                order.createdAt(),
                order.updatedAt(),
                order.version());
    }

    static ServiceOrderActionsView actions(ServiceOrderStatus status, boolean forProvider, boolean collectable) {
        if (forProvider) {
            return new ServiceOrderActionsView(false, false, status.beforeStart(), false, false,
                    status == ServiceOrderStatus.AWAITING_PLAN || status == ServiceOrderStatus.PLAN_PENDING,
                    status == ServiceOrderStatus.AWAITING_START,
                    status == ServiceOrderStatus.IN_SERVICE || status == ServiceOrderStatus.REWORK,
                    status.inService());
        }
        boolean delivered = status == ServiceOrderStatus.DELIVERED;
        return new ServiceOrderActionsView(status == ServiceOrderStatus.PLAN_PENDING,
                status == ServiceOrderStatus.AWAITING_PAYMENT && collectable, status.beforeStart(), delivered,
                delivered, false, false, false, false);
    }

    private static boolean isPublicEvent(String type) {
        return Arrays.stream(ServiceOrderEventType.values()).anyMatch(value -> value.name().equals(type));
    }

    private static ServiceOrderCustomerView customer(OrderRow row) {
        return new ServiceOrderCustomerView(ServiceOrderCustomerKind.valueOf(row.customerKind()),
                row.customerOrganizationName(), row.contactName());
    }

    private static ServiceOrderLineView line(LineRow row) {
        return new ServiceOrderLineView(row.name(), row.quantity(), row.unit(), row.unitAmountMinor(),
                row.amountMinor());
    }

    private static ServiceOrderFileView file(FileRow row) {
        return new ServiceOrderFileView(row.id(), row.name(), row.contentType(), row.sizeBytes(), row.uploadedAt());
    }

    private ServiceOrderFileContent content(FileRow file) {
        try {
            return new ServiceOrderFileContent(file.name(), file.contentType(), files.read(file.storageKey()));
        } catch (ServiceOrderPorts.FileStoreUnavailableException unavailable) {
            throw new ServiceOrderException(Error.FILE_STORE_UNAVAILABLE, unavailable);
        }
    }

    private static PaymentRow pendingPayment(List<PaymentRow> rows) {
        return rows.stream().filter(row -> PaymentStatus.PENDING.name().equals(row.status())).findFirst().orElse(null);
    }

    private static PaymentRow paidPayment(List<PaymentRow> rows) {
        return rows.stream().filter(row -> PaymentStatus.PAID.name().equals(row.status())).findFirst().orElse(null);
    }

    private static List<String> terms(String text) {
        return text == null || text.isEmpty() ? List.of() : List.of(text.split(TERM_SEPARATOR));
    }

    // ---- 校验 ----

    record PlanContent(String schedule, List<String> terms, String note) {

        String termsText() {
            return String.join(TERM_SEPARATOR, terms);
        }

        boolean sameAs(PlanRow row) {
            return schedule.equals(row.scheduleText()) && termsText().equals(row.termsText())
                    && Objects.equals(note, row.note());
        }
    }

    static PlanContent normalizePlan(PlanDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("plan is required");
        }
        String schedule = required(draft.schedule(), MAX_SCHEDULE, "schedule");
        if (draft.terms() == null || draft.terms().isEmpty() || draft.terms().size() > MAX_TERMS) {
            throw new IllegalArgumentException("terms are invalid");
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String term : draft.terms()) {
            String value = required(term, MAX_TERM, "term");
            if (value.contains("\n") || value.contains("\r")) {
                throw new IllegalArgumentException("term is invalid");
            }
            terms.add(value);
        }
        return new PlanContent(schedule, List.copyOf(terms), optional(draft.note(), MAX_PLAN_NOTE));
    }

    private static void requireUploadable(OrderRow order) {
        ServiceOrderStatus status = ServiceOrderStatus.valueOf(order.status());
        if (status != ServiceOrderStatus.IN_SERVICE && status != ServiceOrderStatus.REWORK) {
            throw new ServiceOrderException(Error.STATE_INVALID);
        }
    }

    static String fileName(String originalName) {
        String name = originalName == null ? "" : originalName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        name = name.substring(slash + 1).replaceAll("[\\p{Cntrl}]", "");
        if (name.isEmpty()) {
            name = "交付文件";
        }
        return name.length() > MAX_FILE_NAME ? name.substring(name.length() - MAX_FILE_NAME) : name;
    }

    /** 按文件头判断类型；ZIP 容器按扩展名区分 Office 文档。认不出、空文件、超过 20 MB 都拒绝。 */
    static String sniff(byte[] content, String name) {
        if (content == null || content.length == 0 || content.length > MAX_FILE_BYTES) {
            throw new ServiceOrderException(Error.FILE_INVALID);
        }
        if (startsWith(content, 0x25, 0x50, 0x44, 0x46, 0x2D)) {
            return "application/pdf";
        }
        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(content, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(content, 0x50, 0x4B, 0x03, 0x04)) {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            if (lower.endsWith(".docx")) {
                return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            }
            if (lower.endsWith(".xlsx")) {
                return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            }
            if (lower.endsWith(".pptx")) {
                return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            }
            return "application/zip";
        }
        if (content.length >= 12 && content[4] == 'f' && content[5] == 't' && content[6] == 'y' && content[7] == 'p') {
            return "video/mp4";
        }
        throw new ServiceOrderException(Error.FILE_INVALID);
    }

    private static boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((content[index] & 0xFF) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void deleteQuietly(String key) {
        try {
            files.delete(key);
        } catch (RuntimeException failure) {
            log.warn("service order file {} was not removed from the store", key);
        }
    }

    private String displayName(UUID accountId) {
        return truncate(people.displayName(accountId).filter(name -> !name.isBlank()).orElse("客户"), 80);
    }

    private String newOrderNo(OffsetDateTime now) {
        String day = now.atZoneSameInstant(BEIJING).format(ORDER_NO_DATE);
        for (int attempt = 0; attempt < 5; attempt++) {
            String candidate = "SV" + day + String.format("%06d", random.nextInt(1_000_000));
            if (mapper.orderNoTaken(candidate) == null) {
                return candidate;
            }
        }
        throw new IllegalStateException("order number is not available");
    }

    private static String status(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return ServiceOrderStatus.valueOf(code.trim()).name();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("status is invalid", invalid);
        }
    }

    private static void requireVersion(OrderRow order, int version) {
        if (order.version() != version) {
            throw new ServiceOrderException(Error.VERSION_CONFLICT);
        }
    }

    private static OrderRow requireOwn(OrderRow row) {
        if (row == null) {
            throw new ServiceOrderException(Error.NOT_FOUND);
        }
        return row;
    }

    private static UUID requireAccount(UUID accountId) {
        if (accountId == null) {
            throw new ServiceOrderException(Error.FORBIDDEN);
        }
        return accountId;
    }

    private static UUID requireProvider(UUID providerId) {
        if (providerId == null) {
            throw new ServiceOrderException(Error.FORBIDDEN);
        }
        return providerId;
    }

    private static void requirePage(int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    static String required(String text, int max, String name) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty() || value.length() > max) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }

    static String optional(String text, int max) {
        String value = text == null ? "" : text.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.length() > max) {
            throw new IllegalArgumentException("text is too long");
        }
        return value;
    }

    private static String truncate(String text, int max) {
        String value = text == null ? "" : text.trim();
        return value.length() > max ? value.substring(0, max) : value;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }
}
