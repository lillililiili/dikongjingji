package com.lowaltitude.inspection.support.infrastructure.persistence;

import org.apache.ibatis.annotations.Select;

/**
 * 运营端首页“经营概览”（契约 contracts/openapi-ops-dashboard.yaml）里客服的汇总数。只做 COUNT / SUM，不读明细。
 * 时间区间都是 [from, to)。
 */
public interface SupportOpsStatsMapper {

    /** 待处理的举报与反馈。 */
    @Select("""
            SELECT COUNT(*) FROM support_feedback WHERE handle_status = 'PENDING'
            """)
    long pendingFeedback();

    /** 客户发来平台客服还没读的会话。 */
    @Select("""
            SELECT COUNT(*) FROM support_conversation
            WHERE counterpart_kind = 'PLATFORM'
              AND counterpart_unread > 0
            """)
    long conversationsAwaitingReply();
}
