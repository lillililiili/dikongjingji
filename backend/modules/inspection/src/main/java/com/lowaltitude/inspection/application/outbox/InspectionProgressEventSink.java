package com.lowaltitude.inspection.application.outbox;

/**
 * 巡检流程事件的订阅方。在转发器为这条事件开的事务里被调用：
 * 正常返回后事件才标记为已发布；抛出异常则整条回滚，稍后重试。
 * 同一条事件可能被再次送达，订阅方要按 eventId 去重。
 */
public interface InspectionProgressEventSink {

    void accept(InspectionProgressEvent event);
}
