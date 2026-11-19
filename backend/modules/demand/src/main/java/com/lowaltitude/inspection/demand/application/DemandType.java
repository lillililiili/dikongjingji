package com.lowaltitude.inspection.demand.application;

import static com.lowaltitude.inspection.demand.application.DemandDeliverable.EDITED_FILM;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.HAZARD_LIST;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.INSPECTION_REPORT;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.ISSUE_LIST;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.MAINTENANCE_ADVICE;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.MODEL_3D;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.OPERATION_TRAINING;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.ORTHOPHOTO;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.PANORAMA;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.PARTS_LIST;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.PHOTOS;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.POINT_CLOUD;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.REPAIR_RECORD;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.SERVICE_RECORD;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.SOLUTION_ADVICE;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.SURVEY_REPORT;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.TEST_REPORT;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.TOPOGRAPHIC_MAP;
import static com.lowaltitude.inspection.demand.application.DemandDeliverable.VIDEO_FOOTAGE;

import java.util.List;

/**
 * 服务类型（契约 DemandType）和每类能选的交付物，取自 2026-11-14 修订版原型 Demand 画板。
 * 交付物按页面显示的顺序；草稿保存和提交都只认这里列出的。
 */
public enum DemandType {
    INSPECTION("巡检", List.of(INSPECTION_REPORT, ORTHOPHOTO, HAZARD_LIST, MODEL_3D, VIDEO_FOOTAGE)),
    AERIAL("航拍", List.of(VIDEO_FOOTAGE, PHOTOS, PANORAMA, EDITED_FILM)),
    SURVEY("测绘", List.of(ORTHOPHOTO, MODEL_3D, POINT_CLOUD, TOPOGRAPHIC_MAP, SURVEY_REPORT)),
    MAINTENANCE("设备维护", List.of(REPAIR_RECORD, TEST_REPORT, PARTS_LIST, MAINTENANCE_ADVICE)),
    TECH_SUPPORT("技术支持", List.of(SERVICE_RECORD, ISSUE_LIST, OPERATION_TRAINING, SOLUTION_ADVICE));

    private final String label;
    private final List<DemandDeliverable> deliverables;

    DemandType(String label, List<DemandDeliverable> deliverables) {
        this.label = label;
        this.deliverables = deliverables;
    }

    public String label() {
        return label;
    }

    public List<DemandDeliverable> deliverables() {
        return deliverables;
    }
}
