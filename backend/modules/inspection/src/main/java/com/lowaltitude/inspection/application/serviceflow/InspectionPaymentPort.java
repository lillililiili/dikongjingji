package com.lowaltitude.inspection.application.serviceflow;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * 巡检付款节点用到的付款能力（S4-1）。巡检模块只认这个端口，由启动模块接到统一交易模块（订单来源 INSPECTION，
 * 收款方是巡检服务商，钱进它自己的子商户号）；付款到账和订单关闭由启动模块转给
 * {@link InspectionPaymentService#onPaymentConfirmed} 和 {@link InspectionPaymentService#onPaymentClosed}。
 * 加锁顺序：先交易订单（{@link #lockOrder}），再巡检服务单。
 */
public interface InspectionPaymentPort {

    /** 服务商登记了有效的收款账户，能在线收款。 */
    boolean merchantCanCollect(UUID organizationId);

    /** 在当前事务里建一张待付款的交易订单。服务商收不了款时抛 {@link PlanFlowException}（PROVIDER_PAYMENT_UNAVAILABLE）。 */
    OpenedPayment open(OpenPayment request);

    /** 在当前事务里锁住这张交易订单。 */
    void lockOrder(UUID tradeOrderId);

    /** 在当前事务里建一笔退款，事务提交后交给渠道。 */
    UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey, UUID actorId);

    /** 这些退款现在的进度；不认识的不返回。 */
    Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds);

    /** 取消一张还没付款的交易订单（发起过支付的先向渠道查单、关单）。不能在事务里调用。返回订单最后是否已付款。 */
    boolean cancelUnpaid(UUID accountId, UUID tradeOrderId);

    /**
     * @param attemptId   付款尝试 ID（交易订单的业务单 ID）
     * @param orderTitle  订单上显示的“服务名 · 方案 vN”
     * @param nodeLabel   订单上显示的节点名，如“首付 30%”“第 2/6 次服务费”
     */
    record OpenPayment(
            UUID attemptId,
            UUID customerId,
            UUID providerId,
            String cityCode,
            UUID serviceOrderId,
            String orderTitle,
            String nodeLabel,
            String coverUrl,
            long amountMinor,
            Instant payBefore
    ) {
    }

    record OpenedPayment(UUID tradeOrderId, Instant payBefore) {
    }

    /** status 是交易模块的退款状态：REQUESTED、PROCESSING、SUCCEEDED、CLOSED、ABNORMAL。 */
    record RefundProgress(String status, long amountMinor) {
    }

    /** 没有接交易模块时：带付款节点的方案一律不能确认，其余照常。 */
    static InspectionPaymentPort unavailable() {
        return new InspectionPaymentPort() {
            @Override
            public boolean merchantCanCollect(UUID organizationId) {
                return false;
            }

            @Override
            public OpenedPayment open(OpenPayment request) {
                throw new ProviderPaymentUnavailableException();
            }

            @Override
            public void lockOrder(UUID tradeOrderId) {
                throw new ProviderPaymentUnavailableException();
            }

            @Override
            public UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey,
                                      UUID actorId) {
                throw new ProviderPaymentUnavailableException();
            }

            @Override
            public Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds) {
                return Map.of();
            }

            @Override
            public boolean cancelUnpaid(UUID accountId, UUID tradeOrderId) {
                throw new ProviderPaymentUnavailableException();
            }
        };
    }
}
