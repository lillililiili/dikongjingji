package com.lowaltitude.inspection.ops.common;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 运营端的一条改动记录（留痕）：改了什么对象（subjectType + subjectId，比如 ACTIVITY / SESSION）、什么动作
 * （CREATE、UPDATE、PUBLISH、OFFLINE、DELETE 等，各块契约列出）、一句话说明、操作人显示名快照、时间。
 * 各模块把记录写在自己的表里（只追加），用这个形状返回；契约里的 schema 名统一叫 OpsChange。
 */
public record OpsChangeView(
        UUID changeId,
        String subjectType,
        UUID subjectId,
        String action,
        String summary,
        String operatorName,
        OffsetDateTime changedAt
) {
}
