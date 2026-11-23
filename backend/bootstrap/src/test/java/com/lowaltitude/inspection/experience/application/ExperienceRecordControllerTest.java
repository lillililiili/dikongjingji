package com.lowaltitude.inspection.experience.application;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.experience.application.ExperienceRecordException.Error;
import com.lowaltitude.inspection.experience.application.ExperienceRecordService.RecordResult;
import com.lowaltitude.inspection.experience.application.ExperienceRecordService.SimulationResultCommand;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 体验记录控制器（S5-4）：从令牌里取账号和角色，把请求转成服务的参数，把结果和业务拒绝转成契约里的状态码。
 * 没带令牌时的 401 由安全过滤器给出（AuthenticationSecurityIntegrationTest）。
 */
class ExperienceRecordControllerTest {

    private static final UUID SUBJECT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROBOT = UUID.fromString("b1000000-0000-4000-8000-000000000001");
    private static final UUID SOFTWARE = UUID.fromString("b1000000-0000-4000-8000-000000000004");
    private static final UUID RECORD = UUID.fromString("b7000000-0000-4000-8000-000000000001");
    private static final UUID KEY = UUID.fromString("e0000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime STARTED = OffsetDateTime.parse("2026-11-22T01:56:00Z");
    private static final OffsetDateTime ENDED = OffsetDateTime.parse("2026-11-22T01:59:00Z");

    private final ExperienceRecordService service = mock(ExperienceRecordService.class);
    private final TestingAuthenticationToken customer = signedIn("CUSTOMER");
    private final TestingAuthenticationToken operator = signedIn("PROVIDER_OPERATOR");
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ExperienceRecordController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }

    @Test
    void aNewResultIs201AndAReplayIs200() throws Exception {
        SimulationResultCommand command = new SimulationResultCommand(ROBOT, STARTED, ENDED, 156, 3, 3, 1);
        when(service.recordSimulation(SUBJECT, KEY, command))
                .thenReturn(new RecordResult(simulation(), true))
                .thenReturn(new RecordResult(simulation(), false));

        for (int expected : new int[] {201, 200}) {
            mockMvc.perform(post("/api/v1/experience/records")
                            .principal(customer)
                            .header("Idempotency-Key", KEY.toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"experienceId":"%s","startedAt":"2026-11-22T09:56:00+08:00",
                                     "endedAt":"2026-11-22T09:59:00+08:00","durationSeconds":156,
                                     "levelsCompleted":3,"levelsTotal":3,"mistakes":1}
                                    """.formatted(ROBOT)))
                    .andExpect(status().is(expected))
                    .andExpect(jsonPath("$.recordId").value(RECORD.toString()))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.result.score").value(95))
                    .andExpect(jsonPath("$.expiresAt").value(nullValue()))
                    .andExpect(jsonPath("$.experience.simulationTemplate").value("ROBOT_OBSTACLE"));
        }
    }

    @Test
    void aTrialStartIs201ThenTheActiveOneIs200AndEndingReturnsTheRecord() throws Exception {
        when(service.startTrial(SUBJECT, SOFTWARE))
                .thenReturn(new RecordResult(trial(ExperienceRecordStatus.ACTIVE), true))
                .thenReturn(new RecordResult(trial(ExperienceRecordStatus.ACTIVE), false));
        when(service.endTrial(SUBJECT, RECORD)).thenReturn(trial(ExperienceRecordStatus.ENDED));

        for (int expected : new int[] {201, 200}) {
            mockMvc.perform(post("/api/v1/experience/trials")
                            .principal(customer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"experienceId\":\"" + SOFTWARE + "\"}"))
                    .andExpect(status().is(expected))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.result").value(nullValue()));
        }
        mockMvc.perform(post("/api/v1/experience/records/" + RECORD + "/end").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ENDED"));
    }

    @Test
    void myRecordsUseTheContractDefaults() throws Exception {
        when(service.listRecords(SUBJECT, null, 1, 20))
                .thenReturn(new ExperienceRecordPageView(1, 20, 1, false, List.of(simulation())));
        when(service.listRecords(SUBJECT, SOFTWARE, 1, 1))
                .thenReturn(new ExperienceRecordPageView(1, 1, 0, false, List.of()));

        mockMvc.perform(get("/api/v1/experience/records").principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recordId").value(RECORD.toString()));
        mockMvc.perform(get("/api/v1/experience/records")
                        .param("experienceId", SOFTWARE.toString())
                        .param("size", "1")
                        .principal(customer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void rejectionsUseTheContractCodes() throws Exception {
        when(service.readRecord(eq(SUBJECT), any())).thenThrow(new ExperienceRecordException(Error.NOT_FOUND));
        when(service.endTrial(SUBJECT, RECORD)).thenThrow(new ExperienceRecordException(Error.NOT_TRIAL));
        when(service.startTrial(SUBJECT, ROBOT)).thenThrow(new ExperienceRecordException(Error.NOT_RECORDABLE));
        when(service.recordSimulation(eq(SUBJECT), eq(KEY), any()))
                .thenThrow(new ExperienceRecordException(Error.RESULT_INVALID));

        mockMvc.perform(get("/api/v1/experience/records/" + RECORD).principal(customer))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
        mockMvc.perform(post("/api/v1/experience/records/" + RECORD + "/end").principal(customer))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPERIENCE_NOT_TRIAL"));
        mockMvc.perform(post("/api/v1/experience/trials")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"experienceId\":\"" + ROBOT + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXPERIENCE_NOT_RECORDABLE"));
        mockMvc.perform(post("/api/v1/experience/records")
                        .principal(customer)
                        .header("Idempotency-Key", KEY.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"experienceId\":\"" + ROBOT + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EXPERIENCE_RESULT_INVALID"));
    }

    @Test
    void anAccountThatIsNotACustomerIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/experience/records").principal(operator))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mockMvc.perform(post("/api/v1/experience/trials")
                        .principal(operator)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"experienceId\":\"" + SOFTWARE + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/experience/records"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void aMissingIdempotencyKeyIsABadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/experience/records")
                        .principal(customer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    private static ExperienceRecordView simulation() {
        return new ExperienceRecordView(RECORD,
                new ExperienceRecordItemView(ROBOT, ExperienceType.SIMULATION, "机器人避障模拟", null, null,
                        SimulationTemplate.ROBOT_OBSTACLE),
                ExperienceRecordStatus.COMPLETED, STARTED, ENDED, null,
                new SimulationResultView(95, 156, 3, 3, 1));
    }

    private static ExperienceRecordView trial(ExperienceRecordStatus status) {
        return new ExperienceRecordView(RECORD,
                new ExperienceRecordItemView(SOFTWARE, ExperienceType.SOFTWARE_TRIAL, "图形化编程软件试用", null, null, null),
                status, STARTED, status == ExperienceRecordStatus.ENDED ? ENDED : null, STARTED.plusMinutes(25), null);
    }

    private static TestingAuthenticationToken signedIn(String role) {
        return new TestingAuthenticationToken(
                new CurrentSubject(SUBJECT, Set.of(role), null, Set.of(), 0), null);
    }
}
