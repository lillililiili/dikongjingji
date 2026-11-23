package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeMerchantAccountMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeMerchantAccountMapper.AccountRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 运营端收款账户登记（S3-1）：格式、开发占位号段、版本号、留痕。 */
class OpsMerchantAccountServiceTest {

    private static final UUID ORG = UUID.fromString("c1000000-0000-4000-8000-000000000006");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-23T02:00:00Z");
    private static final OpsMerchantAccountService.Operator OPERATOR =
            new OpsMerchantAccountService.Operator(UUID.randomUUID(), "平台运营（示例）");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-23T02:00:00Z"), ZoneOffset.UTC);

    private final TradeMerchantAccountMapper mapper = mock(TradeMerchantAccountMapper.class);

    private static AccountRow unregistered() {
        return new AccountRow(ORG, "DEV-MALL-RENTAL", "示例设备租赁商", "ACTIVE", false, null, false, null, null);
    }

    private static AccountRow registered(String subMchid, boolean active, int version) {
        return new AccountRow(ORG, "DEV-MALL-RENTAL", "示例设备租赁商", "ACTIVE", true, subMchid, active, AT, version);
    }

    @Test
    void placeholderNumbersOnlyWorkWhereTheyAreAllowed() {
        OpsMerchantAccountService production = new OpsMerchantAccountService(mapper, false, CLOCK);
        OpsMerchantAccountService development = new OpsMerchantAccountService(mapper, true, CLOCK);

        assertThatThrownBy(() -> production.requireUsable("1900000106"))
                .isInstanceOfSatisfying(OpsMerchantAccountException.class, rejected ->
                        assertThat(rejected.error()).isEqualTo(OpsMerchantAccountException.Error.SUB_MCHID_PLACEHOLDER));
        assertThat(production.requireUsable(" 1612345678 ")).isEqualTo("1612345678");
        assertThat(development.requireUsable("1900000106")).isEqualTo("1900000106");
        assertThat(production.placeholderRejected("1900000999")).isTrue();
        assertThat(development.placeholderRejected("1900000999")).isFalse();
        for (String invalid : new String[] {"1234567", "12ab5678", "", null}) {
            assertThatThrownBy(() -> development.requireUsable(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void registeringTakesNoVersionChangingTakesTheCurrentOneAndBothAreRecorded() {
        OpsMerchantAccountService service = new OpsMerchantAccountService(mapper, false, CLOCK);
        when(mapper.findOrganizationType(ORG)).thenReturn("PROVIDER");
        when(mapper.findAccount(ORG)).thenReturn(unregistered(), registered("1612345678", true, 0),
                registered("1612345678", true, 0), registered("1612345679", false, 1));
        when(mapper.insertAccount(ORG, "1612345678", true, AT)).thenReturn(0);
        when(mapper.updateAccount(ORG, "1612345679", false, 0, AT)).thenReturn(1);

        assertThat(service.save(ORG, "1612345678", true, null, OPERATOR).registered()).isTrue();
        assertThat(service.save(ORG, "1612345679", false, 0, OPERATOR).subMchid()).isEqualTo("1612345679");

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertOperation(eq(ORG), eq("REGISTER"), details.capture(), eq(OPERATOR.accountId()), any(),
                eq(AT));
        verify(mapper).insertOperation(eq(ORG), eq("CHANGE"), details.capture(), eq(OPERATOR.accountId()), any(),
                eq(AT));
        assertThat(details.getAllValues().get(1)).contains("1612345678 → 1612345679").contains("暂停收款");
    }

    @Test
    void staleVersionsAndNonProvidersAreRefused() {
        OpsMerchantAccountService service = new OpsMerchantAccountService(mapper, false, CLOCK);
        when(mapper.findOrganizationType(ORG)).thenReturn("PROVIDER");
        when(mapper.findAccount(ORG)).thenReturn(registered("1612345678", true, 3));
        // 版本不对时 UPDATE … RETURNING 没有行，MyBatis 给 null。
        when(mapper.updateAccount(ORG, "1612345678", true, 2, AT)).thenReturn(null);
        assertThatThrownBy(() -> service.save(ORG, "1612345678", true, null, OPERATOR))
                .isInstanceOf(OpsMerchantAccountException.class);
        assertThatThrownBy(() -> service.save(ORG, "1612345678", true, 2, OPERATOR))
                .isInstanceOf(OpsMerchantAccountException.class);

        UUID customer = UUID.randomUUID();
        when(mapper.findOrganizationType(customer)).thenReturn("CUSTOMER");
        assertThatThrownBy(() -> service.save(customer, "1612345678", true, null, OPERATOR))
                .isInstanceOfSatisfying(OpsMerchantAccountException.class, rejected ->
                        assertThat(rejected.error()).isEqualTo(OpsMerchantAccountException.Error.MERCHANT_UNKNOWN));
        assertThatThrownBy(() -> service.operations(UUID.randomUUID()))
                .isInstanceOfSatisfying(OpsMerchantAccountException.class, rejected ->
                        assertThat(rejected.error()).isEqualTo(OpsMerchantAccountException.Error.NOT_FOUND));
        verify(mapper, never()).insertOperation(any(), anyString(), anyString(), any(), any(), any());
    }
}
