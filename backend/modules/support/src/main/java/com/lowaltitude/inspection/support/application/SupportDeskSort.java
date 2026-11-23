package com.lowaltitude.inspection.support.application;

/**
 * 回复方会话列表的排序（契约 openapi-support.yaml 1.1.0 的 sort 参数）：
 * LATEST 最后一条消息新的在前（原来的排法，也是默认）；UNREAD_FIRST 有未读的在前，各自再按最后一条消息新的在前。
 */
public enum SupportDeskSort {
    LATEST,
    UNREAD_FIRST
}
