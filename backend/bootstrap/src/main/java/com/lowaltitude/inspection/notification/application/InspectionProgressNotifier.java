package com.lowaltitude.inspection.notification.application;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import com.lowaltitude.inspection.application.outbox.InspectionProgressEvent;
import com.lowaltitude.inspection.application.outbox.InspectionProgressEventSink;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 把巡检流程事件变成发给客户的“服务进度”通知。
 * 只通知服务商一侧推进、需要客户知道的节点；客户自己做的动作（确认方案、验收、取消）
 * 和太细的过程（中途进度）不通知。文案里只有服务名和第几次，不带地址、联系人。
 */
@Component
// 和站内信服务同一个条件：没有数据库时两者都不存在。
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class InspectionProgressNotifier implements InspectionProgressEventSink {

    private static final DateTimeFormatter BEIJING_TIME =
            DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private static final int MAX_TITLE_IN_BODY = 60;

    private final NotificationService notifications;

    public InspectionProgressNotifier(NotificationService notifications) {
        this.notifications = notifications;
    }

    @Override
    public void accept(InspectionProgressEvent event) {
        toNotification(event).ifPresent(notifications::record);
    }

    static Optional<NewNotification> toNotification(InspectionProgressEvent event) {
        String service = shorten(event.serviceTitle());
        String visit = event.visitNo() == null ? "" : "第 " + event.visitNo() + " 次";
        return switch (event.eventType()) {
            case "INSPECTION_PLAN_PUBLISHED" -> notification(event, "方案已出",
                    service + "的方案已发布，请查看并确认。",
                    NotificationTargetType.INSPECTION_PLAN, event.planId(), null);
            case "INSPECTION_ORDER_ARRANGED" -> notification(event, "服务团队已安排",
                    service + "已安排服务团队和设备。",
                    NotificationTargetType.INSPECTION_FULFILLMENT, event.serviceOrderId(), null);
            case "INSPECTION_VISIT_SCHEDULED" -> notification(event, "巡检已排期",
                    service + visit + "巡检" + (event.plannedStartAt() == null
                            ? "已排期。"
                            : "定在 " + BEIJING_TIME.format(event.plannedStartAt()) + " 开始。"),
                    NotificationTargetType.INSPECTION_FULFILLMENT, event.serviceOrderId(), event.visitId());
            case "INSPECTION_VISIT_SERVICE_STARTED" -> notification(event, "巡检已开始",
                    service + visit + "巡检已开始。",
                    NotificationTargetType.INSPECTION_FULFILLMENT, event.serviceOrderId(), event.visitId());
            case "INSPECTION_VISIT_SERVICE_COMPLETED" -> notification(event, "现场巡检完成",
                    service + visit + "现场巡检已完成，报告整理中。",
                    NotificationTargetType.INSPECTION_FULFILLMENT, event.serviceOrderId(), event.visitId());
            case "INSPECTION_DELIVERY_PUBLISHED" -> notification(event, "巡检报告已交付",
                    service + visit + "报告已交付，请核对后验收。",
                    NotificationTargetType.INSPECTION_DELIVERY, event.serviceOrderId(), event.visitId());
            default -> Optional.empty();
        };
    }

    private static Optional<NewNotification> notification(
            InspectionProgressEvent event,
            String title,
            String body,
            NotificationTargetType targetType,
            java.util.UUID targetId,
            java.util.UUID targetSubId
    ) {
        if (event.customerId() == null || targetId == null) {
            throw new IllegalStateException(event.eventType() + " has no recipient or target");
        }
        return Optional.of(new NewNotification(
                event.customerId(),
                NotificationCategory.SERVICE_PROGRESS,
                event.eventType(),
                title,
                body,
                targetType,
                targetId,
                targetSubId,
                "inspection-outbox:" + event.eventId(),
                event.occurredAt()));
    }

    private static String shorten(String serviceTitle) {
        String title = serviceTitle == null || serviceTitle.isBlank() ? "巡检服务" : serviceTitle.trim();
        return title.length() > MAX_TITLE_IN_BODY ? title.substring(0, MAX_TITLE_IN_BODY) + "…" : title;
    }
}
