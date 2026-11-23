package com.lowaltitude.inspection.course.application;

// 课程的稳定编码（契约 contracts/openapi-course.yaml）。

/** ONLINE 线上录播课（课程模块卖）；OFFLINE 线下班（指向一个活动，在赛事研学报名）。 */
enum CourseMode {
    ONLINE,
    OFFLINE
}

/** 商城“课程”页的子类目（画板 MallCourse）：无人机、机器人、编程、考证。 */
enum CourseTopic {
    DRONE,
    ROBOT,
    PROGRAMMING,
    CERTIFICATE
}

enum CourseSort {
    DEFAULT,
    PRICE_ASC,
    PRICE_DESC
}

/** FIXED 标价；FREE 免费；INQUIRY 价格面议（线下班的活动按询价）。 */
enum CoursePriceMode {
    FIXED,
    FREE,
    INQUIRY
}

/** 对外的开通状态：EXPIRED 是开通着但过了有效期（按时间算，不落库）。 */
enum CourseEnrollmentStatus {
    PENDING_PAYMENT,
    ACTIVE,
    EXPIRED,
    CLOSED,
    REFUNDED
}

enum CourseEnrollmentSource {
    PURCHASE,
    BUNDLE
}

/** 我的课程的三个胶囊（画板 MyCourses）。 */
enum CourseEnrollmentBucket {
    ALL,
    LEARNING,
    FINISHED
}

enum CourseLessonState {
    NOT_STARTED,
    STARTED,
    COMPLETED
}

/** 交易模块的退款状态。 */
enum CourseRefundStatus {
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    CLOSED,
    ABNORMAL
}

/** 为什么不能在线退款。 */
enum CourseRefundBlock {
    BUNDLED,
    NOT_ACTIVE,
    STARTED,
    WINDOW_PASSED
}

/** 落库的状态（course_enrollment.status）。 */
final class CourseEnrollmentRecord {

    static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    static final String ACTIVE = "ACTIVE";
    static final String CLOSED = "CLOSED";
    static final String REFUNDED = "REFUNDED";
    static final String EXPIRED = "EXPIRED";

    private CourseEnrollmentRecord() {
    }
}

/** 落库的课程状态（course.status）。 */
final class CourseRecordStatus {

    static final String DRAFT = "DRAFT";
    static final String LISTED = "LISTED";
    static final String UNLISTED = "UNLISTED";

    private CourseRecordStatus() {
    }
}
