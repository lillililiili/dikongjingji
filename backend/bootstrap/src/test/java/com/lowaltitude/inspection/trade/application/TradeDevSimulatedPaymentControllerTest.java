package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.infrastructure.TradeWechatPayProperties;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

class TradeDevSimulatedPaymentControllerTest {

    private static final UUID BUYER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ORDER = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private final TradePaymentService payments = mock(TradePaymentService.class);
    private final List<URI> calls = new ArrayList<>();

    @Test
    void asksTheLocalFakeToPayTheOpenPaymentOfTheBuyersOrder() {
        when(payments.openPaymentNo(BUYER, ORDER)).thenReturn("P123");
        assertThat(controller(true, "http://127.0.0.1:17460", 200).pay(buyer(), ORDER).getStatusCode().value())
                .isEqualTo(204);
        assertThat(calls).containsExactly(URI.create("http://127.0.0.1:17460/_control/pay/P123?notify=1"));
    }

    @Test
    void doesNotExistUnlessTheChannelIsTheLocalFake() {
        for (var controller : List.of(controller(true, "https://api.mch.weixin.qq.com", 200),
                controller(true, "http://10.0.0.8:17460", 200), controller(false, "http://127.0.0.1:17460", 200))) {
            assertThatThrownBy(() -> controller.pay(buyer(), ORDER)).isInstanceOfSatisfying(TradeException.class,
                    failure -> assertThat(failure.error()).isEqualTo(Error.NOT_FOUND));
        }
        verify(payments, never()).openPaymentNo(any(), any());
        assertThat(calls).isEmpty();
    }

    @Test
    void needsAPaymentInProgressAndAReachableFake() {
        assertThatThrownBy(() -> controller(true, "http://127.0.0.1:17460", 200).pay(buyer(), ORDER))
                .isInstanceOfSatisfying(TradeException.class,
                        failure -> assertThat(failure.error()).isEqualTo(Error.ORDER_STATE_INVALID));
        when(payments.openPaymentNo(BUYER, ORDER)).thenReturn("P123");
        assertThatThrownBy(() -> controller(true, "http://127.0.0.1:17460", 0).pay(buyer(), ORDER))
                .isInstanceOfSatisfying(TradeException.class,
                        failure -> assertThat(failure.error()).isEqualTo(Error.CHANNEL_UNAVAILABLE));
    }

    @Test
    void existsInTheDevProfileOnly() {
        assertThat(TradeDevSimulatedPaymentController.class.getAnnotation(Profile.class).value()).containsExactly("dev");
    }

    private TradeDevSimulatedPaymentController controller(boolean enabled, String base, int status) {
        return new TradeDevSimulatedPaymentController(payments, new TradeWechatPayProperties(enabled, null, null, null,
                null, null, null, null, URI.create(base), null, null, null, null, null), uri -> {
                    calls.add(uri);
                    return status;
                });
    }

    private static Authentication buyer() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .claim("subjectId", BUYER.toString()).claim("roles", List.of("CUSTOMER")).build();
        return new TestingAuthenticationToken(jwt, null);
    }
}
