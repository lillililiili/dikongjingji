package com.lowaltitude.inspection.serviceorder.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderCustomerKind;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderDeliveryStatus;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderEventType;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderParty;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderRefundKind;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderStatus;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderCodes.ServiceOrderType;

/** 接口返回的对象（契约 contracts/openapi-service-order.yaml 1.0.0 的 schema 一一对应）。 */
public final class ServiceOrderViews {

    private ServiceOrderViews() {
    }

    public record ServiceOrderCustomerView(ServiceOrderCustomerKind kind, String organizationName, String contactName) {
    }

    public record ServiceOrderProviderView(UUID providerId, String name, boolean qualificationReviewed) {
    }

    public record ServiceOrderLineView(String name, Integer quantity, String unit, Long unitAmountMinor,
                                       long amountMinor) {
    }

    public record ServiceOrderQuoteView(
            UUID quoteId,
            int quoteVersion,
            String method,
            String schedule,
            int durationDays,
            String deliverables,
            String team,
            String nightWork,
            Integer reportHours,
            Integer archiveDays,
            LocalDate firstVisitDate
    ) {
    }

    public record ServiceOrderPlanView(
            int version,
            long amountMinor,
            String schedule,
            List<String> terms,
            String note,
            OffsetDateTime issuedAt,
            OffsetDateTime confirmedAt,
            String confirmedBy
    ) {
    }

    /** status 是交易模块的退款状态。 */
    public record ServiceOrderRefundView(
            UUID refundId,
            ServiceOrderRefundKind kind,
            long amountMinor,
            String reason,
            String status,
            OffsetDateTime requestedAt
    ) {
    }

    public record ServiceOrderPaymentView(
            long amountMinor,
            boolean collectable,
            OffsetDateTime paidAt,
            UUID pendingTradeOrderId,
            OffsetDateTime pendingPayBefore,
            UUID paidTradeOrderId,
            long refundedMinor,
            List<ServiceOrderRefundView> refunds
    ) {
    }

    public record ServiceOrderFileView(UUID fileId, String name, String contentType, long sizeBytes,
                                       OffsetDateTime uploadedAt) {
    }

    public record ServiceOrderDeliveryView(
            int round,
            ServiceOrderDeliveryStatus status,
            String note,
            List<ServiceOrderFileView> files,
            OffsetDateTime deliveredAt,
            String reviewNote,
            OffsetDateTime reviewedAt,
            String reviewedBy
    ) {
    }

    public record ServiceOrderEventView(ServiceOrderEventType type, OffsetDateTime occurredAt, Integer planVersion,
                                        Integer round) {
    }

    public record ServiceOrderActionsView(
            boolean confirmPlan,
            boolean pay,
            boolean cancel,
            boolean accept,
            boolean rework,
            boolean issuePlan,
            boolean start,
            boolean deliver,
            boolean terminate
    ) {
    }

    public record ServiceOrderView(
            UUID orderId,
            String orderNo,
            ServiceOrderStatus status,
            ServiceOrderType type,
            String title,
            UUID demandId,
            ServiceOrderCustomerView customer,
            ServiceOrderProviderView provider,
            String cityCode,
            String cityName,
            String address,
            String scope,
            LocalDate dueDate,
            long amountMinor,
            List<ServiceOrderLineView> lines,
            ServiceOrderQuoteView quote,
            ServiceOrderPlanView plan,
            ServiceOrderPaymentView payment,
            List<ServiceOrderDeliveryView> deliveries,
            List<ServiceOrderFileView> stagedFiles,
            List<ServiceOrderEventView> events,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            OffsetDateTime closedAt,
            ServiceOrderParty closedBy,
            String closeReason,
            ServiceOrderActionsView actions,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    public record ServiceOrderSummaryView(
            UUID orderId,
            String orderNo,
            ServiceOrderStatus status,
            ServiceOrderType type,
            String title,
            String scope,
            String cityName,
            ServiceOrderCustomerView customer,
            ServiceOrderProviderView provider,
            long amountMinor,
            Integer planVersion,
            OffsetDateTime paidAt,
            OffsetDateTime latestDeliveryAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    public record ServiceOrderPageView(int page, int size, long total, boolean hasMore,
                                       List<ServiceOrderSummaryView> items) {
    }

    public record ServiceOrderPaymentStartView(UUID orderId, UUID tradeOrderId, long amountMinor,
                                               OffsetDateTime payBefore) {
    }

    public record DownloadIntentView(String url, OffsetDateTime expiresAt) {
    }

    /** 一个文件的内容（不是接口 schema：控制器直接写成响应体）。 */
    public record ServiceOrderFileContent(String name, String contentType, byte[] content) {
    }

    /**
     * 需求模块选定报价时的建单内容（需求和那一版报价的快照）。
     *
     * @param organizationId   需求记在客户组织名下时的组织；个人时为空
     * @param lines            报价明细；为空时订单只有一行“服务费用”
     */
    public record ServiceOrderOpening(
            UUID demandId,
            UUID quoteId,
            int quoteVersion,
            UUID accountId,
            UUID organizationId,
            String organizationName,
            UUID providerId,
            String providerName,
            String type,
            String title,
            String cityCode,
            String cityName,
            String address,
            String scope,
            LocalDate dueDate,
            long amountMinor,
            String method,
            String schedule,
            int durationDays,
            String deliverables,
            String team,
            String nightWork,
            Integer reportHours,
            Integer archiveDays,
            LocalDate firstVisitDate,
            List<OpeningLine> lines
    ) {
    }

    public record OpeningLine(String name, Integer quantity, String unit, Long unitAmountMinor, long amountMinor) {
    }

    /** 服务商出方案的内容。 */
    public record PlanDraft(String schedule, List<String> terms, String note) {
    }
}
