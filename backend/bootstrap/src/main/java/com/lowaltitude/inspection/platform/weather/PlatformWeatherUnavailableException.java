package com.lowaltitude.inspection.platform.weather;

final class PlatformWeatherUnavailableException extends RuntimeException {
    PlatformWeatherUnavailableException(String message) {
        super(message);
    }

    PlatformWeatherUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
