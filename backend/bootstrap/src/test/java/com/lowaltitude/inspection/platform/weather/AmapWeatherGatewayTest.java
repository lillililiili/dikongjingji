package com.lowaltitude.inspection.platform.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AmapWeatherGatewayTest {

    private MockRestServiceServer server;
    private AmapWeatherGateway gateway;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        gateway = new AmapWeatherGateway(
                builder.baseUrl("https://restapi.amap.com").build(),
                "server-side-key");
    }

    @Test
    void queriesAmapByAdcodeAndMapsTheLiveWeather() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://restapi.amap.com/v3/weather/weatherInfo")))
                .andExpect(queryParam("city", "370100"))
                .andExpect(queryParam("extensions", "base"))
                .andExpect(queryParam("output", "JSON"))
                .andExpect(queryParam("key", "server-side-key"))
                .andRespond(withSuccess("""
                        {
                          "status":"1",
                          "count":"1",
                          "info":"OK",
                          "infocode":"10000",
                          "lives":[{
                            "province":"山东",
                            "city":"济南市",
                            "adcode":"370100",
                            "weather":"多云",
                            "temperature":"26",
                            "reporttime":"2026-07-29 10:30:00"
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        CurrentWeather weather = gateway.load("370100");

        assertThat(weather.cityCode()).isEqualTo("370100");
        assertThat(weather.cityName()).isEqualTo("济南市");
        assertThat(weather.conditionCode()).isEqualTo(WeatherConditionCode.PARTLY_CLOUDY);
        assertThat(weather.conditionText()).isEqualTo("多云");
        assertThat(weather.temperatureCelsius()).isEqualByComparingTo("26");
        assertThat(weather.observedAt()).isEqualTo(Instant.parse("2026-07-29T02:30:00Z"));
        server.verify();
    }

    @Test
    void failsClosedWhenAmapReturnsNoSuccessfulLiveWeather() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        "https://restapi.amap.com/v3/weather/weatherInfo")))
                .andRespond(withSuccess("""
                        {"status":"0","count":"0","info":"INVALID_USER_KEY","infocode":"10001","lives":[]}
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.load("370100"))
                .isInstanceOf(PlatformWeatherUnavailableException.class);
    }
}
