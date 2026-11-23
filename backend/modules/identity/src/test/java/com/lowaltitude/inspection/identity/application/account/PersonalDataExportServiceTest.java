package com.lowaltitude.inspection.identity.application.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.AccountException.Error;
import com.lowaltitude.inspection.identity.domain.CurrentIdentity;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.ExportRow;
import com.lowaltitude.inspection.identity.infrastructure.persistence.AccountMapper.UserRow;
import org.junit.jupiter.api.Test;

/** 个人信息导出：要验证本人手机号；同时只一份；生成 ZIP 放受控存储；下载凭据只能用一次，对不上一律按不存在。 */
class PersonalDataExportServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");
    private static final OffsetDateTime AT = NOW.atOffset(ZoneOffset.UTC);
    private static final UUID ME = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final AccountMapper mapper = mock(AccountMapper.class);
    private final SmsCodeService codes = mock(SmsCodeService.class);
    private final EnterpriseService enterprises = mock(EnterpriseService.class);
    private final Map<String, byte[]> stored = new HashMap<>();
    private boolean storeUp = true;
    private final AccountPorts.ExportFiles files = new AccountPorts.ExportFiles() {
        public void put(String key, byte[] content) {
            if (!storeUp) throw new AccountPorts.ExportStoreUnavailableException("down");
            stored.put(key, content);
        }
        public byte[] read(String key) { return stored.get(key); }
        public void delete(String key) { stored.remove(key); }
    };
    private final AccountPorts.PersonalDataSource orders = new AccountPorts.PersonalDataSource() {
        public String code() { return "ORDERS"; }
        public String title() { return "订单记录"; }
        public String description() { return "商品、服务和报名订单"; }
        public Long count(UUID accountId) { return 2L; }
        public Object export(UUID accountId) { return List.of("MO1", "MO2"); }
    };
    private final PersonalDataExportService service = new PersonalDataExportService(mapper, codes, enterprises,
            List.of(orders), files, value -> value.toString().getBytes(StandardCharsets.UTF_8),
            Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());

    @Test
    void onlyKnownSectionsOnceEachAndAPhoneIsNeeded() {
        for (List<String> bad : List.of(List.<String>of(), List.of("SECRETS"), List.of("ACCOUNT", "ACCOUNT"))) {
            assertThatThrownBy(() -> service.request(ME, bad, "123456"))
                    .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.INVALID);
        }
        when(mapper.lockUser(ME)).thenReturn(new UserRow(ME, "ACTIVE", "王女士", null, null, null, "PASSWORD"));
        assertThatThrownBy(() -> service.request(ME, List.of("ACCOUNT"), "123456"))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.PHONE_REQUIRED);
    }

    @Test
    void oneExportAtATimeAndTheCodeIsChecked() {
        when(mapper.lockUser(ME)).thenReturn(new UserRow(ME, "ACTIVE", "王女士", "13800002026", null, null, "PASSWORD"));
        when(mapper.countPendingExports(ME)).thenReturn(1);
        assertThatThrownBy(() -> service.request(ME, List.of("ACCOUNT"), "123456"))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.EXPORT_IN_PROGRESS);

        when(mapper.countPendingExports(ME)).thenReturn(0);
        UUID id = UUID.randomUUID();
        when(mapper.insertExport(ME, "ACCOUNT,ORDERS", AT)).thenReturn(id);
        when(mapper.findExport(id, ME)).thenReturn(new ExportRow(id, ME, "ACCOUNT,ORDERS", "PENDING", AT, null, null, null, null));
        AccountViews.DataExportView view = service.request(ME, List.of("ORDERS", "ACCOUNT"), "123456");
        verify(codes).verify("13800002026", "EXPORT", "123456", ME);
        assertThat(view.sections()).containsExactly("ACCOUNT", "ORDERS");
    }

    @Test
    void generatingWritesAZipToTheStoreAndKeepsItSevenDays() {
        UUID id = UUID.randomUUID();
        when(mapper.lockPendingExports(1)).thenReturn(List.of(new ExportRow(id, ME, "ACCOUNT,ORDERS", "PENDING", AT, null, null, null, null)));
        when(mapper.findUser(ME)).thenReturn(new UserRow(ME, "ACTIVE", "王女士", "13800002026", null, null, "PASSWORD"));
        when(enterprises.currentIdentity(ME)).thenReturn(CurrentIdentity.personal());

        assertThat(service.generateNext()).isTrue();

        String key = "data-exports/" + ME + "/" + id + ".zip";
        assertThat(stored.get(key)).startsWith("PK".getBytes(StandardCharsets.US_ASCII));
        verify(mapper).markExportReady(eq(id), eq(key), anyLong(), anyString(), eq(AT), eq(AT.plusDays(7)));
    }

    @Test
    void anUnavailableStoreMarksTheExportFailedInsteadOfPretending() {
        storeUp = false;
        UUID id = UUID.randomUUID();
        when(mapper.lockPendingExports(1)).thenReturn(List.of(new ExportRow(id, ME, "ORDERS", "PENDING", AT, null, null, null, null)));

        service.generateNext();

        verify(mapper).markExportFailed(id);
        verify(mapper, never()).markExportReady(any(), anyString(), anyLong(), anyString(), any(), any());
    }

    @Test
    void downloadIntentsAreOnlyForTheOwnerAndReadyCopies() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.createDownload(ME, id))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.NOT_FOUND);
        when(mapper.findExport(id, ME)).thenReturn(new ExportRow(id, ME, "ORDERS", "PENDING", AT, null, null, null, null));
        assertThatThrownBy(() -> service.createDownload(ME, id))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.EXPORT_NOT_READY);
        when(mapper.findExport(id, ME)).thenReturn(new ExportRow(id, ME, "ORDERS", "READY", AT, AT, AT.plusDays(7), "k", 10L));

        AccountViews.DownloadIntentView intent = service.createDownload(ME, id);

        String token = intent.downloadUrl().substring(intent.downloadUrl().indexOf("token=") + 6);
        assertThat(intent.downloadUrl()).startsWith("/api/v1/account/data-exports/" + id + "/file?token=");
        assertThat(intent.expiresAt()).isEqualTo(AT.plusMinutes(5));
        verify(mapper).setDownloadToken(id, ME, PersonalDataExportService.tokenHash(token), AT.plusMinutes(5));
    }

    @Test
    void aWrongUsedOrMissingTokenIsNotFound() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.download(id, "short"))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.NOT_FOUND);
        assertThatThrownBy(() -> service.download(id, "x".repeat(43)))
                .extracting(e -> ((AccountException) e).error()).isEqualTo(Error.NOT_FOUND);

        when(mapper.consumeDownloadToken(id, PersonalDataExportService.tokenHash("y".repeat(43)), AT)).thenReturn("k");
        stored.put("k", new byte[] {1});
        assertThat(service.download(id, "y".repeat(43)).content()).containsExactly(1);
    }
}
