package com.lowaltitude.inspection.experience.application;

/**
 * 体验记录的状态。仿真：COMPLETED 全部关卡完成 / UNFINISHED 中途退出；
 * 试用和试听：ACTIVE 试用中 / ENDED 用户主动结束 / EXPIRED 到期（按时间算，不落库）。
 */
public enum ExperienceRecordStatus {
    COMPLETED,
    UNFINISHED,
    ACTIVE,
    ENDED,
    EXPIRED
}
