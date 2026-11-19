package com.lowaltitude.inspection.rental.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 租赁模块用到的别的模块的能力，由启动模块接上（§24）：商城的租赁供给、账号的收货地址、统一交易模块的付款和退款。
 * 本模块不读它们的表。付款到账和订单关闭由启动模块转给 {@link RentalService#onPaymentConfirmed} 和
 * {@link RentalService#onPaymentClosed}。加锁顺序：先交易订单（{@link Payments#lockOrder}），再租赁订单。
 */
public final class RentalPorts {

    private RentalPorts() {
    }

    public interface Offers {

        /** 商城供给的租赁信息；不存在时为空。 */
        Optional<Offer> find(UUID offerId);
    }

    public interface Addresses {

        /** 这个账号自己的一条收货地址；不存在或不是它的时为空。 */
        Optional<Address> find(UUID accountId, UUID addressId);
    }

    public interface Payments {

        /** 这家机构登记了有效的收款账户，能在线收款。 */
        boolean merchantCanCollect(UUID organizationId);

        /** 在当前事务里建一张待付款的交易订单（来源 RENTAL）。收不了款时抛 PAYMENT_UNAVAILABLE。 */
        OpenedPayment open(OpenPayment request);

        /** 在当前事务里锁住这张交易订单。 */
        void lockOrder(UUID tradeOrderId);

        /** 在当前事务里建一笔退款，事务提交后交给渠道。退不了时抛 REFUND_NOT_ALLOWED。 */
        UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey, UUID actorId);

        /** 这些退款现在的进度；不认识的不返回。 */
        Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds);
    }

    /**
     * @param cityCode             供给所在城市；为空表示全国
     * @param rentable             现在能不能租（已上架、在售、交易方式是租赁）
     * @param referenceAmountMinor 参考价（分）；没有标价时为空
     * @param priceUnit            DAY、HALF_DAY 或 ITEM
     */
    public record Offer(
            UUID offerId,
            UUID productId,
            String productName,
            String skuName,
            String coverUrl,
            UUID merchantId,
            String merchantName,
            String cityCode,
            Long referenceAmountMinor,
            String priceUnit,
            boolean rentable
    ) {
    }

    public record Address(
            UUID addressId,
            String recipientName,
            String phone,
            String provinceName,
            String cityCode,
            String cityName,
            String detail
    ) {
    }

    /** 交易订单的一行：业务项目（商品）和选项（供给或费用行），单价和数量。 */
    public record PaymentLine(
            UUID itemId,
            UUID optionId,
            String itemName,
            String optionName,
            String coverUrl,
            long unitAmountMinor,
            int quantity
    ) {
    }

    public record OpenPayment(
            UUID rentalOrderId,
            UUID accountId,
            UUID merchantId,
            String cityCode,
            List<PaymentLine> lines,
            Instant payBefore
    ) {
    }

    public record OpenedPayment(UUID tradeOrderId, Instant payBefore) {
    }

    /** status 是交易模块的退款状态：REQUESTED、PROCESSING、SUCCEEDED、CLOSED、ABNORMAL。 */
    public record RefundProgress(String status, long amountMinor) {
    }
}
