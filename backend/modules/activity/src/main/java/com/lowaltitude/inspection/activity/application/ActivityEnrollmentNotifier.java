package com.lowaltitude.inspection.activity.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 报名成功告诉报名的账号（R6-5，docs/redesign/06-原型对齐修正计划.md）：免费报名提交成功、收费报名付款到账。
 * 活动模块只认这个端口，启动模块把它接到站内信；两个模块互不依赖。
 *
 * <p>在报名的同一个事务里调用。实现者自己决定什么时候写（启动模块在事务提交之后写），而且不能抛异常。
 * 报名已退款由交易模块的退款到账转告，不经过这里。
 */
@FunctionalInterface
public interface ActivityEnrollmentNotifier {

    void enrolled(Enrolled event);

    /**
     * 报名成功的事实：只有活动名、场次开始时间和人数，不带参与人姓名。
     *
     * @param people 这张报名单里生效的人数
     * @param paid   收费报名（付款到账时）为 true
     */
    record Enrolled(
            UUID accountId,
            UUID enrollmentId,
            String activityTitle,
            OffsetDateTime startsAt,
            int people,
            boolean paid,
            Instant at
    ) {
    }

    /** 没有接站内信时。 */
    static ActivityEnrollmentNotifier none() {
        return event -> {
        };
    }
}
