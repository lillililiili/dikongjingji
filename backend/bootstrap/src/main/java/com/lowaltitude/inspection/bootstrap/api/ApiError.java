package com.lowaltitude.inspection.bootstrap.api;

import java.util.Map;

public record ApiError(String code, String message, String traceId, Map<String, Object> details) {

    public ApiError(String code, String message, String traceId) {
        this(code, message, traceId, null);
    }
}
