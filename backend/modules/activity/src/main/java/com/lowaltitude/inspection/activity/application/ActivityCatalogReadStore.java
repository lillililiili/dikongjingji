package com.lowaltitude.inspection.activity.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ActivityCatalogReadStore {

    long countListed(ActivityListQuery query);

    List<StoredActivityCard> findListedPage(ActivityListQuery query, int limit, int offset);

    List<LocalDate> findSessionDates(
            String cityCode,
            String type,
            String zoneId,
            OffsetDateTime rangeStart,
            OffsetDateTime rangeEnd,
            OffsetDateTime now);

    Optional<StoredActivityDetail> findPublishedDetail(UUID activityId, String cityCode);

    List<StoredActivitySession> findActiveSessions(UUID activityId);
}

/** dateStart、dateEnd 只在 filterByDate 为 true 时有意义；为 false 时仍传非空值，避免向 SQL 绑定空参数。 */
record ActivityListQuery(
        String cityCode,
        String type,
        boolean freeOnly,
        boolean enrollingOnly,
        boolean filterByDate,
        OffsetDateTime dateStart,
        OffsetDateTime dateEnd,
        OffsetDateTime now
) {
}

record StoredActivityCard(
        UUID activityId,
        String type,
        String title,
        String coverUrl,
        String venueName,
        String scheduleNote,
        String priceMode,
        Long priceAmountMinor,
        String currency,
        Integer minAge,
        Integer maxAge,
        boolean waitlistAllowed,
        UUID nextSessionId,
        OffsetDateTime nextStartsAt,
        int nextCapacity,
        int nextEnrolledCount,
        boolean hasOpenSession,
        boolean hasFullSession
) {
}

record StoredActivityDetail(
        UUID activityId,
        String type,
        String title,
        String subtitle,
        String coverUrl,
        String priceMode,
        Long priceAmountMinor,
        String currency,
        Integer minAge,
        Integer maxAge,
        UUID organizerId,
        String organizerName,
        UUID coOrganizerId,
        String coOrganizerName,
        String venueName,
        String venueAddress,
        Double latitude,
        Double longitude,
        String scheduleNote,
        String eligibilityNote,
        String seatNote,
        String refundNote,
        boolean waitlistAllowed,
        boolean teamEnrollmentAllowed
) {
}

record StoredActivitySession(
        UUID sessionId,
        String label,
        OffsetDateTime startsAt,
        OffsetDateTime endsAt,
        OffsetDateTime checkInOpensAt,
        OffsetDateTime enrollmentClosesAt,
        int capacity,
        int enrolledCount
) {
}
