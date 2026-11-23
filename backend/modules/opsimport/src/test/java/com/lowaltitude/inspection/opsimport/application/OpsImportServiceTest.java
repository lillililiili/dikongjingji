package com.lowaltitude.inspection.opsimport.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.opsimport.infrastructure.persistence.OpsImportMapper;
import com.lowaltitude.inspection.opsimport.infrastructure.persistence.OpsImportMapper.BatchRow;
import org.junit.jupiter.api.Test;

/** 批次的状态规则：预检建批次、重复内容不再写、导入前状态检查。真实 SQL 由开发环境联调覆盖。 */
class OpsImportServiceTest {

    private static final OpsImportPorts.Actor ACTOR = new OpsImportPorts.Actor(UUID.randomUUID(), "平台运营（示例）");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-23T02:00:00Z");

    private final OpsImportMapper mapper = mock(OpsImportMapper.class);
    private final OpsImportPorts.Lookups lookups = mock(OpsImportPorts.Lookups.class);
    private final OpsImportPorts.Writers writers = mock(OpsImportPorts.Writers.class);
    private final OpsImportPorts.FileStore files = mock(OpsImportPorts.FileStore.class);
    private final OpsImportService service = new OpsImportService(mapper, lookups, writers, files,
            Clock.fixed(Instant.parse("2026-11-23T02:00:00Z"), ZoneOffset.UTC));

    private static OpsImportService.UploadedFile file(String name, String text) {
        return new OpsImportService.UploadedFile(name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static BatchRow batch(UUID id, String kind, String status) {
        return new BatchRow(id, kind, status, "a".repeat(64), 1, status.equals("REJECTED") ? 1 : 0, 0, null,
                "平台运营（示例）", AT, null, null, AT.plusDays(180));
    }

    @Test
    void filesAreLimitedInNumberSizeAndNames() {
        assertThatThrownBy(() -> OpsImportService.normalize(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpsImportService.normalize(List.of(file("a.csv", "x"), file("A.CSV", "y"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OpsImportService.normalize(List.of(file("a.csv", ""))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(OpsImportService.normalize(List.of(file("C:\\Users\\me\\01-organizations.csv", "x"))))
                .extracting(ImportValidator.Upload::fileName).containsExactly("01-organizations.csv");
    }

    @Test
    void theDigestIgnoresUploadOrderButNotContent() {
        var a = new ImportValidator.Upload("a.csv", "1".getBytes(StandardCharsets.UTF_8));
        var b = new ImportValidator.Upload("b.csv", "2".getBytes(StandardCharsets.UTF_8));
        var changed = new ImportValidator.Upload("b.csv", "3".getBytes(StandardCharsets.UTF_8));

        assertThat(OpsImportService.digest(List.of(a, b))).isEqualTo(OpsImportService.digest(List.of(b, a)))
                .isNotEqualTo(OpsImportService.digest(List.of(a, changed)));
    }

    @Test
    void contentImportedBeforeIsMarkedAsADuplicateAndNotValidatedAgainstIssues() {
        UUID earlier = UUID.randomUUID();
        when(mapper.findImported(eq("MERCHANT_ACCOUNTS"), anyString())).thenReturn(earlier);
        when(mapper.findBatch(any())).thenAnswer(call -> batch(call.getArgument(0), "MERCHANT_ACCOUNTS", "IMPORTED"));
        when(lookups.organizations(any())).thenReturn(Map.of());

        OpsImportViews.OpsImportBatchView view = service.check("merchant_accounts", List.of(file(
                "02-merchant-payment-accounts.csv", "organization_code,channel,sub_mchid,active\nX,WECHAT,12345678,true\n")),
                ACTOR);

        assertThat(view.status()).isEqualTo(OpsImportStatus.IMPORTED);
        verify(mapper).insertBatch(any(), eq("MERCHANT_ACCOUNTS"), eq("IMPORTED"), anyString(), eq(1), eq(0),
                eq(earlier), any(), any(), any(), any(), any(), any(), any());
        verify(mapper, never()).insertIssue(any(), anyInt(), any(), anyInt(), any(), any());
        verify(files).put(anyString(), any());
    }

    @Test
    void storageMustBeAvailableBeforeAnythingIsRecorded() {
        org.mockito.Mockito.doThrow(new OpsImportPorts.FileStoreUnavailableException("not configured"))
                .when(files).put(anyString(), any());

        assertThatThrownBy(() -> service.check("MALL", List.of(file("03-mall-products.csv", "x")), ACTOR))
                .isInstanceOfSatisfying(OpsImportException.class,
                        rejected -> assertThat(rejected.error()).isEqualTo(OpsImportException.Error.FILE_STORE_UNAVAILABLE));
        verify(mapper, never()).insertBatch(any(), any(), any(), any(), anyInt(), anyInt(), any(), any(), any(), any(),
                any(), any(), any(), any());
    }

    @Test
    void unknownKindsAreNotFound() {
        assertThatThrownBy(() -> service.check("TOYS", List.of(file("a.csv", "x")), ACTOR))
                .isInstanceOfSatisfying(OpsImportException.class,
                        rejected -> assertThat(rejected.error()).isEqualTo(OpsImportException.Error.NOT_FOUND));
    }

    @Test
    void runRefusesRejectedAndCheckOnlyBatchesAndReplaysImportedOnes() {
        UUID rejected = UUID.randomUUID();
        UUID activities = UUID.randomUUID();
        UUID imported = UUID.randomUUID();
        when(mapper.lockBatch(rejected)).thenReturn(batch(rejected, "MALL", "REJECTED"));
        when(mapper.lockBatch(activities)).thenReturn(batch(activities, "ACTIVITIES", "CHECKED"));
        when(mapper.lockBatch(imported)).thenReturn(batch(imported, "MALL", "IMPORTED"));
        when(mapper.findBatch(imported)).thenReturn(batch(imported, "MALL", "IMPORTED"));

        assertThatThrownBy(() -> service.run(rejected, ACTOR)).isInstanceOfSatisfying(OpsImportException.class,
                failure -> assertThat(failure.error()).isEqualTo(OpsImportException.Error.HAS_ISSUES));
        assertThatThrownBy(() -> service.run(activities, ACTOR)).isInstanceOfSatisfying(OpsImportException.class,
                failure -> assertThat(failure.error()).isEqualTo(OpsImportException.Error.NOT_WRITABLE));
        assertThat(service.run(imported, ACTOR).createdAccounts()).isEmpty();
        assertThatThrownBy(() -> service.run(UUID.randomUUID(), ACTOR)).isInstanceOfSatisfying(
                OpsImportException.class, failure -> assertThat(failure.error()).isEqualTo(OpsImportException.Error.NOT_FOUND));
        verifyNoInteractions(writers);
    }

    @Test
    void kindsListEveryTemplateInImportOrder() {
        assertThat(service.kinds()).extracting(OpsImportViews.OpsImportKindView::kind)
                .containsExactly(OpsImportKind.values());
        assertThat(service.kinds().get(2).files()).extracting(OpsImportViews.OpsImportFileSpecView::fileName)
                .containsExactly("03-mall-products.csv", "03b-mall-product-specs.csv", "03c-mall-pack-items.csv",
                        "03d-mall-skus.csv", "03e-mall-offers.csv");
    }
}
