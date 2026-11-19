package com.lowaltitude.inspection.support.infrastructure.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 客服会话、消息和反馈的读写（G1）。全部用绑定参数。客户的读写以客户账号为条件；回复方的读写以 DESK 为条件：
 * 平台客服只看对方是平台客服的会话，机构操作员只看对方是本机构（商家或服务商）的会话。“当前时间”由调用方传入。
 */
public interface SupportMapper {

    /** 同一个发送人（或反馈人）的写入排队：幂等键查重和写入之间不会插进同一个人的另一次提交。 */
    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended('support-sender:' || CAST(#{accountId} AS text), 0))
            ) locked
            """)
    Integer lockSender(@Param("accountId") UUID accountId);

    // ---- 会话 ----

    /** 新开一个会话；客户和这一方已经有了时什么都不写（两个部分唯一索引）。 */
    @Insert("""
            INSERT INTO support_conversation (
                customer_id, customer_name, counterpart_kind, counterpart_org_id, counterpart_name, created_at, updated_at
            )
            VALUES (#{customerId}, #{customerName}, #{counterpartKind}, #{counterpartOrgId}, #{counterpartName}, #{at}, #{at})
            ON CONFLICT DO NOTHING
            """)
    int insertConversation(
            @Param("customerId") UUID customerId,
            @Param("customerName") String customerName,
            @Param("counterpartKind") String counterpartKind,
            @Param("counterpartOrgId") UUID counterpartOrgId,
            @Param("counterpartName") String counterpartName,
            @Param("at") OffsetDateTime at
    );

    String CONVERSATION_COLUMNS = """
            SELECT c.id,
                   c.customer_id,
                   c.customer_name,
                   c.counterpart_kind,
                   c.counterpart_org_id,
                   c.counterpart_name,
                   c.message_count,
                   c.customer_unread,
                   c.counterpart_unread,
                   c.last_message_at,
                   c.created_at,
                   c.version,
                   last_message.sender_side last_sender_side,
                   last_message.kind last_kind,
                   last_message.text_body last_text,
                   last_message.card_title last_card_title
            FROM support_conversation c
            LEFT JOIN support_message last_message
              ON last_message.conversation_id = c.id AND last_message.seq = c.message_count
            """;

    @Select(CONVERSATION_COLUMNS + """
            WHERE c.customer_id = #{customerId}
              AND c.counterpart_kind = #{counterpartKind}
              AND c.counterpart_org_id IS NOT DISTINCT FROM CAST(#{counterpartOrgId} AS uuid)
            """)
    ConversationRow findParty(
            @Param("customerId") UUID customerId,
            @Param("counterpartKind") String counterpartKind,
            @Param("counterpartOrgId") UUID counterpartOrgId
    );

    @Select(CONVERSATION_COLUMNS + "WHERE c.id = #{conversationId} AND c.customer_id = #{customerId}")
    ConversationRow findCustomerConversation(@Param("customerId") UUID customerId,
                                             @Param("conversationId") UUID conversationId);

    @Select(CONVERSATION_COLUMNS + "WHERE c.id = #{conversationId} AND c.customer_id = #{customerId} FOR UPDATE OF c")
    ConversationRow lockCustomerConversation(@Param("customerId") UUID customerId,
                                             @Param("conversationId") UUID conversationId);

    /** 客户的会话列表只列发过消息的。 */
    @Select("""
            SELECT count(*) FROM support_conversation c
            WHERE c.customer_id = #{customerId}
              AND c.message_count > 0
            """)
    long countCustomerConversations(@Param("customerId") UUID customerId);

    @Select("""
            SELECT COALESCE(sum(c.customer_unread), 0) FROM support_conversation c
            WHERE c.customer_id = #{customerId}
            """)
    long sumCustomerUnread(@Param("customerId") UUID customerId);

    @Select(CONVERSATION_COLUMNS + """
            WHERE c.customer_id = #{customerId}
              AND c.message_count > 0
            ORDER BY c.last_message_at DESC, c.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ConversationRow> findCustomerPage(
            @Param("customerId") UUID customerId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 回复方能看的会话：平台客服看平台客服的；机构操作员看对方是本机构的商家、服务商会话。 */
    String DESK = """
            ((#{platform} AND c.counterpart_kind = 'PLATFORM')
              OR (NOT #{platform} AND c.counterpart_kind IN ('MERCHANT', 'PROVIDER')
                  AND c.counterpart_org_id = CAST(#{organizationId} AS uuid)))
            """;

    @Select(CONVERSATION_COLUMNS + "WHERE c.id = #{conversationId} AND " + DESK)
    ConversationRow findDeskConversation(
            @Param("platform") boolean platform,
            @Param("organizationId") UUID organizationId,
            @Param("conversationId") UUID conversationId
    );

    @Select(CONVERSATION_COLUMNS + "WHERE c.id = #{conversationId} AND " + DESK + " FOR UPDATE OF c")
    ConversationRow lockDeskConversation(
            @Param("platform") boolean platform,
            @Param("organizationId") UUID organizationId,
            @Param("conversationId") UUID conversationId
    );

    @Select("SELECT count(*) FROM support_conversation c WHERE c.message_count > 0 AND " + DESK)
    long countDeskConversations(@Param("platform") boolean platform, @Param("organizationId") UUID organizationId);

    @Select("SELECT COALESCE(sum(c.counterpart_unread), 0) FROM support_conversation c WHERE " + DESK)
    long sumDeskUnread(@Param("platform") boolean platform, @Param("organizationId") UUID organizationId);

    @Select(CONVERSATION_COLUMNS + "WHERE c.message_count > 0 AND " + DESK + """
            ORDER BY c.last_message_at DESC, c.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<ConversationRow> findDeskPage(
            @Param("platform") boolean platform,
            @Param("organizationId") UUID organizationId,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /** 客户发了一条：序号接上，对方未读加一，客户这边算读过，刷新客户的显示名。 */
    @Update("""
            UPDATE support_conversation
            SET message_count = #{seq},
                last_message_at = #{at},
                counterpart_unread = counterpart_unread + 1,
                customer_unread = 0,
                customer_read_at = #{at},
                customer_name = #{customerName},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{conversationId}
              AND message_count = #{seq} - 1
            """)
    int recordCustomerMessage(
            @Param("conversationId") UUID conversationId,
            @Param("seq") int seq,
            @Param("customerName") String customerName,
            @Param("at") OffsetDateTime at
    );

    /** 对方回复了一条：序号接上，客户未读加一，对方这边算读过。 */
    @Update("""
            UPDATE support_conversation
            SET message_count = #{seq},
                last_message_at = #{at},
                customer_unread = customer_unread + 1,
                counterpart_unread = 0,
                counterpart_read_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{conversationId}
              AND message_count = #{seq} - 1
            """)
    int recordDeskMessage(
            @Param("conversationId") UUID conversationId,
            @Param("seq") int seq,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE support_conversation
            SET customer_unread = 0,
                customer_read_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{conversationId}
              AND customer_id = #{customerId}
            """)
    int markCustomerRead(@Param("customerId") UUID customerId, @Param("conversationId") UUID conversationId,
                         @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE support_conversation c
            SET counterpart_unread = 0,
                counterpart_read_at = #{at},
                version = c.version + 1,
                updated_at = #{at}
            WHERE c.id = #{conversationId}
              AND """ + DESK)
    int markDeskRead(
            @Param("platform") boolean platform,
            @Param("organizationId") UUID organizationId,
            @Param("conversationId") UUID conversationId,
            @Param("at") OffsetDateTime at
    );

    // ---- 消息 ----

    String MESSAGE_COLUMNS = """
            SELECT m.id,
                   m.conversation_id,
                   m.seq,
                   m.sender_side,
                   m.kind,
                   m.text_body,
                   m.card_type,
                   m.card_ref_id,
                   m.card_title,
                   m.card_subtitle,
                   m.created_at
            FROM support_message m
            """;

    /** 一页消息，新的在前（服务按时间先后翻过来）；before 是上一页最早那条的序号。 */
    @Select(MESSAGE_COLUMNS + """
            WHERE m.conversation_id = #{conversationId}
              AND (CAST(#{before} AS integer) IS NULL OR m.seq < CAST(#{before} AS integer))
            ORDER BY m.seq DESC
            LIMIT #{limit}
            """)
    List<MessageRow> findMessages(
            @Param("conversationId") UUID conversationId,
            @Param("before") Integer before,
            @Param("limit") int limit
    );

    @Select(MESSAGE_COLUMNS + "WHERE m.id = #{messageId}")
    MessageRow findMessage(@Param("messageId") UUID messageId);

    @Select("""
            SELECT id, request_hash
            FROM support_message
            WHERE sender_id = #{senderId}
              AND idempotency_key = #{idempotencyKey}
            """)
    KeyRow findMessageByKey(@Param("senderId") UUID senderId, @Param("idempotencyKey") UUID idempotencyKey);

    @Select("""
            INSERT INTO support_message (
                conversation_id, seq, sender_side, sender_id, kind, text_body, card_type, card_ref_id, card_title,
                card_subtitle, idempotency_key, request_hash, created_at
            )
            VALUES (
                #{conversationId}, #{seq}, #{senderSide}, #{senderId}, #{kind}, #{text}, #{cardType}, #{cardRefId},
                #{cardTitle}, #{cardSubtitle}, #{idempotencyKey}, #{requestHash}, #{at}
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertMessage(
            @Param("conversationId") UUID conversationId,
            @Param("seq") int seq,
            @Param("senderSide") String senderSide,
            @Param("senderId") UUID senderId,
            @Param("kind") String kind,
            @Param("text") String text,
            @Param("cardType") String cardType,
            @Param("cardRefId") UUID cardRefId,
            @Param("cardTitle") String cardTitle,
            @Param("cardSubtitle") String cardSubtitle,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") OffsetDateTime at
    );

    // ---- 反馈 ----

    @Select("""
            SELECT id, account_id, feedback_type, body, request_hash, created_at
            FROM support_feedback
            WHERE account_id = #{accountId}
              AND idempotency_key = #{idempotencyKey}
            """)
    FeedbackRow findFeedbackByKey(@Param("accountId") UUID accountId, @Param("idempotencyKey") UUID idempotencyKey);

    @Select("""
            INSERT INTO support_feedback (account_id, feedback_type, body, idempotency_key, request_hash, created_at)
            VALUES (#{accountId}, #{feedbackType}, #{body}, #{idempotencyKey}, #{requestHash}, #{at})
            RETURNING id, account_id, feedback_type, body, request_hash, created_at
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    FeedbackRow insertFeedback(
            @Param("accountId") UUID accountId,
            @Param("feedbackType") String feedbackType,
            @Param("body") String body,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") OffsetDateTime at
    );

    // ---- 行 ----

    record ConversationRow(
            UUID id,
            UUID customerId,
            String customerName,
            String counterpartKind,
            UUID counterpartOrgId,
            String counterpartName,
            int messageCount,
            int customerUnread,
            int counterpartUnread,
            OffsetDateTime lastMessageAt,
            OffsetDateTime createdAt,
            int version,
            String lastSenderSide,
            String lastKind,
            String lastText,
            String lastCardTitle
    ) {
    }

    record MessageRow(
            UUID id,
            UUID conversationId,
            int seq,
            String senderSide,
            String kind,
            String textBody,
            String cardType,
            UUID cardRefId,
            String cardTitle,
            String cardSubtitle,
            OffsetDateTime createdAt
    ) {
    }

    record KeyRow(UUID id, String requestHash) {
    }

    record FeedbackRow(
            UUID id,
            UUID accountId,
            String feedbackType,
            String body,
            String requestHash,
            OffsetDateTime createdAt
    ) {
    }
}
