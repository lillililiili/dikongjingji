package com.lowaltitude.inspection.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest
@AutoConfigureMockMvc
@Import(BootstrapContextIntegrationTest.ErrorControllerTestConfiguration.class)
class BootstrapContextIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void contextIncludesTheProductionBootstrapApplication() {
        assertThat(applicationContext.containsBean("bootstrapApplication")).isTrue();
    }

    @Test
    void healthEndpointReportsUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void invalidArgumentUsesTheApiErrorContract() throws Exception {
        mockMvc.perform(get("/test/invalid-request").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("请求参数无效"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void unexpectedExceptionsUseTheStableApiErrorContract() throws Exception {
        mockMvc.perform(get("/test/unexpected-request").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("COMMON_INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("系统繁忙，请稍后重试"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(TestErrorController.class)
    static class ErrorControllerTestConfiguration {
    }

    @RestController
    static class TestErrorController {

        @GetMapping("/test/invalid-request")
        String invalidRequest() {
            throw new IllegalArgumentException("invalid test request");
        }

        @GetMapping("/test/unexpected-request")
        String unexpectedRequest() {
            throw new IllegalStateException("internal implementation detail");
        }
    }
}
