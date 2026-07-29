package com.lowaltitude.inspection.platform.weather;

@FunctionalInterface
interface CurrentWeatherGateway {
    CurrentWeather load(String cityCode);
}
