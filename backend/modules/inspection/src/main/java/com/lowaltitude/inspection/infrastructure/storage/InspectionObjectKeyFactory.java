package com.lowaltitude.inspection.infrastructure.storage;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class InspectionObjectKeyFactory {

    private static final String UUID_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern ALLOWED_KEY = Pattern.compile(
            "tenant/" + UUID_PATTERN
                    + "/(?:reservation/" + UUID_PATTERN + "/" + UUID_PATTERN
                    + "|order/" + UUID_PATTERN + "/(?:delivery/" + UUID_PATTERN + "/" + UUID_PATTERN
                    + "|bundle/" + UUID_PATTERN + "\\.zip))");

    public String reservationAttachment(UUID providerId, UUID reservationId, UUID fileId) {
        return "tenant/" + required(providerId) + "/reservation/" + required(reservationId) + "/" + required(fileId);
    }

    public String deliveryArtifact(
            UUID providerId,
            UUID serviceOrderId,
            UUID deliveryId,
            UUID fileId
    ) {
        return "tenant/" + required(providerId) + "/order/" + required(serviceOrderId)
                + "/delivery/" + required(deliveryId) + "/" + required(fileId);
    }

    public String deliveryBundle(UUID providerId, UUID serviceOrderId, UUID deliveryId) {
        return "tenant/" + required(providerId) + "/order/" + required(serviceOrderId)
                + "/bundle/" + required(deliveryId) + ".zip";
    }

    public boolean isAllowed(String objectKey) {
        return objectKey != null && ALLOWED_KEY.matcher(objectKey).matches();
    }

    private static UUID required(UUID id) {
        return Objects.requireNonNull(id, "stable id must not be null");
    }
}
