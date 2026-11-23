package com.lowaltitude.inspection.checkin.application;

/**
 * 核销被拦下（记录已经落库）：全局异常处理回 409，code 是 CHECKIN_ 加结果名，details.record 是那条记录。
 */
public final class CheckinRejectedException extends RuntimeException {

    private final transient CheckinRecordView record;

    CheckinRejectedException(CheckinRecordView record) {
        super(record.outcome().name());
        this.record = record;
    }

    /** 契约 CheckinErrorCode 里的错误码。 */
    public String code() {
        return "CHECKIN_" + record.outcome().name();
    }

    public String message() {
        return switch (record.outcome()) {
            case REPEATED -> "这张凭证已经核销过";
            case EXPIRED -> "凭证已过期";
            case OTHER_ORGANIZATION -> "不是本机构的凭证";
            case SESSION_MISMATCH -> "凭证不属于当前核销场次";
            case CANCELLED -> "凭证已取消";
            case UNPAID -> "凭证还没付款";
            case CHECKED_IN -> throw new IllegalStateException("a checked-in record is not a rejection");
        };
    }

    /** 那条记录（CheckinRecord），放进 ApiError 的 details。 */
    public Object record() {
        return record;
    }
}
