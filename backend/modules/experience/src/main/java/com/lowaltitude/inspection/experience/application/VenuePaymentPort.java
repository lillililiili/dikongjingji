package com.lowaltitude.inspection.experience.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.VenueBookingException.Error;

/**
 * 到馆预约用到的付款能力（E10，§21.2）。体验模块只认这个端口，由启动模块接到统一交易模块（订单来源 VENUE，
 * 收款方是场馆运营机构）；付款到账和订单关闭由启动模块转给 {@link VenueBookingService#onPaymentConfirmed}
 * 和 {@link VenueBookingService#onPaymentClosed}。加锁顺序：先交易订单（{@link #lockOrder}），再场次和预约单。
 */
public interface VenuePaymentPort {

    /** 场馆运营机构登记了有效的收款账户，能在线收款。 */
    boolean merchantCanCollect(UUID organizationId);

    /** 在当前事务里建一张待付款的交易订单。运营机构收不了款时抛 {@link Error#PAYMENT_UNAVAILABLE}。 */
    OpenedPayment open(OpenPayment request);

    /** 在当前事务里锁住这张交易订单。 */
    void lockOrder(UUID tradeOrderId);

    /** 在当前事务里建一笔退款，事务提交后交给渠道。退不了时抛 {@link Error#CANCEL_NOT_ALLOWED}。 */
    UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey, UUID actorId);

    /** 这些退款现在的进度；不认识的不返回。 */
    Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds);

    /** 取消一张还没付款的交易订单（发起过支付的先向渠道查单、关单）。不能在事务里调用。 */
    void cancelUnpaid(UUID accountId, UUID tradeOrderId);

    /**
     * @param sessionName 订单上显示的场次，如“11月28日 10:00”
     */
    record OpenPayment(
            UUID bookingId,
            UUID accountId,
            UUID operatorId,
            String cityCode,
            UUID projectId,
            UUID sessionId,
            String projectName,
            String sessionName,
            String coverUrl,
            long unitAmountMinor,
            int people,
            Instant payBefore
    ) {
    }

    record OpenedPayment(UUID tradeOrderId, Instant payBefore) {
    }

    /** status 是交易模块的退款状态：REQUESTED、PROCESSING、SUCCEEDED、CLOSED、ABNORMAL。 */
    record RefundProgress(String status, long amountMinor) {
    }

    /** 没有接交易模块时：要付款的项目一律“暂未开通在线支付”，免费项目照常可约。 */
    static VenuePaymentPort unavailable() {
        return new VenuePaymentPort() {
            @Override
            public boolean merchantCanCollect(UUID organizationId) {
                return false;
            }

            @Override
            public OpenedPayment open(OpenPayment request) {
                throw new VenueBookingException(Error.PAYMENT_UNAVAILABLE);
            }

            @Override
            public void lockOrder(UUID tradeOrderId) {
                throw new VenueBookingException(Error.PAYMENT_UNAVAILABLE);
            }

            @Override
            public UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey,
                                      UUID actorId) {
                throw new VenueBookingException(Error.PAYMENT_UNAVAILABLE);
            }

            @Override
            public Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds) {
                return Map.of();
            }

            @Override
            public void cancelUnpaid(UUID accountId, UUID tradeOrderId) {
                throw new VenueBookingException(Error.PAYMENT_UNAVAILABLE);
            }
        };
    }
}
