package com.lowaltitude.inspection.course.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.activity.application.ActivityCourseListings;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.trade.application.TradeException;
import com.lowaltitude.inspection.trade.application.TradeOrderCompletionListener;
import com.lowaltitude.inspection.trade.application.TradeSourceListener;
import com.lowaltitude.inspection.trade.application.TradeSourceOrders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 课程模块的端口接到别的模块（S5-5）：统一交易模块（订单来源 COURSE，收款方是开课机构）、赛事研学的活动（线下班卡片）、
 * 身份模块的机构（名称、资质审核、能不能开课）；交易订单付款、关闭时转给课程服务，商城订单完成时转给
 * {@link CourseService#grantBundled}（附带的数字课程开通）。几个模块互不依赖。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class CoursePortsConfiguration {

    static final String SOURCE = "COURSE";
    /** 能开课的机构类型：服务商（培训机构、商家都是服务商机构）和平台机构。 */
    static final Set<String> HOST_TYPES = Set.of("PROVIDER", "PLATFORM");

    @Bean
    CoursePorts.Payments coursePayments(TradeSourceOrders orders) {
        return new TradeBackedCoursePayments(orders);
    }

    @Bean
    CoursePorts.Activities courseActivities(ActivityCourseListings listings) {
        return new CoursePorts.Activities() {
            @Override
            public Optional<CoursePorts.Activity> find(UUID activityId, String cityCode) {
                return listings.find(activityId, cityCode).map(listing -> new CoursePorts.Activity(
                        listing.activityId(), listing.title(), listing.coverUrl(), listing.coverAlt(), cityCode,
                        listing.priceMode(), listing.amountMinor()));
            }

            @Override
            public boolean exists(UUID activityId) {
                return listings.exists(activityId);
            }
        };
    }

    @Bean
    CoursePorts.Organizations courseOrganizations(IdentityOrganizationPort organizations) {
        return new CoursePorts.Organizations() {
            @Override
            public Map<UUID, String> names(Collection<UUID> organizationIds) {
                Map<UUID, String> names = new LinkedHashMap<>();
                for (UUID id : Set.copyOf(organizationIds)) {
                    organizations.findActiveOrganization(id).ifPresent(found -> names.put(id, found.name()));
                }
                return names;
            }

            @Override
            public Set<UUID> reviewed(Collection<UUID> organizationIds) {
                return organizations.qualificationReviewed(organizationIds);
            }

            @Override
            public boolean canHostCourses(UUID organizationId) {
                return organizations.findActiveOrganization(organizationId)
                        .map(found -> HOST_TYPES.contains(found.organizationType()))
                        .orElse(false);
            }

            @Override
            public boolean listingSuspended(UUID organizationId) {
                return organizations.findActiveOrganization(organizationId)
                        .map(found -> found.listingSuspended())
                        .orElse(false);
            }
        };
    }

    @Bean
    TradeSourceListener courseTradeSourceListener(CourseService courses) {
        return new TradeSourceListener() {
            @Override
            public String source() {
                return SOURCE;
            }

            @Override
            public boolean onPaid(UUID orderId, UUID sourceRef) {
                return courses.onPaymentConfirmed(orderId, sourceRef);
            }

            @Override
            public void onClosed(UUID orderId, UUID sourceRef) {
                courses.onPaymentClosed(orderId, sourceRef);
            }
        };
    }

    /** 商城订单完成：订单里附带数字课程的商品给买家开通课程。 */
    @Bean
    TradeOrderCompletionListener courseBundleListener(CourseService courses) {
        return order -> courses.grantBundled(
                order.buyerId(),
                order.orderId(),
                order.orderNo(),
                order.lines().stream()
                        .map(line -> new CourseService.BundledLine(line.productId(), line.productName(), line.skuName()))
                        .toList(),
                order.completedAt());
    }

    /** 交易模块的拒绝换成课程自己的错误，页面拿到的是 COURSE_ 错误码。 */
    static final class TradeBackedCoursePayments implements CoursePorts.Payments {

        private final TradeSourceOrders orders;

        TradeBackedCoursePayments(TradeSourceOrders orders) {
            this.orders = orders;
        }

        @Override
        public boolean merchantCanCollect(UUID organizationId) {
            return orders.merchantCanCollect(organizationId);
        }

        @Override
        public CoursePorts.OpenedPayment open(CoursePorts.OpenPayment request) {
            try {
                TradeSourceOrders.OpenedOrder opened = orders.open(new TradeSourceOrders.OpenCommand(
                        SOURCE,
                        request.enrollmentId(),
                        request.accountId(),
                        request.organizationId(),
                        // 线上课不分城市；交易订单要一个城市编码，记“全国”。
                        NATIONWIDE,
                        List.of(new TradeSourceOrders.Line(request.courseId(), request.courseId(), request.courseTitle(),
                                request.optionName(), request.coverUrl(), request.amountMinor(), 1)),
                        request.payBefore()));
                return new CoursePorts.OpenedPayment(opened.orderId(), opened.payBefore());
            } catch (TradeException exception) {
                if (exception.error() == TradeException.Error.MERCHANT_PAYMENT_UNAVAILABLE) {
                    throw new CourseException(CourseException.Error.PAYMENT_UNAVAILABLE);
                }
                throw exception;
            }
        }

        @Override
        public void lockOrder(UUID tradeOrderId) {
            orders.lockOrder(tradeOrderId);
        }

        @Override
        public UUID requestRefund(UUID tradeOrderId, long amountMinor, String reason, UUID idempotencyKey,
                                  UUID actorId) {
            try {
                return orders.requestRefund(tradeOrderId, amountMinor, reason, idempotencyKey, actorId);
            } catch (TradeException exception) {
                switch (exception.error()) {
                    case REFUND_EXCEEDS_PAID, REFUND_LIMIT_REACHED, ORDER_STATE_INVALID ->
                            throw new CourseException(CourseException.Error.REFUND_NOT_ALLOWED,
                                    Map.of("reason", "NOT_ACTIVE"));
                    default -> throw exception;
                }
            }
        }

        @Override
        public Map<UUID, CoursePorts.RefundProgress> refundStates(Collection<UUID> refundIds) {
            return orders.refundStates(refundIds).stream()
                    .collect(Collectors.toMap(
                            TradeSourceOrders.RefundState::refundId,
                            state -> new CoursePorts.RefundProgress(state.status(), state.amountMinor())));
        }
    }

    /** 交易订单的城市编码：线上课全国可买，记 000000。 */
    static final String NATIONWIDE = "000000";
}
