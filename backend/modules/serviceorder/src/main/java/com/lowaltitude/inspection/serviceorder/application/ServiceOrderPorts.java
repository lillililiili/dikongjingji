package com.lowaltitude.inspection.serviceorder.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 服务订单模块用到的别的模块的能力，由启动模块接上（§28）：统一交易模块的付款和退款、身份模块的账号显示名和服务商资质、
 * 交付文件存储。本模块不读它们的表。付款到账和交易订单关闭由启动模块转给 {@link ServiceOrderService#onPaymentConfirmed}
 * 和 {@link ServiceOrderService#onPaymentClosed}。加锁顺序：先交易订单（{@link Payments#lockOrder}），再服务订单。
 */
public final class ServiceOrderPorts {

    private ServiceOrderPorts() {
    }

    public interface Payments {

        /** 这家机构登记了有效的收款账户，能在线收款。 */
        boolean merchantCanCollect(UUID organizationId);

        /** 在当前事务里建一张待付款的交易订单（来源 PRO_SERVICE，sourceRef 是付款尝试 ID）。收不了款时抛 PAYMENT_UNAVAILABLE。 */
        OpenedPayment open(OpenPayment request);

        /** 在当前事务里锁住这张交易订单。 */
        void lockOrder(UUID tradeOrderId);

        /** 不在事务里调用：关掉一张还没付款的交易订单（先向渠道查单）。返回 true 表示查单发现已经付了。 */
        boolean cancelUnpaid(UUID buyerId, UUID tradeOrderId);

        /** 在当前事务里建一笔退款，事务提交后交给渠道。退不了时抛 REFUND_NOT_ALLOWED。 */
        UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey, UUID actorId);

        /** 这些退款现在的进度（REQUESTED、PROCESSING、SUCCEEDED、CLOSED、ABNORMAL）；不认识的不返回。 */
        Map<UUID, String> refundStates(Collection<UUID> refundIds);
    }

    public interface People {

        /** 账号的显示名；账号不存在或停用时为空。 */
        Optional<String> displayName(UUID accountId);

        /** 这些机构里平台审核过资质的那些。 */
        Set<UUID> qualificationReviewed(Collection<UUID> organizationIds);
    }

    /**
     * 交付文件存储。键由本模块生成（orders/{订单 ID}/files/{文件 ID}）。开发环境是本机目录，其他环境没配置时一律不可用。
     * 不可用时抛 {@link FileStoreUnavailableException}。
     */
    public interface Files {

        void put(String key, byte[] content);

        byte[] read(String key);

        void delete(String key);
    }

    public static final class FileStoreUnavailableException extends RuntimeException {

        public FileStoreUnavailableException(String message) {
            super(message);
        }

        public FileStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 交易订单的一行：productId 是服务订单 ID，offerId 是付款尝试 ID。
     *
     * @param itemName   “需求标题 · 服务商名”
     * @param optionName “方案 vN”
     */
    public record OpenPayment(
            UUID attemptId,
            UUID serviceOrderId,
            UUID accountId,
            UUID providerId,
            String cityCode,
            String itemName,
            String optionName,
            long amountMinor,
            Instant payBefore
    ) {
    }

    public record OpenedPayment(UUID tradeOrderId, Instant payBefore) {
    }
}
