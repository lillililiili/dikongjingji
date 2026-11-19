package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.lowaltitude.inspection.trade.application.TradeSourceOrders;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 询价（F4）问交易模块商家能不能收款；交易模块的入口第一次用到时才取（构造时取会和交易的结算成环）。 */
class MallInquiryPortsConfigurationTest {

    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("c1000000-0000-4000-8000-000000000004");

    @Test
    void merchantPaymentsAskTheTradeModuleOnlyWhenUsed() {
        @SuppressWarnings("unchecked")
        ObjectProvider<TradeSourceOrders> provider = mock(ObjectProvider.class);
        TradeSourceOrders orders = mock(TradeSourceOrders.class);
        when(provider.getObject()).thenReturn(orders);
        when(orders.merchantCanCollect(MERCHANT)).thenReturn(true);

        MallInquiryPorts.MerchantPayments payments =
                new MallInquiryPortsConfiguration().mallInquiryMerchantPayments(provider);

        verifyNoInteractions(provider);
        assertThat(payments.canCollect(MERCHANT)).isTrue();
        assertThat(payments.canCollect(OTHER)).isFalse();
        verify(orders).merchantCanCollect(OTHER);
    }
}
