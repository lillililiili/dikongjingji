package com.lowaltitude.inspection.platform.weather;

enum WeatherConditionCode {
    CLEAR,
    PARTLY_CLOUDY,
    OVERCAST,
    RAIN,
    THUNDERSTORM,
    SNOW,
    FOG,
    UNKNOWN;

    static WeatherConditionCode fromDescription(String description) {
        if (description == null || description.isBlank()) {
            return UNKNOWN;
        }
        String normalized = description.trim();
        if (normalized.contains("雷")) {
            return THUNDERSTORM;
        }
        if (normalized.contains("雪") || normalized.contains("冻雨")) {
            return SNOW;
        }
        if (normalized.contains("雨")) {
            return RAIN;
        }
        if (normalized.contains("雾")
                || normalized.contains("霾")
                || normalized.contains("沙")
                || normalized.contains("尘")) {
            return FOG;
        }
        if (normalized.contains("阴")) {
            return OVERCAST;
        }
        if (normalized.contains("云")) {
            return PARTLY_CLOUDY;
        }
        if (normalized.contains("晴")) {
            return CLEAR;
        }
        return UNKNOWN;
    }
}
