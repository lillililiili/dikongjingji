package com.lowaltitude.inspection.activity.application;

import java.time.YearMonth;

import com.lowaltitude.inspection.experience.application.VenueParticipantPort;
import com.lowaltitude.inspection.experience.application.VenueParticipantPort.VenueParticipant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 到馆预约的参与人就是活动报名的“本人和子女”（E10，§21.2）：体验模块的参与人端口接到活动模块的参与人名单。
 * 放在活动的包里，才能读到参与人视图的内部结构；两个业务模块互不依赖。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class VenueParticipantsConfiguration {

    @Bean
    VenueParticipantPort venueParticipantPort(ActivityEnrollmentService enrollments) {
        return (accountId, accountName) -> enrollments.listParticipants(accountId, accountName, null).items().stream()
                .map(participant -> new VenueParticipant(
                        participant.participantId(),
                        participant.relation() == ParticipantRelation.SELF,
                        participant.name(),
                        participant.birthMonth() == null ? null : YearMonth.parse(participant.birthMonth()).atDay(1)))
                .toList();
    }
}
