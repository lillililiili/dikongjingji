package com.lowaltitude.inspection.application.serviceflow;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.domain.files.DeliveryFileType;
import com.lowaltitude.inspection.domain.files.FileObjectStatus;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class InspectionDeliveryControllerTest {

    private static final UUID ORDER =
            UUID.fromString("60000000-0000-4000-8000-000000000001");
    private static final UUID DELIVERY =
            UUID.fromString("70000000-0000-4000-8000-000000000001");
    private static final UUID FILE =
            UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID CUSTOMER =
            UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID PROVIDER =
            UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-07-28T04:00:00Z");

    private DeliveryApplicationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = org.mockito.Mockito.mock(DeliveryApplicationService.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new InspectionDeliveryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void publishesAndReadsContractDtoWithEtag() throws Exception {
        when(service.publish(
                any(),
                eq(ORDER),
                any(),
                eq(4),
                any(),
                anyString())).thenReturn(delivery());
        when(service.read(any(), eq(ORDER))).thenReturn(delivery());

        mvc.perform(post(
                        "/api/v1/provider/inspection/service-orders/{orderId}/delivery",
                        ORDER)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"4\"")
                        .contentType("application/json")
                        .content(publishJson())
                        .principal(provider()))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.towerCount").value(24))
                .andExpect(jsonPath("$.issueSummary[0].count").value(2))
                .andExpect(jsonPath("$.files[0].type").value("IMAGE_ARCHIVE"))
                .andExpect(jsonPath("$.files[0].pointCount").doesNotExist());

        mvc.perform(get(
                        "/api/v1/inspection/service-orders/{orderId}/delivery",
                        ORDER)
                        .principal(customer()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.serviceOrderId").value(ORDER.toString()));
    }

    @Test
    void createsControlledDownloadsAndAcceptanceDto() throws Exception {
        DownloadIntentView intent = new DownloadIntentView(
                "GET",
                URI.create("http://127.0.0.1:19000/private/object?signature=short"),
                "巡检报告.pdf",
                NOW.plusSeconds(300));
        when(service.download(any(), eq(ORDER), eq(FILE), any()))
                .thenReturn(intent);
        when(service.downloadAll(any(), eq(ORDER), any())).thenReturn(intent);
        when(service.accept(
                any(),
                eq(ORDER),
                any(),
                eq(5),
                any(),
                anyString())).thenReturn(new AcceptanceView(
                        UUID.randomUUID(),
                        ORDER,
                        DELIVERY,
                        CUSTOMER,
                        NOW,
                        ServiceOrderStatus.ACCEPTED,
                        6));

        mvc.perform(post(
                        "/api/v1/inspection/service-orders/{orderId}"
                                + "/delivery-files/{fileId}/download-intent",
                        ORDER,
                        FILE)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .principal(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.method").value("GET"))
                .andExpect(jsonPath("$.suggestedFileName").value("巡检报告.pdf"));

        mvc.perform(post(
                        "/api/v1/inspection/service-orders/{orderId}"
                                + "/delivery-files/download-all-intent",
                        ORDER)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .principal(customer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.downloadUrl").isString());

        mvc.perform(post(
                        "/api/v1/inspection/service-orders/{orderId}/acceptance",
                        ORDER)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"5\"")
                        .contentType("application/json")
                        .content("""
                                {"confirmed":true,"note":"交付成果已核验"}
                                """)
                        .principal(customer()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"6\""))
                .andExpect(jsonPath("$.orderStatus").value("ACCEPTED"));
    }

    @Test
    void mapsInvalidDeliveryFilesToStableError() throws Exception {
        when(service.publish(
                any(),
                eq(ORDER),
                any(),
                anyInt(),
                any(),
                anyString())).thenThrow(new DeliveryFilesInvalidException());

        mvc.perform(post(
                        "/api/v1/provider/inspection/service-orders/{orderId}/delivery",
                        ORDER)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .header("If-Match", "\"4\"")
                        .contentType("application/json")
                        .content(publishJson())
                        .principal(provider()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INS_DELIVERY_FILES_INVALID"));
    }

    private static DeliveryView delivery() {
        return new DeliveryView(
                DELIVERY,
                ORDER,
                DeliveryStatus.PUBLISHED,
                24,
                18_600,
                3,
                List.of(new IssueSummary("GENERAL", "一般问题", 2)),
                List.of(new DeliveryFile(
                        FILE,
                        DeliveryFileType.IMAGE_ARCHIVE,
                        "现场影像.zip",
                        null,
                        "application/zip",
                        1024,
                        FileObjectStatus.READY)),
                NOW,
                null,
                0);
    }

    private static String publishJson() {
        return """
                {
                  "towerCount":24,
                  "inspectedLengthMeters":18600,
                  "issueCount":3,
                  "issueSummary":[
                    {"severityCode":"GENERAL","severityName":"一般问题","count":2},
                    {"severityCode":"WATCH","severityName":"重点关注","count":1}
                  ],
                  "files":[
                    {"fileId":"80000000-0000-4000-8000-000000000001",
                     "type":"IMAGE_ARCHIVE","displayName":"现场影像.zip"},
                    {"fileId":"80000000-0000-4000-8000-000000000002",
                     "type":"DEFECT_LIST","displayName":"缺陷清单.xlsx"},
                    {"fileId":"80000000-0000-4000-8000-000000000003",
                     "type":"POINT_KML","displayName":"巡检点位.kml","pointCount":24},
                    {"fileId":"80000000-0000-4000-8000-000000000004",
                     "type":"INSPECTION_REPORT","displayName":"巡检报告.pdf"}
                  ]
                }
                """;
    }

    private static UsernamePasswordAuthenticationToken customer() {
        return principal(new CurrentSubject(
                CUSTOMER,
                Set.of("CUSTOMER"),
                CUSTOMER,
                Set.of(
                        "inspection.order.read.own",
                        "inspection.delivery.download.own",
                        "inspection.acceptance.create.own"),
                0));
    }

    private static UsernamePasswordAuthenticationToken provider() {
        return principal(new CurrentSubject(
                UUID.randomUUID(),
                Set.of("PROVIDER_OPERATOR"),
                PROVIDER,
                Set.of("inspection.delivery.publish.assigned"),
                0));
    }

    private static UsernamePasswordAuthenticationToken principal(
            CurrentSubject subject
    ) {
        return new UsernamePasswordAuthenticationToken(
                subject,
                "n/a",
                List.of());
    }
}
