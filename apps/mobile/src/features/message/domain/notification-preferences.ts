// The account's notification switches (contract openapi-notification.yaml 1.5.0, board Settings).
// Switches only govern pushes: the 消息 tab keeps every notification. 订单与服务进度's 站内消息 is
// always on; 系统通知 has no switch.
export interface NotificationPreferences {
  // 订单与服务进度 推送: 服务进度、需求与报价、客服消息、交易物流.
  orderServicePush: boolean
  // 活动提醒: 活动提醒、直播提醒 pushes.
  activityPush: boolean
  // 发现内容更新红点: whether the Discover tab shows its dot.
  discoverDot: boolean
}

export type NotificationSwitch = keyof NotificationPreferences

export const DEFAULT_NOTIFICATION_PREFERENCES: NotificationPreferences = {
  orderServicePush: true,
  activityPush: true,
  discoverDot: true,
}
