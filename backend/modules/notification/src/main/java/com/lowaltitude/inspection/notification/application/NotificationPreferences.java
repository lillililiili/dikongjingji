package com.lowaltitude.inspection.notification.application;

/**
 * 一个账号的通知开关（设置页“通知”，画板 Settings）。没有存过就是全开。
 *
 * <p>规则：开关只管推送，站内信一律照记——关掉以后“消息”页照样能看到，只是手机上不再弹出来。
 * <ul>
 *   <li>订单与服务进度的推送（orderServicePush）：服务进度、需求与报价、客服消息、交易物流。站内消息始终开启。</li>
 *   <li>活动提醒（activityPush）：活动提醒、直播提醒的推送。</li>
 *   <li>系统通知没有开关，一直推送：协议更新等平台公告要送到每个人。</li>
 *   <li>发现内容更新红点（discoverDot）：底栏“发现”的红点，只是 App 的显示开关，和推送无关。</li>
 * </ul>
 */
public record NotificationPreferences(boolean orderServicePush, boolean activityPush, boolean discoverDot) {

    public static final NotificationPreferences DEFAULTS = new NotificationPreferences(true, true, true);

    public boolean allowsPush(NotificationCategory category) {
        return switch (category) {
            case SERVICE_PROGRESS, DEMAND_QUOTE, CUSTOMER_SERVICE, TRADE_LOGISTICS -> orderServicePush;
            case ACTIVITY_REMINDER, LIVE_REMINDER -> activityPush;
            case SYSTEM_NOTICE -> true;
        };
    }

    /** 只改给了值的开关（null 不动）。 */
    public NotificationPreferences with(Boolean orderServicePush, Boolean activityPush, Boolean discoverDot) {
        return new NotificationPreferences(
                orderServicePush == null ? this.orderServicePush : orderServicePush,
                activityPush == null ? this.activityPush : activityPush,
                discoverDot == null ? this.discoverDot : discoverDot);
    }
}
