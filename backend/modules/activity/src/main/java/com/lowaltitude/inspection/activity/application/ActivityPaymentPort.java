package com.lowaltitude.inspection.activity.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;

/**
 * 收费报名用到的付款能力（E09，docs/redesign/03-App实现任务计划.md §20.2）。活动模块只认这个端口，
 * 由启动模块接到统一交易模块；两个模块互不依赖。付款到账和订单关闭由启动模块转给
 * {@link ActivityEnrollmentService#onPaymentConfirmed} 和 {@link ActivityEnrollmentService#onPaymentClosed}。
 *
 * <p>加锁顺序：同时动交易订单和报名单的路径一律先锁交易订单（{@link #lockOrder}），再锁场次和报名单。
 */
public interface ActivityPaymentPort {

    /** 主办机构登记了有效的收款账户，能在线收款。 */
    boolean merchantCanCollect(UUID organizationId);

    /**
     * 在当前事务里建一张待付款的交易订单，收款方是主办机构。同一张报名单只会有一张订单。
     * 主办机构收不了款时抛 {@link Error#PAYMENT_UNAVAILABLE}。
     */
    OpenedPayment open(OpenPayment request);

    /** 在当前事务里锁住这张交易订单。 */
    void lockOrder(UUID tradeOrderId);

    /**
     * 在当前事务里为取消的几位参与人建一笔退款，事务提交后交给渠道。
     * 退不了（超过可退金额、超过次数或期限）时抛 {@link Error#CANCEL_NOT_ALLOWED}。
     *
     * @return 退款单 ID
     */
    UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey, UUID actorId);

    /** 这些退款现在的进度；不认识的不返回。 */
    Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds);

    /**
     * 取消一张还没付款的交易订单（发起过支付的先向渠道查单、关单）。不能在事务里调用。
     * 关闭后由 {@link ActivityEnrollmentService#onPaymentClosed} 释放名额；查单发现已经付了，则已按付款处理。
     */
    void cancelUnpaid(UUID accountId, UUID tradeOrderId);

    /**
     * 建订单的内容。
     *
     * @param sessionName 订单上显示的场次，如“11月28日 09:00 上午场”
     */
    record OpenPayment(
            UUID enrollmentId,
            UUID accountId,
            UUID organizerId,
            String cityCode,
            UUID activityId,
            UUID sessionId,
            String activityTitle,
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

    /** 没有接交易模块时（只有活动模块的测试、或没有数据库的上下文）：收费报名一律“暂未开通在线支付”。 */
    static ActivityPaymentPort unavailable() {
        return new ActivityPaymentPort() {
            @Override
            public boolean merchantCanCollect(UUID organizationId) {
                return false;
            }

            @Override
            public OpenedPayment open(OpenPayment request) {
                throw new ActivityEnrollmentException(Error.PAYMENT_UNAVAILABLE);
            }

            @Override
            public void lockOrder(UUID tradeOrderId) {
                throw new ActivityEnrollmentException(Error.PAYMENT_UNAVAILABLE);
            }

            @Override
            public UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey,
                                      UUID actorId) {
                throw new ActivityEnrollmentException(Error.PAYMENT_UNAVAILABLE);
            }

            @Override
            public Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds) {
                return Map.of();
            }

            @Override
            public void cancelUnpaid(UUID accountId, UUID tradeOrderId) {
                throw new ActivityEnrollmentException(Error.PAYMENT_UNAVAILABLE);
            }
        };
    }
}
