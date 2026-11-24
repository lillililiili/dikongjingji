package com.lowaltitude.inspection.trade.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOpsLookupMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOpsLookupMapper.DeliveryRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOpsLookupMapper.OrderExtraRow;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeOpsLookupMapper.OrderSummaryRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“订单查询”（契约 contracts/openapi-ops-lookup.yaml 1.0.0）：客服、运营在客户来问时按手机号、单号查交易订单。只读。
 * 手机号、收货人打码，不给详细地址和自提码。角色由控制器检查，查看记录由控制器记。
 */
public class OpsOrderLookupService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_QUERY = 64;
    public static final int MAX_DAYS = 366;
    static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final Pattern PHONE = Pattern.compile("^1[3-9][0-9]{9}$");

    private final TradeOpsLookupMapper mapper;

    public OpsOrderLookupService(TradeOpsLookupMapper mapper) {
        this.mapper = mapper;
    }

    /** 订单来源（契约 OpsOrderSource）。 */
    public enum OpsOrderSource {
        MALL, RENTAL, ACTIVITY, VENUE, INSPECTION, PRO_SERVICE, COURSE
    }

    @Transactional(readOnly = true)
    public OpsOrderPageView search(String query, UUID buyerId, String source, String status, LocalDate from,
                                   LocalDate to, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        Query parsed = Query.parse(query);
        String sourceCode = source == null || source.isBlank() ? null : OpsOrderSource.valueOf(source.strip()).name();
        String statusCode = status == null || status.isBlank() ? null : TradeOrderStatus.valueOf(status.strip()).name();
        if (from != null && to != null && (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS)) {
            throw new IllegalArgumentException("date range is invalid");
        }
        OffsetDateTime start = from == null ? null : from.atStartOfDay(BEIJING).toOffsetDateTime();
        OffsetDateTime end = to == null ? null : to.plusDays(1).atStartOfDay(BEIJING).toOffsetDateTime();
        long total = mapper.countOrders(parsed.phone(), parsed.orderId(), parsed.number(), buyerId, sourceCode,
                statusCode, start, end);
        List<OpsOrderSummaryView> items = total == 0 ? List.of() : mapper.findOrders(parsed.phone(), parsed.orderId(),
                        parsed.number(), buyerId, sourceCode, statusCode, start, end, size,
                        Math.multiplyExact(page - 1, size)).stream()
                .map(OpsOrderLookupService::summary)
                .toList();
        return new OpsOrderPageView(page, size, total, (long) page * size < total, items);
    }

    @Transactional(readOnly = true)
    public Optional<OpsOrderDetailView> detail(UUID orderId) {
        OrderSummaryRow row = mapper.findOrder(orderId);
        if (row == null) {
            return Optional.empty();
        }
        OpsOrderSummaryView summary = summary(row);
        OrderExtraRow extra = mapper.findOrderExtra(orderId);
        DeliveryRow delivery = mapper.findDelivery(orderId);
        return Optional.of(new OpsOrderDetailView(
                summary.id(), summary.orderNo(), summary.source(), summary.status(), summary.cityCode(),
                summary.merchantName(), summary.buyer(), summary.payerName(), summary.title(), summary.itemCount(),
                summary.payableMinor(), summary.paidMinor(), summary.refundedMinor(), summary.createdAt(),
                summary.paidAt(),
                extra.sourceRef(), extra.deliveryMode(), extra.goodsMinor(), extra.shippingMinor(), extra.buyerNote(),
                extra.payBefore(), extra.shippedAt(), extra.completedAt(), extra.closedAt(),
                mapper.findLines(orderId).stream()
                        .map(line -> new OpsOrderLineView(line.productName(), line.skuName(), line.unitAmountMinor(),
                                line.quantity(), line.subtotalMinor()))
                        .toList(),
                delivery == null ? null : new OpsOrderDeliveryView(delivery.mode(), maskName(delivery.recipientName()),
                        maskPhone(delivery.phone()), delivery.cityName(), delivery.pickupPoint(),
                        delivery.carrierName(), delivery.trackingNo()),
                mapper.findPayments(orderId).stream()
                        .map(payment -> new OpsOrderPaymentView(payment.outTradeNo(), payment.transactionId(),
                                payment.amountMinor(), payment.status(), payment.createdAt(), payment.succeededAt(),
                                payment.closedAt()))
                        .toList(),
                mapper.findRefunds(orderId).stream()
                        .map(refund -> new OpsOrderRefundView(refund.outRefundNo(), refund.refundId(),
                                refund.amountMinor(), refund.reason(), refund.initiator(), refund.status(),
                                refund.createdAt(), refund.acceptedAt(), refund.succeededAt()))
                        .toList(),
                mapper.findAftersales(orderId).stream()
                        .map(aftersale -> new OpsOrderAftersaleView(aftersale.id(), aftersale.aftersaleNo(),
                                aftersale.kind(), aftersale.status(), aftersale.reason(), aftersale.requestedMinor(),
                                aftersale.refundMinor(), aftersale.submittedAt()))
                        .toList(),
                mapper.findTransitions(orderId).stream()
                        .map(transition -> new OpsOrderTransitionView(transition.fromStatus(),
                                transition.toStatus(), transition.cause(), transition.occurredAt()))
                        .toList()));
    }

    /** 作为买家的订单数（用户详情用）。 */
    @Transactional(readOnly = true)
    public long countByBuyer(UUID buyerId) {
        return mapper.countByBuyer(buyerId);
    }

    private static OpsOrderSummaryView summary(OrderSummaryRow row) {
        return new OpsOrderSummaryView(row.id(), row.orderNo(), OpsOrderSource.valueOf(row.source()),
                TradeOrderStatus.valueOf(row.status()), row.cityCode(), row.merchantName(),
                new OpsBuyerView(row.buyerId(), row.buyerName(), maskPhone(row.buyerPhone())), row.payerName(),
                row.title(), Math.toIntExact(row.itemCount()), row.payableMinor(), row.paidMinor(),
                row.refundedMinor(), row.createdAt(), row.paidAt());
    }

    /** 前 3 位和后 4 位，中间 4 个星号；不是 11 位时不给。 */
    static String maskPhone(String phone) {
        if (phone == null || phone.length() != 11) {
            return null;
        }
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }

    /** 只留第一个字（一般是姓）。 */
    static String maskName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String trimmed = name.strip();
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)) + "*";
    }

    /** q 的三种用法：手机号、订单 ID、各种单号。 */
    record Query(String phone, UUID orderId, String number) {

        static Query parse(String query) {
            if (query == null || query.isBlank()) {
                return new Query(null, null, null);
            }
            String text = query.strip();
            if (text.codePointCount(0, text.length()) > MAX_QUERY) {
                throw new IllegalArgumentException("query is too long");
            }
            if (PHONE.matcher(text).matches()) {
                return new Query(text, null, null);
            }
            if (text.length() == 36) {
                try {
                    return new Query(null, UUID.fromString(text), null);
                } catch (IllegalArgumentException notAnId) {
                    // 36 位但不是 UUID 的，当单号查。
                }
            }
            return new Query(null, null, text);
        }
    }

    public record OpsBuyerView(UUID userId, String displayName, String maskedPhone) {
    }

    public record OpsOrderSummaryView(UUID id, String orderNo, OpsOrderSource source, TradeOrderStatus status,
                                      String cityCode, String merchantName, OpsBuyerView buyer, String payerName,
                                      String title, int itemCount, long payableMinor, long paidMinor,
                                      long refundedMinor, OffsetDateTime createdAt, OffsetDateTime paidAt) {
    }

    public record OpsOrderPageView(int page, int size, long total, boolean hasMore,
                                   List<OpsOrderSummaryView> items) {
    }

    public record OpsOrderDetailView(UUID id, String orderNo, OpsOrderSource source, TradeOrderStatus status,
                                     String cityCode, String merchantName, OpsBuyerView buyer, String payerName,
                                     String title, int itemCount, long payableMinor, long paidMinor,
                                     long refundedMinor, OffsetDateTime createdAt, OffsetDateTime paidAt,
                                     UUID sourceRef, String deliveryMode, long goodsMinor, long shippingMinor,
                                     String buyerNote, OffsetDateTime payBefore, OffsetDateTime shippedAt,
                                     OffsetDateTime completedAt, OffsetDateTime closedAt,
                                     List<OpsOrderLineView> lines, OpsOrderDeliveryView delivery,
                                     List<OpsOrderPaymentView> payments, List<OpsOrderRefundView> refunds,
                                     List<OpsOrderAftersaleView> aftersales,
                                     List<OpsOrderTransitionView> history) {
    }

    public record OpsOrderLineView(String productName, String skuName, long unitAmountMinor, int quantity,
                                   long subtotalMinor) {
    }

    public record OpsOrderDeliveryView(String mode, String recipientMasked, String phoneMasked, String cityName,
                                       String pickupPoint, String carrierName, String trackingNo) {
    }

    public record OpsOrderPaymentView(String outTradeNo, String transactionId, long amountMinor, String status,
                                      OffsetDateTime createdAt, OffsetDateTime succeededAt,
                                      OffsetDateTime closedAt) {
    }

    public record OpsOrderRefundView(String outRefundNo, String refundId, long amountMinor, String reason,
                                     String initiator, String status, OffsetDateTime createdAt,
                                     OffsetDateTime acceptedAt, OffsetDateTime succeededAt) {
    }

    public record OpsOrderAftersaleView(UUID id, String aftersaleNo, String kind, String status, String reason,
                                        long requestedMinor, Long refundMinor, OffsetDateTime submittedAt) {
    }

    public record OpsOrderTransitionView(String fromStatus, String toStatus, String cause,
                                         OffsetDateTime occurredAt) {
    }
}
