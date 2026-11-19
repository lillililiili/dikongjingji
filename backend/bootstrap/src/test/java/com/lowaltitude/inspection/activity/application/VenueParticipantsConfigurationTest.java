package com.lowaltitude.inspection.activity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.VenueParticipantPort;
import com.lowaltitude.inspection.experience.application.VenueParticipantPort.VenueParticipant;
import org.junit.jupiter.api.Test;

/** 到馆预约的参与人就是活动报名的“本人和子女”（E10）：本人没有出生年月，子女的出生年月是当月 1 日。 */
class VenueParticipantsConfigurationTest {

    private static final UUID ACCOUNT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SELF = UUID.fromString("c0000000-0000-4000-8000-000000000001");
    private static final UUID CHILD = UUID.fromString("c0000000-0000-4000-8000-000000000002");

    @Test
    void theActivityParticipantListIsHandedOverWithBirthMonthsAsDates() {
        ActivityEnrollmentService enrollments = mock(ActivityEnrollmentService.class);
        when(enrollments.listParticipants(ACCOUNT, "王女士", null)).thenReturn(new ParticipantListView(List.of(
                new ParticipantView(SELF, ParticipantRelation.SELF, "王女士", null, null, false, null),
                new ParticipantView(CHILD, ParticipantRelation.CHILD, "小明", "2016-05", 10, true, null))));
        VenueParticipantPort port = new VenueParticipantsConfiguration().venueParticipantPort(enrollments);

        assertThat(port.participants(ACCOUNT, "王女士")).containsExactly(
                new VenueParticipant(SELF, true, "王女士", null),
                new VenueParticipant(CHILD, false, "小明", LocalDate.parse("2016-05-01")));
    }
}
