package com.lowaltitude.inspection.trade.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.lowaltitude.inspection.trade.application.TradeException.Error;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderView;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOrderMapper.MerchantAccountRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeSourceMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeSourceMapper.SourceOrderRow;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务模块用的交易入口（E09，docs/redesign/03-App实现任务计划.md §20.2）：业务模块在自己的事务里建订单、
 * 为业务单建退款，交易模块不依赖任何业务模块；付款和关闭通过 {@link TradeSourceListener} 回告业务模块。
 *
 * <p>加锁顺序：凡是同时动交易订单和业务单的路径，一律先锁交易订单（{@link #lockOrder}），再锁业务单。
 */
public class TradeSourceOrders {

    /**
     * 各业务来源的订单编号前缀；商城订单是 MO。收费报名 AO（E09），到馆预约 VO（E10），设备租赁 RO（F3），
     * 巡检付款节点 IO（S4-1），通用服务订单 PS（S4-3），线上录播课 CO（S5-5）。
     */
    static final Map<String, String> ORDER_PREFIXES = Map.of(
            "ACTIVITY", "AO", "VENUE", "VO", "RENTAL", "RO", "INSPECTION", "IO", "PRO_SERVICE", "PS", "COURSE", "CO");
    static final int MAX_LINES = 20;
    static final int MAX_ITEM_NAME = 160;
    static final int MAX_OPTION_NAME = 80;
    static final int MAX_QUANTITY = 99;

    private final TradeSourceMapper sources;
    private final TradeOrderMapper orders;
    private final TradeOrderService orderService;
    private final TradePaymentService payments;
    private final TradeRefundService refunds;
    private final AfterCommit afterCommit;
    private final Clock clock;

    TradeSourceOrders(
            TradeSourceMapper sources,
            TradeOrderMapper orders,
            TradeOrderService orderService,
            TradePaymentService payments,
            TradeRefundService refunds,
            AfterCommit afterCommit,
            Clock clock
    ) {
        this.sources = sources;
        this.orders = orders;
        this.orderService = orderService;
        this.payments = payments;
        this.refunds = refunds;
        this.afterCommit = afterCommit;
        this.clock = clock;
    }

    // ---- 建订单 ----

    /** 这家机构登记了有效的微信收款账户，能收款。业务模块据此提前拒绝，免得占了名额再回滚。 */
    @Transactional(readOnly = true)
    public boolean merchantCanCollect(UUID organizationId) {
        MerchantAccountRow account = organizationId == null ? null : orders.findMerchantAccount(organizationId);
        return account != null && account.active();
    }

    /**
     * 在调用方的事务里建一张待付款订单：收款方是 merchantId 这家机构（钱进它自己的子商户号，平台不经手），
     * 金额是各行小计之和，付款截止时间由业务决定。同一张业务单已经有订单时原样返回它（不会建第二张）。
     * 机构没有有效的收款账户时拒绝（MERCHANT_PAYMENT_UNAVAILABLE），调用方的事务随之回滚。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public OpenedOrder open(OpenCommand command) {
        String prefix = validate(command);
        SourceOrderRow existing = sources.findBySourceRef(command.source(), command.sourceRef());
        if (existing != null) {
            if (!existing.buyerId().equals(command.buyerId())) {
                throw new TradeException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new OpenedOrder(existing.id(), existing.orderNo(), existing.payBefore());
        }
        MerchantAccountRow account = orders.findMerchantAccount(command.merchantId());
        if (account == null || !account.active()) {
            throw new TradeException(Error.MERCHANT_PAYMENT_UNAVAILABLE);
        }
        // S5-7、S5-8：付款主体（当前身份）记在订单上；注销冷静期内的账号不能建单（调用方的事务随之回滚）。
        TradePayerPort.Payer payer = orderService.payerFor(command.buyerId());
        String merchantName = sources.findOrganizationName(command.merchantId());
        if (merchantName == null) {
            throw new TradeException(Error.NOT_FOUND);
        }
        Instant now = clock.instant();
        if (!command.payBefore().isAfter(now)) {
            throw new IllegalArgumentException("pay-before time has passed");
        }
        long goods = command.lines().stream()
                .mapToLong(line -> Math.multiplyExact(line.unitAmountMinor(), line.quantity()))
                .reduce(0L, Math::addExact);
        UUID orderId = sources.insertSourceOrder(
                orderService.newOrderNo(prefix, now),
                command.buyerId(),
                command.merchantId(),
                merchantName,
                account.subMchid(),
                command.source(),
                command.sourceRef(),
                command.cityCode(),
                goods,
                command.payBefore(),
                requestHash(command.source(), command.sourceRef()),
                now);
        orderService.recordPayer(orderId, payer);
        // 业务来源的订单行记的是业务项目，不是商品：product_id 是业务项目 ID（如活动 ID），offer_id 是选项 ID（如场次 ID），
        // product_name / sku_name 是它们的名称。
        int sort = 0;
        for (Line line : command.lines()) {
            orders.insertLine(
                    orderId,
                    line.itemId(),
                    line.optionId(),
                    line.itemName().trim(),
                    line.optionName().trim(),
                    line.coverUrl(),
                    line.unitAmountMinor(),
                    line.quantity(),
                    line.unitAmountMinor() * line.quantity(),
                    sort,
                    now);
            sort += 10;
        }
        String names = participantNames(command.participantNames());
        if (names != null) {
            orders.saveParticipantNames(orderId, names);
        }
        orders.insertTransition(orderId, null, TradeOrderStatus.PENDING_PAYMENT.name(), "SUBMIT", command.buyerId(), now);
        SourceOrderRow created = sources.findSourceOrder(orderId);
        return new OpenedOrder(orderId, created.orderNo(), created.payBefore());
    }

    // ---- 锁订单、退款 ----

    /**
     * 锁住一张业务来源的订单（调用方的事务结束时释放），返回它当前的状态；不存在或是商城订单时为 null。
     * 业务模块改报名单之前先调它，保证“先订单、后业务单”的加锁顺序。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TradeOrderStatus lockOrder(UUID orderId) {
        if (orderId == null) {
            throw new IllegalArgumentException("order id is required");
        }
        SourceOrderRow row = sources.lockSourceOrder(orderId);
        return row == null ? null : TradeOrderStatus.valueOf(row.status());
    }

    /**
     * 在调用方的事务里为业务单建一笔退款（发起方 SOURCE），事务提交之后交给渠道；渠道不通时定时任务用同一个退款单号重提。
     * 规则和商家退款一样；退的是剩下的全部时订单进入退款中。同一个幂等键、同样的金额和原因重放返回同一笔退款。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID requestRefund(UUID orderId, long amountMinor, String reason, UUID idempotencyKey, UUID actorId) {
        if (amountMinor < 1) {
            throw new IllegalArgumentException("refund amount must be positive");
        }
        UUID refundId = refunds.createSourceRefund(orderId, amountMinor, reason, idempotencyKey, actorId);
        afterCommit.run(() -> refunds.submit(refundId));
        return refundId;
    }

    /** 这些退款现在的进度（REQUESTED、PROCESSING、SUCCEEDED、CLOSED、ABNORMAL）、金额和到账时间；不认识的 ID 不返回。 */
    @Transactional(readOnly = true)
    public List<RefundState> refundStates(Collection<UUID> refundIds) {
        if (refundIds == null || refundIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = refundIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        return sources.findRefunds(ids).stream()
                .map(row -> new RefundState(row.id(), row.status(), row.amountMinor(), row.succeededAt()))
                .toList();
    }

    // ---- 取消未付款的订单 ----

    /**
     * 买家取消一张还没付款的订单：和买家在交易页面上取消一样（发起过支付的先向渠道查单、关单），
     * 关闭后通过 {@link TradeSourceListener#onClosed} 通知业务模块。不在任何事务里调用（要调渠道）。
     * 返回订单最后的状态：已关闭；或者查单发现已经付了款，是已付款（业务那边已按付款处理）。
     */
    public TradeOrderStatus cancelUnpaid(UUID buyerId, UUID orderId) {
        for (int attempt = 0; ; attempt++) {
            TradeOrderView order = orderService.read(buyerId, orderId);
            if (order.status() != TradeOrderStatus.PENDING_PAYMENT) {
                return order.status();
            }
            try {
                // 幂等键只用于已付款商城订单的退款，这里用不到。
                return payments.cancel(buyerId, orderId, order.version(), UUID.randomUUID()).status();
            } catch (TradeException exception) {
                if (exception.error() == Error.VERSION_CONFLICT && attempt == 0) {
                    // 读和关之间订单变了（比如刚发起了支付）：重读一次再试。
                    continue;
                }
                if (exception.error() == Error.ORDER_STATE_INVALID) {
                    // 查单发现已经付了款：订单已按付款处理，返回它现在的状态。
                    return orderService.read(buyerId, orderId).status();
                }
                throw exception;
            }
        }
    }

    // ---- 工具 ----

    private static String validate(OpenCommand command) {
        if (command == null || command.source() == null || command.sourceRef() == null || command.buyerId() == null
                || command.merchantId() == null || command.payBefore() == null) {
            throw new IllegalArgumentException("source order is invalid");
        }
        String prefix = ORDER_PREFIXES.get(command.source());
        if (prefix == null) {
            throw new IllegalArgumentException("order source is not supported");
        }
        if (command.cityCode() == null || !command.cityCode().matches(TradeOrderService.CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        if (command.lines() == null || command.lines().isEmpty() || command.lines().size() > MAX_LINES) {
            throw new IllegalArgumentException("order lines are invalid");
        }
        for (Line line : command.lines()) {
            if (line == null || line.itemId() == null || line.optionId() == null
                    || line.unitAmountMinor() < 1 || line.quantity() < 1 || line.quantity() > MAX_QUANTITY
                    || !fits(line.itemName(), MAX_ITEM_NAME) || !fits(line.optionName(), MAX_OPTION_NAME)) {
                throw new IllegalArgumentException("order line is invalid");
            }
        }
        return prefix;
    }

    private static boolean fits(String text, int max) {
        String trimmed = text == null ? "" : text.trim();
        return !trimmed.isEmpty() && trimmed.length() <= max;
    }

    private static String requestHash(String source, UUID sourceRef) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((source + ":" + sourceRef).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /**
     * 建订单的内容。
     *
     * @param source     订单来源，如 ACTIVITY
     * @param sourceRef  业务单 ID（如报名单 ID），同一来源里唯一
     * @param merchantId 收款机构（它要登记过有效的微信子商户号）
     * @param payBefore  付款截止时间，由业务决定（收费报名是 15 分钟且不晚于报名截止）
     */
    /** 姓名用顿号连接，最多 400 个字符（超出的截掉，只是显示用）。 */
    static String participantNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return null;
        }
        String joined = names.stream()
                .filter(name -> name != null && !name.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining("、"));
        if (joined.isEmpty()) {
            return null;
        }
        return joined.length() <= 400 ? joined : joined.substring(0, 400);
    }

    public record OpenCommand(
            String source,
            UUID sourceRef,
            UUID buyerId,
            UUID merchantId,
            String cityCode,
            List<Line> lines,
            Instant payBefore,
            /** 0.6.0：订单上显示的参与人姓名（收费报名的报名顺序）；别的来源为空。只用来显示。 */
            List<String> participantNames
    ) {
        public OpenCommand(String source, UUID sourceRef, UUID buyerId, UUID merchantId, String cityCode,
                           List<Line> lines, Instant payBefore) {
            this(source, sourceRef, buyerId, merchantId, cityCode, lines, payBefore, List.of());
        }
    }

    /** 一行：业务项目（如活动）和选项（如场次），单价和数量。 */
    public record Line(
            UUID itemId,
            UUID optionId,
            String itemName,
            String optionName,
            String coverUrl,
            long unitAmountMinor,
            int quantity
    ) {
    }

    public record OpenedOrder(UUID orderId, String orderNo, Instant payBefore) {
    }

    /** succeededAt：到账时间（S5-3 活动退改详情用），没到账时为空。 */
    public record RefundState(UUID refundId, String status, long amountMinor, Instant succeededAt) {

        public RefundState(UUID refundId, String status, long amountMinor) {
            this(refundId, status, amountMinor, null);
        }
    }
}
