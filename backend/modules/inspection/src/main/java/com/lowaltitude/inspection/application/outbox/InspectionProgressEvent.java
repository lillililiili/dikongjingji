package com.lowaltitude.inspection.application.outbox;

import java.time.Instant;
import java.util.UUID;

/**
 * 一条已提交的巡检流程事件，带上订阅方需要的稳定标识。
 * planId、serviceOrderId、visitId、visitNo、plannedStartAt 只在事件所在的阶段有值。
 * 不带联系人、地址这类敏感内容。
 */
public record InspectionProgressEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID customerId,
        UUID reservationId,
        String reservationNo,
        String serviceTitle,
        UUID planId,
        UUID serviceOrderId,
        UUID visitId,
        Integer visitNo,
        Instant plannedStartAt
) {
}
