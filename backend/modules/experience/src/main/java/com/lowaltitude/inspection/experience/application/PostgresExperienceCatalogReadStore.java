package com.lowaltitude.inspection.experience.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.ExperienceRows.ExperienceItemRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueCardRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueDeviceRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueInfoRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueProjectRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueRow;
import com.lowaltitude.inspection.experience.application.ExperienceRows.VenueSessionRow;
import com.lowaltitude.inspection.experience.infrastructure.persistence.ExperienceCatalogMapper;

final class PostgresExperienceCatalogReadStore implements ExperienceCatalogReadStore {

    private final ExperienceCatalogMapper mapper;

    PostgresExperienceCatalogReadStore(ExperienceCatalogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long countExperienceItems(String type) {
        return mapper.countExperienceItems(type);
    }

    @Override
    public List<ExperienceItemRow> findExperienceItems(String type, int limit, int offset) {
        return mapper.findExperienceItems(type, limit, offset);
    }

    @Override
    public long countVenues(String cityCode) {
        return mapper.countVenues(cityCode);
    }

    @Override
    public List<VenueCardRow> findVenues(
            String cityCode, OffsetDateTime now, OffsetDateTime endOfToday, int limit, int offset
    ) {
        return mapper.findVenues(cityCode, now, endOfToday, limit, offset);
    }

    @Override
    public Optional<VenueRow> findVenue(UUID venueId, String cityCode) {
        return mapper.findVenue(venueId, cityCode);
    }

    @Override
    public List<VenueInfoRow> findInfoItems(UUID venueId) {
        return mapper.findInfoItems(venueId);
    }

    @Override
    public List<VenueProjectRow> findProjects(UUID venueId) {
        return mapper.findProjects(venueId);
    }

    @Override
    public boolean projectBelongsToVenue(UUID venueId, UUID projectId) {
        return mapper.countActiveProject(venueId, projectId) > 0;
    }

    @Override
    public List<VenueSessionRow> findSessions(
            UUID projectId, OffsetDateTime dayStart, OffsetDateTime dayEnd
    ) {
        return mapper.findSessions(projectId, dayStart, dayEnd);
    }

    @Override
    public long countDevices(UUID venueId, String category) {
        return mapper.countDevices(venueId, category);
    }

    @Override
    public List<VenueDeviceRow> findDevices(UUID venueId, String category, int limit, int offset) {
        return mapper.findDevices(venueId, category, limit, offset);
    }
}
