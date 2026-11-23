package com.lowaltitude.inspection.activity.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.activity.infrastructure.persistence.ActivityCatalogMapper;

final class PostgresActivityCatalogReadStore implements ActivityCatalogReadStore {

    private final ActivityCatalogMapper mapper;

    PostgresActivityCatalogReadStore(ActivityCatalogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long countListed(ActivityListQuery query) {
        return mapper.countListed(
                query.cityCode(),
                query.type(),
                query.freeOnly(),
                query.enrollingOnly(),
                query.filterByDate(),
                query.dateStart(),
                query.dateEnd(),
                query.now());
    }

    @Override
    public List<StoredActivityCard> findListedPage(ActivityListQuery query, int limit, int offset) {
        return mapper.findListedPage(
                        query.cityCode(),
                        query.type(),
                        query.freeOnly(),
                        query.enrollingOnly(),
                        query.filterByDate(),
                        query.dateStart(),
                        query.dateEnd(),
                        query.now(),
                        limit,
                        offset)
                .stream()
                .map(row -> new StoredActivityCard(
                        row.activityId(),
                        row.type(),
                        row.title(),
                        row.coverUrl(),
                        row.venueName(),
                        row.scheduleNote(),
                        row.priceMode(),
                        row.priceAmountMinor(),
                        row.currency(),
                        row.minAge(),
                        row.maxAge(),
                        row.waitlistAllowed(),
                        row.nextSessionId(),
                        row.nextStartsAt(),
                        row.nextCapacity(),
                        row.nextEnrolledCount(),
                        row.hasOpenSession(),
                        row.hasFullSession(),
                        row.organizerId()))
                .toList();
    }

    @Override
    public List<LocalDate> findSessionDates(
            String cityCode,
            String type,
            String zoneId,
            OffsetDateTime rangeStart,
            OffsetDateTime rangeEnd,
            OffsetDateTime now
    ) {
        return mapper.findSessionDates(cityCode, type, zoneId, rangeStart, rangeEnd, now);
    }

    @Override
    public Optional<StoredActivityDetail> findPublishedDetail(UUID activityId, String cityCode) {
        return mapper.findPublishedDetail(activityId, cityCode)
                .map(row -> new StoredActivityDetail(
                        row.activityId(),
                        row.type(),
                        row.title(),
                        row.subtitle(),
                        row.coverUrl(),
                        row.priceMode(),
                        row.priceAmountMinor(),
                        row.currency(),
                        row.minAge(),
                        row.maxAge(),
                        row.organizerId(),
                        row.organizerName(),
                        row.coOrganizerId(),
                        row.coOrganizerName(),
                        row.venueName(),
                        row.venueAddress(),
                        row.latitude(),
                        row.longitude(),
                        row.scheduleNote(),
                        row.eligibilityNote(),
                        row.seatNote(),
                        row.refundNote(),
                        row.waitlistAllowed(),
                        row.teamEnrollmentAllowed(),
                        row.enrollmentNotice()));
    }

    @Override
    public List<StoredActivitySession> findActiveSessions(UUID activityId) {
        return mapper.findActiveSessions(activityId).stream()
                .map(row -> new StoredActivitySession(
                        row.sessionId(),
                        row.label(),
                        row.startsAt(),
                        row.endsAt(),
                        row.checkInOpensAt(),
                        row.enrollmentClosesAt(),
                        row.capacity(),
                        row.enrolledCount(),
                        row.saleStopped()))
                .toList();
    }

    @Override
    public List<String> findGallery(UUID activityId) {
        return mapper.findGallery(activityId);
    }
}
