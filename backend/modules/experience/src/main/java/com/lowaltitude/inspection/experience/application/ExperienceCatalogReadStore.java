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

interface ExperienceCatalogReadStore {

    long countExperienceItems(String type);

    List<ExperienceItemRow> findExperienceItems(String type, int limit, int offset);

    long countVenues(String cityCode);

    List<VenueCardRow> findVenues(
            String cityCode, OffsetDateTime now, OffsetDateTime endOfToday, int limit, int offset);

    Optional<VenueRow> findVenue(UUID venueId, String cityCode);

    List<VenueInfoRow> findInfoItems(UUID venueId);

    List<VenueProjectRow> findProjects(UUID venueId);

    boolean projectBelongsToVenue(UUID venueId, UUID projectId);

    List<VenueSessionRow> findSessions(UUID projectId, OffsetDateTime dayStart, OffsetDateTime dayEnd);

    long countDevices(UUID venueId, String category);

    List<VenueDeviceRow> findDevices(UUID venueId, String category, int limit, int offset);
}
