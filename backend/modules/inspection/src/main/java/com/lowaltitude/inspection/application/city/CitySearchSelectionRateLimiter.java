package com.lowaltitude.inspection.application.city;

import java.time.Duration;
import java.util.Objects;

public interface CitySearchSelectionRateLimiter {

    Decision check(String clientAddress);

    enum Status {
        ALLOWED,
        RATE_LIMITED,
        UNAVAILABLE
    }

    record Decision(Status status, Duration retryAfter) {

        public Decision {
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(retryAfter, "retryAfter must not be null");
            if (retryAfter.isNegative()) {
                throw new IllegalArgumentException("retryAfter must not be negative");
            }
        }

        public static Decision allowed() {
            return new Decision(Status.ALLOWED, Duration.ZERO);
        }

        public static Decision rateLimited(Duration retryAfter) {
            return new Decision(Status.RATE_LIMITED, retryAfter);
        }

        public static Decision unavailable() {
            return new Decision(Status.UNAVAILABLE, Duration.ZERO);
        }
    }
}
