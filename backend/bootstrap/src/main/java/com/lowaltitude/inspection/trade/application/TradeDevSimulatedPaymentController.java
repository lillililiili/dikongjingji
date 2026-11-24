package com.lowaltitude.inspection.trade.application;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.infrastructure.TradeWechatPayProperties;
import com.lowaltitude.inspection.bootstrap.infrastructure.WechatPayPreflight;
import com.lowaltitude.inspection.account.application.CustomerAccount;
import com.lowaltitude.inspection.trade.application.TradeException.Error;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅开发、演示环境：代替买家在本机模拟微信（infra/dev/fake-wechat）里付款，给演示包收银台的“模拟付款”用。
 *
 * <p>这里不改订单：只请模拟微信把这一笔标成已付并发回调，订单照样由验签后的回调推进。
 * 支付渠道不是指向本机回环的模拟微信时一律 404；只有 dev profile 才有这个接口，不在契约里。
 */
@RestController
@Profile("dev")
public final class TradeDevSimulatedPaymentController {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final TradePaymentService payments;
    private final TradeWechatPayProperties properties;
    private final PayCall payCall;

    @Autowired
    public TradeDevSimulatedPaymentController(TradePaymentService payments, TradeWechatPayProperties properties) {
        this(payments, properties, TradeDevSimulatedPaymentController::post);
    }

    TradeDevSimulatedPaymentController(TradePaymentService payments, TradeWechatPayProperties properties,
                                       PayCall payCall) {
        this.payments = payments;
        this.properties = properties;
        this.payCall = payCall;
    }

    @PostMapping("/api/v1/dev/trade/orders/{orderId}/simulated-payment")
    public ResponseEntity<Void> pay(Authentication authentication, @PathVariable UUID orderId) {
        if (!properties.enabled() || !WechatPayPreflight.simulated(properties)) {
            throw new TradeException(Error.NOT_FOUND);
        }
        String outTradeNo = payments.openPaymentNo(CustomerAccount.of(authentication), orderId);
        if (outTradeNo == null) {
            throw new TradeException(Error.ORDER_STATE_INVALID);
        }
        int status = payCall.post(properties.apiBase().resolve("/_control/pay/" + outTradeNo + "?notify=1"));
        if (status != 200) {
            throw new TradeException(Error.CHANNEL_UNAVAILABLE);
        }
        return ResponseEntity.noContent().build();
    }

    @FunctionalInterface
    interface PayCall {
        /** 返回 HTTP 状态码；连不上时返回 0。 */
        int post(URI uri);
    }

    private static int post(URI uri) {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            return client.send(HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
                    .statusCode();
        } catch (IOException exception) {
            return 0;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }
}
