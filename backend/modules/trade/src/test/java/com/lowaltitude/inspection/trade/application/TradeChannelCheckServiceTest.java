package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentQuery;
import com.lowaltitude.inspection.trade.application.PaymentChannel.PaymentState;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundQuery;
import com.lowaltitude.inspection.trade.application.PaymentChannel.RefundState;
import com.lowaltitude.inspection.trade.application.PaymentChannelException.Kind;
import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckItemView;
import com.lowaltitude.inspection.trade.application.TradeViews.ChannelCheckView;
import org.junit.jupiter.api.Test;

/**
 * 支付通道自检（S2-1）：配置项照抄，再按自检单号查单、查退款；渠道验过签地说“没有”才算通过。只调两个查询，不调别的。
 */
class TradeChannelCheckServiceTest {

    private static final String SUB = "1900000109";
    private static final Instant NOW = Instant.parse("2026-11-22T02:00:00Z");

    private final PaymentChannel channel = mock(PaymentChannel.class);
    private final PaymentChannelDiagnostics diagnostics = new PaymentChannelDiagnostics() {
        @Override
        public String mode() {
            return MODE_WECHAT;
        }

        @Override
        public List<ChannelCheckItemView> configuration() {
            return List.of(new ChannelCheckItemView("SP_MCHID", TradeChannelCheckService.PASS, "服务商户号格式对"));
        }
    };

    private TradeChannelCheckService service(PaymentChannel available) {
        return new TradeChannelCheckService(() -> available, diagnostics, Clock.fixed(NOW, ZoneOffset.UTC),
                () -> "17637764000001234567");
    }

    @Test
    void aVerifiedNotFoundForBothProbesPasses() {
        when(channel.queryPayment(SUB, "CHK17637764000001234567")).thenReturn(new PaymentQuery(PaymentState.NOT_FOUND,
                "ORDER_NOT_EXIST", "1900000001", SUB, "CHK17637764000001234567", null, null, null, null, null));
        when(channel.queryRefund(SUB, "CHKR17637764000001234567")).thenReturn(new RefundQuery(RefundState.NOT_FOUND,
                "RESOURCE_NOT_EXISTS", SUB, null, "CHKR17637764000001234567", null, null, null, null));

        ChannelCheckView view = service(channel).run(" " + SUB + " ");

        assertThat(view.mode()).isEqualTo("WECHAT");
        assertThat(view.passed()).isTrue();
        assertThat(view.checkedAt()).isEqualTo(NOW);
        assertThat(view.items()).extracting(ChannelCheckItemView::code)
                .containsExactly("SP_MCHID", "CHANNEL", "QUERY_PAYMENT", "QUERY_REFUND");
        assertThat(view.items()).extracting(ChannelCheckItemView::status).containsOnly("PASS");
        verify(channel).queryPayment(SUB, "CHK17637764000001234567");
        verify(channel).queryRefund(SUB, "CHKR17637764000001234567");
        verifyNoMoreInteractions(channel);
    }

    @Test
    void channelFailuresBecomeFailItemsWithAHintAndNoChannelText() {
        when(channel.queryPayment(eq(SUB), anyString()))
                .thenThrow(new PaymentChannelException(Kind.REJECTED, "SIGN_ERROR", 401, "wechat pay SIGN_ERROR secret-ish text"));
        when(channel.queryRefund(eq(SUB), anyString()))
                .thenThrow(new PaymentChannelException(Kind.UNTRUSTED, "SIGNATURE", 200, "bad signature"));

        ChannelCheckView view = service(channel).run(SUB);

        assertThat(view.passed()).isFalse();
        ChannelCheckItemView payment = view.items().get(2);
        assertThat(payment.status()).isEqualTo("FAIL");
        assertThat(payment.message()).contains("API 证书序列号和私钥").contains("SIGN_ERROR").contains("HTTP 401")
                .doesNotContain("secret-ish");
        assertThat(view.items().get(3).message()).contains("微信支付公钥");
    }

    @Test
    void networkAndUnexpectedStatesAreExplained() {
        when(channel.queryPayment(eq(SUB), anyString()))
                .thenThrow(new PaymentChannelException(Kind.RETRYABLE, "NETWORK", "wechat pay is unreachable", null));
        when(channel.queryRefund(eq(SUB), anyString())).thenReturn(new RefundQuery(RefundState.SUCCESS, "SUCCESS", SUB,
                "x", "y", "z", 1L, 1L, NOW));

        ChannelCheckView view = service(channel).run(SUB);

        assertThat(view.items().get(2).message()).contains("出口网络");
        assertThat(view.items().get(3).status()).isEqualTo("WARN");
        assertThat(view.passed()).isFalse();
    }

    @Test
    void withoutASubMerchantTheProbesAreSkipped() {
        ChannelCheckView view = service(channel).run(null);

        assertThat(view.passed()).isTrue();
        assertThat(view.items()).extracting(ChannelCheckItemView::status)
                .containsExactly("PASS", "PASS", "SKIPPED", "SKIPPED");
        verifyNoMoreInteractions(channel);
    }

    @Test
    void withoutAChannelItFailsAndSkipsTheProbes() {
        ChannelCheckView view = new TradeChannelCheckService(() -> null, PaymentChannelDiagnostics.none(),
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "1").run(SUB);

        assertThat(view.mode()).isEqualTo("DISABLED");
        assertThat(view.passed()).isFalse();
        assertThat(view.items()).extracting(ChannelCheckItemView::code)
                .containsExactly("ENABLED", "CHANNEL", "QUERY_PAYMENT", "QUERY_REFUND");
        assertThat(view.items()).extracting(ChannelCheckItemView::status)
                .containsExactly("FAIL", "FAIL", "SKIPPED", "SKIPPED");
    }

    @Test
    void aMalformedSubMerchantIsRefused() {
        assertThatThrownBy(() -> service(channel).run("12ab")).isInstanceOf(IllegalArgumentException.class);
        verifyNoMoreInteractions(channel);
    }
}
