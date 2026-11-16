package com.lowaltitude.inspection.activity.application;

/** 活动类型：赛事、培训、研学、体验日。 */
public enum ActivityType {
    CONTEST,
    TRAINING,
    STUDY_TOUR,
    OPEN_DAY;

    /** 查询里“不限类型”的占位值，不是一种活动类型。 */
    static final String ALL = "ALL";

    static String parseFilter(String code) {
        if (code == null || code.isBlank()) {
            return ALL;
        }
        try {
            return valueOf(code.trim()).name();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("activity type is invalid");
        }
    }
}
