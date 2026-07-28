package com.lowaltitude.inspection.domain.files;

import java.util.Objects;
import java.util.UUID;

public final class DeliveryObjectKeys {

    private DeliveryObjectKeys() {
    }

    public static String artifact(
            UUID providerId,
            UUID serviceOrderId,
            UUID deliveryId,
            UUID fileId
    ) {
        return prefix(providerId, serviceOrderId, deliveryId)
                + Objects.requireNonNull(fileId, "fileId must not be null");
    }

    public static String bundle(
            UUID providerId,
            UUID serviceOrderId,
            UUID deliveryId
    ) {
        return "tenant/" + required(providerId) + "/order/" + required(serviceOrderId)
                + "/bundle/" + required(deliveryId) + ".zip";
    }

    private static String prefix(
            UUID providerId,
            UUID serviceOrderId,
            UUID deliveryId
    ) {
        return "tenant/" + required(providerId) + "/order/" + required(serviceOrderId)
                + "/delivery/" + required(deliveryId) + "/";
    }

    private static UUID required(UUID value) {
        return Objects.requireNonNull(value, "stable id must not be null");
    }
}
