package com.lowaltitude.inspection.support.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 客服会话用到的别的模块，由启动模块接上；本模块不读别的模块的表。 */
public final class SupportPorts {

    private SupportPorts() {
    }

    /** 身份模块：在用的机构。 */
    public interface Organizations {

        /** 不存在或已停用时为空。type 是身份模块的机构类型（CUSTOMER / PROVIDER / PLATFORM）。 */
        Optional<Organization> findActive(UUID organizationId);
    }

    public record Organization(UUID organizationId, String name, String type) {
    }

    /** 身份模块：账号的显示名（对方在会话里只看到这个）。账号不存在或已停用时为空。 */
    public interface Profiles {

        Optional<String> displayName(UUID accountId);
    }

    /** 站内信：对方回复了，给客户记一条“客服消息”（同一会话最多一条未读）；客户进了会话，把它标为已读。 */
    public interface Notifier {

        void replied(UUID customerId, UUID conversationId, String counterpartKind, String counterpartName,
                     UUID messageId, Instant at);

        void read(UUID customerId, UUID conversationId);
    }
}
