package com.lowaltitude.inspection.demand.application;

/** 需要的交付物（契约 DemandDeliverable）。和表上的 CHECK 一致；每种服务类型能选哪些见 {@link DemandType}。 */
public enum DemandDeliverable {
    INSPECTION_REPORT("巡检报告"),
    ORTHOPHOTO("正射影像"),
    HAZARD_LIST("隐患清单"),
    MODEL_3D("三维模型"),
    VIDEO_FOOTAGE("视频素材"),
    PHOTOS("照片"),
    PANORAMA("全景图"),
    EDITED_FILM("成片剪辑"),
    POINT_CLOUD("点云数据"),
    TOPOGRAPHIC_MAP("地形图"),
    SURVEY_REPORT("测绘报告"),
    REPAIR_RECORD("维修记录"),
    TEST_REPORT("检测报告"),
    PARTS_LIST("更换件清单"),
    MAINTENANCE_ADVICE("保养建议"),
    SERVICE_RECORD("服务记录"),
    ISSUE_LIST("问题清单"),
    OPERATION_TRAINING("操作培训"),
    SOLUTION_ADVICE("方案建议");

    private final String label;

    DemandDeliverable(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
