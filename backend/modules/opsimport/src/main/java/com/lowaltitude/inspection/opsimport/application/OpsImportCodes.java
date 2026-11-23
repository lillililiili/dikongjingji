package com.lowaltitude.inspection.opsimport.application;

// 批量导入的稳定编码（契约 contracts/openapi-ops-import.yaml 1.0.0），与迁移 opsimport V001 的 CHECK 约束一致。

/** 批次状态：CHECKED 预检通过、待导入；REJECTED 预检有问题；IMPORTED 已导入（或和以前导入过的内容相同）。 */
enum OpsImportStatus {
    CHECKED,
    REJECTED,
    IMPORTED
}

/** 改动记录里的对象是新建的还是更新的。 */
enum OpsImportChangeType {
    CREATED,
    UPDATED
}
