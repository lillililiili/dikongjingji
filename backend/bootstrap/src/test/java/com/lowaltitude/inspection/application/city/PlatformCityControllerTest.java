package com.lowaltitude.inspection.application.city;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlatformCityControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        CityDirectoryReadService service = new CityDirectoryReadService(new CityDirectoryReadService.CityDirectoryStore() {
            @Override
            public List<CityDirectoryReadService.StoredCity> findActiveCities() {
                return List.of(new CityDirectoryReadService.StoredCity(
                        "370100", "济南", "jinan", "J", "370000", "山东", "shandong", "S", 1));
            }

            @Override
            public int incrementSearchCount(String cityCode) {
                return "370100".equals(cityCode) ? 1 : 0;
            }
        });
        mockMvc = MockMvcBuilders.standaloneSetup(new PlatformCityController(service)).build();
    }

    @Test
    void returnsTheNationwideDirectory() throws Exception {
        mockMvc.perform(get("/api/v1/platform/cities"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotCities[0].code").value("370100"))
                .andExpect(jsonPath("$.provinces[0].cities").isArray());
    }

    @Test
    void recordsAnActiveCitySearchSelection() throws Exception {
        mockMvc.perform(post("/api/v1/platform/cities/370100/search-selections"))
                .andExpect(status().isNoContent());
    }

    @Test
    void rejectsAnUnknownCitySearchSelection() throws Exception {
        mockMvc.perform(post("/api/v1/platform/cities/999999/search-selections")
                        .header("X-Request-Id", "city-not-found-test"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("资源不存在"))
                .andExpect(jsonPath("$.traceId").value("city-not-found-test"));
    }

    @Test
    void generatesATraceIdForAnUnknownCitySearchSelectionWithoutRequestId() throws Exception {
        mockMvc.perform(post("/api/v1/platform/cities/999998/search-selections"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }
}
