package com.lowaltitude.inspection.experience.application;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 体验记录读库得到的行（S5-4）。字段顺序和 ExperienceRecordMapper 里的列顺序一致。 */
public final class ExperienceRecordRows {

    private ExperienceRecordRows() {
    }

    public record RecordableItemRow(
            UUID experienceId,
            String type,
            String title,
            String status,
            String simulationTemplate,
            Integer trialMinutes
    ) {
    }

    public record RecordRow(
            UUID recordId,
            UUID experienceId,
            String type,
            String title,
            String coverUrl,
            UUID productId,
            String simulationTemplate,
            String kind,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            OffsetDateTime expiresAt,
            String outcome,
            Integer score,
            Integer durationSeconds,
            Integer levelsCompleted,
            Integer levelsTotal,
            Integer mistakes,
            String requestHash
    ) {
    }
}
