package com.lowaltitude.inspection.opsimport.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsTestSupport;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端“批量导入”的控制器（S2-3）。 */
class OpsImportControllerTest {

    private static final UUID BATCH = UUID.fromString("e8000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-23T02:00:00Z");
    private static final OpsImportPorts.Actor ACTOR = new OpsImportPorts.Actor(OpsTestSupport.OPERATOR_ID,
            OpsTestSupport.OPERATOR_NAME);

    private final OpsImportService service = mock(OpsImportService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(new OpsImportController(service, OpsTestSupport.actors()));

    private static OpsImportViews.OpsImportBatchView batch(List<OpsImportViews.OpsImportCreatedAccountView> created) {
        return new OpsImportViews.OpsImportBatchView(BATCH, OpsImportKind.ORGANIZATIONS, OpsImportStatus.IMPORTED,
                "a".repeat(64), List.of(new OpsImportViews.OpsImportFileView("01-organizations.csv", 1)), 1, 0,
                List.of(), List.of(), List.of(), created, null, OpsTestSupport.OPERATOR_NAME, AT,
                OpsTestSupport.OPERATOR_NAME, AT, AT.plusDays(180));
    }

    @Test
    void checksTakeEveryUploadedFileUnderTheFilesPart() throws Exception {
        when(service.check(eq("ORGANIZATIONS"), any(), eq(ACTOR))).thenReturn(batch(List.of()));

        mockMvc.perform(multipart("/api/v1/ops/imports/{kind}/checks", "ORGANIZATIONS")
                        .file(new MockMultipartFile("files", "01-organizations.csv", "text/csv",
                                "a\n".getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("files", "01b-organization-operators.csv", "text/csv",
                                "b\n".getBytes(StandardCharsets.UTF_8)))
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IMPORTED"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OpsImportService.UploadedFile>> files = ArgumentCaptor.forClass(List.class);
        verify(service).check(eq("ORGANIZATIONS"), files.capture(), eq(ACTOR));
        assertThat(files.getValue()).extracting(OpsImportService.UploadedFile::fileName)
                .containsExactly("01-organizations.csv", "01b-organization-operators.csv");
    }

    @Test
    void runIsNotCachedBecauseItCarriesInitialPasswords() throws Exception {
        when(service.run(BATCH, ACTOR)).thenReturn(batch(List.of(
                new OpsImportViews.OpsImportCreatedAccountView("SHOP", "shop-ops", "Ab3defGh5jKm"))));

        mockMvc.perform(post("/api/v1/ops/imports/batches/{id}/run", BATCH).principal(OpsTestSupport.operator()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.createdAccounts[0].loginName").value("shop-ops"));
    }

    @Test
    void rejectionsUseTheContractCodesAndSupportIsKeptOut() throws Exception {
        when(service.run(BATCH, ACTOR)).thenThrow(new OpsImportException(OpsImportException.Error.NOT_WRITABLE));

        mockMvc.perform(post("/api/v1/ops/imports/batches/{id}/run", BATCH).principal(OpsTestSupport.operator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_IMPORT_NOT_WRITABLE"));
        OpsImportService other = mock(OpsImportService.class);
        OpsTestSupport.mockMvc(new OpsImportController(other, OpsTestSupport.actors()))
                .perform(get("/api/v1/ops/imports/kinds").principal(OpsTestSupport.support()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(other);
    }
}
