package com.lowaltitude.inspection.domain.files;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable delivery aggregate identity used before and after artifact upload. */
public final class DeliveryIdentity {

    private DeliveryIdentity() {
    }

    public static UUID forServiceOrder(UUID serviceOrderId) {
        UUID required = Objects.requireNonNull(serviceOrderId, "serviceOrderId must not be null");
        return UUID.nameUUIDFromBytes(
                ("inspection-delivery:" + required).getBytes(StandardCharsets.UTF_8));
    }
}
