package com.lowaltitude.inspection.live.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 直播用到的别的模块，由启动模块接上；本模块不读别的模块的表。 */
public final class LivePorts {

    private LivePorts() {
    }

    /** 身份模块：在用的机构的名称。不存在或已停用时为空。 */
    public interface Organizations {

        Optional<String> activeName(UUID organizationId);
    }

    /** 身份模块：账号的显示名。账号不存在或已停用时为空。 */
    public interface Profiles {

        Optional<String> displayName(UUID accountId);
    }

    /** 站内信：开播了，给设了提醒的这个账号记一条“直播提醒”。在开播的事务提交之后写，同一直播间每人只记一次。 */
    public interface Reminders {

        void liveStarted(UUID accountId, UUID roomId, String title, String hostName, Instant at);
    }

    /** 客服反馈：记一条举报。同一个幂等键带同样的内容只记一次。 */
    public interface Reports {

        void report(UUID accountId, UUID idempotencyKey, String text);
    }

    /** 弹幕的关键词过滤。true 表示不能发。 */
    public interface CommentFilter {

        boolean blocks(String text);
    }
}
