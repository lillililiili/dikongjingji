package com.lowaltitude.inspection.experience.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 读库得到的行。由持久层填充，读服务把它们转成对外的视图。 */
public final class ExperienceRows {

    private ExperienceRows() {
    }

    public record ExperienceItemRow(
            UUID experienceId,
            String type,
            String title,
            String summary,
            String coverUrl,
            boolean loginRequired
    ) {
    }

    /** 一个已发布的线上体验项目（S5-4 仿真页、试用页读它的模板和试用时长）。 */
    public record ExperienceItemDetailRow(
            UUID experienceId,
            String type,
            String title,
            String summary,
            String coverUrl,
            boolean loginRequired,
            UUID productId,
            String simulationTemplate,
            Integer trialMinutes
    ) {
    }

    public record VenueCardRow(
            UUID venueId,
            String name,
            String coverUrl,
            String openingNote,
            String address,
            long openSessionsToday
    ) {
    }

    public record VenueRow(
            UUID venueId,
            String name,
            String coverUrl,
            String openingNote,
            String address,
            Double latitude,
            Double longitude,
            String phone,
            UUID operatorId,
            String operatorName,
            long deviceCount
    ) {
    }

    public record VenueInfoRow(String label, String content) {
    }

    public record VenueProjectRow(
            UUID projectId,
            String name,
            int durationMinutes,
            Integer minAge,
            int capacity,
            String limitNote,
            long priceAmountMinor,
            String currency
    ) {
    }

    public record VenueSessionRow(
            UUID sessionId,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            int capacity,
            int bookedCount
    ) {
    }

    public record VenueDeviceRow(
            UUID deviceId,
            String category,
            String name,
            String summary,
            String imageUrl,
            String trialMode
    ) {
    }

    /** 关联了某件商品的线上体验项目（R6-2）。 */
    public record ProductExperienceRow(
            UUID experienceId,
            String type,
            String title,
            String summary
    ) {
    }

    /** 本城有某件商品（馆内设备关联了它）的场馆，一个场馆一行，带第一件这样的设备的名称（R6-2）。 */
    public record ProductVenueRow(
            UUID venueId,
            String venueName,
            String deviceName
    ) {
    }
}
