package com.lowaltitude.inspection.content.application;

import java.util.Optional;
import java.util.UUID;

/** 视频评论用到的别的模块，由启动模块接上；本模块不读别的模块的表。 */
public final class ContentPorts {

    private ContentPorts() {
    }

    /** 身份模块：账号的显示名（评论打码后显示）。账号不存在或已停用时为空。 */
    public interface Profiles {

        Optional<String> displayName(UUID accountId);
    }

    /**
     * 客服模块：把举报记成一条“内容有误”的反馈，返回反馈 ID。同一账号带同一个幂等键再记，返回第一次那条。
     */
    public interface Reports {

        UUID record(UUID accountId, UUID idempotencyKey, String text);
    }
}
