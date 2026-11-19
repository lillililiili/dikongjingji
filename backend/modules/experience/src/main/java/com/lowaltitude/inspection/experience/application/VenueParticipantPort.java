package com.lowaltitude.inspection.experience.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 到馆预约用的参与人名单（E10，docs/redesign/03-App实现任务计划.md §21.2）：和活动报名同一份“本人和子女”。
 * 体验模块只认这个端口，由启动模块接到活动模块；两个模块互不依赖。
 */
public interface VenueParticipantPort {

    /** 这个账号的参与人，“本人”在最前（第一次读取时按账号显示名补上“本人”）。 */
    List<VenueParticipant> participants(UUID accountId, String accountName);

    /**
     * @param self       是不是账号持有人本人（按成年人处理，不知道出生年月）
     * @param birthMonth 子女的出生年月（当月 1 日）；本人为 null
     */
    record VenueParticipant(UUID participantId, boolean self, String name, LocalDate birthMonth) {
    }
}
