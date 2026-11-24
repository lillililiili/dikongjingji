package com.lowaltitude.inspection.course.application;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 课程模块用到的别的模块的能力，由启动模块接上：统一交易模块的付款和退款（订单来源 COURSE，收款方是开课机构）、
 * 赛事研学的活动（线下班卡片显示的标题、封面、价格）、身份模块里机构的名称和资质审核。
 * 本模块不读它们的表。付款到账、订单关闭由启动模块转给 {@link CourseService#onPaymentConfirmed}、
 * {@link CourseService#onPaymentClosed}；商城订单完成转给 {@link CourseService#grantBundled}。
 * 加锁顺序：先交易订单（{@link Payments#lockOrder}），再课程、开通记录。
 */
public final class CoursePorts {

    private CoursePorts() {
    }

    public interface Payments {

        /** 这家机构登记了有效的收款账户，能在线收款。 */
        boolean merchantCanCollect(UUID organizationId);

        /** 在当前事务里建一张待付款的交易订单（来源 COURSE）。收不了款时抛 PAYMENT_UNAVAILABLE。 */
        OpenedPayment open(OpenPayment request);

        /** 在当前事务里锁住这张交易订单。 */
        void lockOrder(UUID tradeOrderId);

        /** 在当前事务里建一笔退款，事务提交后交给渠道。退不了时抛 REFUND_NOT_ALLOWED。 */
        UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey, UUID actorId);

        /** 这些退款现在的进度；不认识的不返回。 */
        Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds);

        /** 没有接交易模块：一律收不了款。 */
        static Payments unavailable() {
            return new Payments() {
                @Override
                public boolean merchantCanCollect(UUID organizationId) {
                    return false;
                }

                @Override
                public OpenedPayment open(OpenPayment request) {
                    throw new CourseException(CourseException.Error.PAYMENT_UNAVAILABLE);
                }

                @Override
                public void lockOrder(UUID tradeOrderId) {
                    throw new CourseException(CourseException.Error.PAYMENT_UNAVAILABLE);
                }

                @Override
                public UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey,
                                          UUID actorId) {
                    throw new CourseException(CourseException.Error.REFUND_NOT_ALLOWED);
                }

                @Override
                public Map<UUID, RefundProgress> refundStates(Collection<UUID> refundIds) {
                    return Map.of();
                }
            };
        }
    }

    public interface Simulations {

        /** 体验目录中已发布且配有可运行模板的仿真项目。 */
        boolean isPlayable(UUID itemId);
    }

    public interface Activities {

        /** 这个活动现在在这个城市公开可见（已上线、城市对得上）时的卡片信息；否则为空。 */
        Optional<Activity> find(UUID activityId, String cityCode);

        /** 这个活动存在（不管上没上线）。运营端上架线下班前用。 */
        boolean exists(UUID activityId);

        static Activities none() {
            return new Activities() {
                @Override
                public Optional<Activity> find(UUID activityId, String cityCode) {
                    return Optional.empty();
                }

                @Override
                public boolean exists(UUID activityId) {
                    return false;
                }
            };
        }
    }

    public interface Organizations {

        /** 在用的机构的名称；不认识、停用的不在结果里。 */
        Map<UUID, String> names(Collection<UUID> organizationIds);

        /** 这些机构里平台审核过资质的那些。 */
        Set<UUID> reviewed(Collection<UUID> organizationIds);

        /** 能开课的机构：在用的服务商或平台机构（运营端选开课机构时校验）。 */
        boolean canHostCourses(UUID organizationId);
    }

    /**
     * 线下班卡片用的活动信息。
     *
     * @param priceMode   PER_PERSON、FREE 或 INQUIRY（活动的收费方式）
     * @param amountMinor 按人收费时的单价（分）；其余为空
     */
    public record Activity(
            UUID activityId,
            String title,
            String coverUrl,
            String coverAlt,
            String cityCode,
            String priceMode,
            Long amountMinor
    ) {
    }

    /** 交易订单的一行：课程（itemId 和 optionId 都是课程 ID）、名称、规格名“单人账号”、单价、数量 1。 */
    public record OpenPayment(
            UUID enrollmentId,
            UUID accountId,
            UUID organizationId,
            UUID courseId,
            String courseTitle,
            String optionName,
            String coverUrl,
            long amountMinor,
            Instant payBefore
    ) {
    }

    public record OpenedPayment(UUID tradeOrderId, Instant payBefore) {
    }

    /** status 是交易模块的退款状态：REQUESTED、PROCESSING、SUCCEEDED、CLOSED、ABNORMAL。 */
    public record RefundProgress(String status, long amountMinor) {
    }
}
