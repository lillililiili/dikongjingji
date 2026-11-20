package com.lowaltitude.inspection.experience.application;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 到馆预约成功和改期告诉预约的账号（R6-5，docs/redesign/06-原型对齐修正计划.md）。
 * 体验模块只认这个端口，启动模块把它接到站内信；两个模块互不依赖。
 *
 * <p>在预约的同一个事务里调用。实现者自己决定什么时候写（启动模块在事务提交之后写），而且不能抛异常。
 * 文字里只有场馆名、项目名、场次开始时间和人数，不带参与人姓名和核销码。
 */
public interface VenueBookingNotifier {

    /** 预约成功：免费项目提交即成功，收费项目付款到账时。 */
    void booked(Booking booking);

    /**
     * 改到了另一场。
     *
     * @param fromVersion 改期前的预约版本：同一张预约每改一次都不一样，通知按它去重
     */
    void rescheduled(Booking booking, int fromVersion);

    /** @param startsAt 预约现在所在场次的开始时间 */
    record Booking(
            UUID accountId,
            UUID bookingId,
            String venueName,
            String projectName,
            OffsetDateTime startsAt,
            int people,
            Instant at
    ) {
    }

    /** 没有接站内信时。 */
    static VenueBookingNotifier none() {
        return new VenueBookingNotifier() {
            @Override
            public void booked(Booking booking) {
            }

            @Override
            public void rescheduled(Booking booking, int fromVersion) {
            }
        };
    }
}
