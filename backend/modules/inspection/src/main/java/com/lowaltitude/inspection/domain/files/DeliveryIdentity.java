package com.lowaltitude.inspection.domain.files;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Stable delivery aggregate identity used before and after artifact upload. */
public final class DeliveryIdentity {

    private DeliveryIdentity() {
    }

    /** Backfilled first visits reuse the service order id, so old deliveries keep their id. */
    public static UUID forVisit(UUID visitId) {
        UUID required = Objects.requireNonNull(visitId, "visitId must not be null");
        return UUID.nameUUIDFromBytes(
                ("inspection-delivery:" + required).getBytes(StandardCharsets.UTF_8));
    }
}
