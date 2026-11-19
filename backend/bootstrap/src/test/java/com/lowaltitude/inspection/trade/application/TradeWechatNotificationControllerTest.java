package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.trade.application.TradePaymentService.NotificationResult;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 微信支付回调的接口层：原始字节原样（严格 UTF-8）交给支付服务，请求头不分大小写，按服务的结果回 204 / 401 / 500，
 * 超过 64KB 的请求体回 413。验签、去重、入账都在 TradePaymentService（TradePaymentServiceTest）。
 * 不登录也能到达由 AuthenticationSecurityIntegrationTest 检查。
 */
class TradeWechatNotificationControllerTest {

    private static final String PATH = "/api/v1/trade/channels/wechat/notifications";
    /** 带中文和转义的 JSON：一个字节都不能变，否则验签不过。 */
    private static final String BODY = "{\"id\":\"EV-2018022511223320873\",\"create_time\":\"2026-11-19T00:31:00+08:00\","
            + "\"event_type\":\"TRANSACTION.SUCCESS\",\"summary\":\"支付成功\",\"resource\":{\"nonce\":\"n\\/1\","
            + "\"ciphertext\":\"YWJj\",\"algorithm\":\"AEAD_AES_256_GCM\"}}  \n";

    private final TradePaymentService payments = mock(TradePaymentService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TradeWechatNotificationController(payments))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void aHandledNotificationIsAnsweredWithNoContentAndTheBodyIsPassedVerbatim() throws Exception {
        when(payments.handleNotification(anyMap(), anyString())).thenReturn(NotificationResult.ACKNOWLEDGED);

        mockMvc.perform(notification().content(BODY.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(payments).handleNotification(anyMap(), body.capture());
        assertThat(body.getValue()).isEqualTo(BODY);
    }

    // 请求头的名字大小写随代理而变：交给服务的是不分大小写的表，四个微信头都在。
    @Test
    void headersReachTheServiceCaseInsensitively() throws Exception {
        when(payments.handleNotification(anyMap(), anyString())).thenReturn(NotificationResult.ACKNOWLEDGED);

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("wechatpay-serial", "PUB_KEY_ID_0001")
                        .header("WECHATPAY-SIGNATURE", "c2lnbmF0dXJl")
                        .header("Wechatpay-Timestamp", "1763483460")
                        .header("Wechatpay-Nonce", "nonce-1")
                        .content(BODY))
                .andExpect(status().isNoContent());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> headers = ArgumentCaptor.forClass(Map.class);
        verify(payments).handleNotification(headers.capture(), any());
        Map<String, String> received = headers.getValue();
        assertThat(received.get("Wechatpay-Serial")).isEqualTo("PUB_KEY_ID_0001");
        assertThat(received.get("wechatpay-signature")).isEqualTo("c2lnbmF0dXJl");
        assertThat(received.get("WECHATPAY-TIMESTAMP")).isEqualTo("1763483460");
        assertThat(received.get("wechatpay-nonce")).isEqualTo("nonce-1");
    }

    @ParameterizedTest
    @EnumSource(NotificationResult.class)
    void eachResultHasItsStatusAndFailuresCarryTheWechatFailureBody(NotificationResult result) throws Exception {
        when(payments.handleNotification(anyMap(), anyString())).thenReturn(result);

        var response = mockMvc.perform(notification().content(BODY));

        switch (result) {
            case ACKNOWLEDGED -> response.andExpect(status().isNoContent());
            case UNTRUSTED -> response.andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$.code").value("FAIL"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
            case FAILED -> response.andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.length()").value(2))
                    .andExpect(jsonPath("$.code").value("FAIL"))
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
    }

    @Test
    void aBodyOfExactlySixtyFourKilobytesIsAccepted() throws Exception {
        when(payments.handleNotification(anyMap(), anyString())).thenReturn(NotificationResult.UNTRUSTED);
        byte[] limit = "a".repeat(TradeWechatNotificationController.MAX_BODY_BYTES).getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(notification().content(limit)).andExpect(status().isUnauthorized());

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(payments).handleNotification(anyMap(), body.capture());
        assertThat(body.getValue()).hasSize(64 * 1024);
    }

    @Test
    void aBodyOverSixtyFourKilobytesIsTooLargeAndNeverReachesTheService() throws Exception {
        byte[] tooLarge = "a".repeat(TradeWechatNotificationController.MAX_BODY_BYTES + 1)
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(notification().content(tooLarge))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FAIL"));

        verifyNoInteractions(payments);
    }

    // 分块传输（没有 Content-Length）时靠读到的字节数判断，不信请求头。
    @Test
    void aChunkedBodyOverTheLimitIsStoppedWhileReading() throws IOException {
        MockHttpServletRequest small = new MockHttpServletRequest("POST", PATH);
        small.setContent("x".repeat(100).getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest chunked = new ChunkedRequest(
                "x".repeat(TradeWechatNotificationController.MAX_BODY_BYTES + 1).getBytes(StandardCharsets.UTF_8));
        MockHttpServletRequest exact = new ChunkedRequest(
                "x".repeat(TradeWechatNotificationController.MAX_BODY_BYTES).getBytes(StandardCharsets.UTF_8));
        // 声明的长度超了，一个字节都不读。
        MockHttpServletRequest declared = new ChunkedRequest(new byte[0]) {
            @Override
            public long getContentLengthLong() {
                return TradeWechatNotificationController.MAX_BODY_BYTES + 1L;
            }

            @Override
            public ServletInputStream getInputStream() {
                throw new AssertionError("the body must not be read");
            }
        };

        assertThat(chunked.getContentLengthLong()).isEqualTo(-1);
        assertThat(TradeWechatNotificationController.readBody(small)).hasSize(100);
        assertThat(TradeWechatNotificationController.readBody(chunked)).isNull();
        assertThat(TradeWechatNotificationController.readBody(exact)).hasSize(64 * 1024);
        assertThat(TradeWechatNotificationController.readBody(declared)).isNull();
    }

    // 不是合法 UTF-8 的字节解码时会被改掉，验签必然不过：交给服务一个空体，由服务回 401。
    @Test
    void bytesThatAreNotUtf8ReachTheServiceAsAnEmptyBody() throws Exception {
        when(payments.handleNotification(anyMap(), anyString())).thenReturn(NotificationResult.UNTRUSTED);
        byte[] latin1 = "{\"summary\":\"café\"}".getBytes(StandardCharsets.ISO_8859_1);

        mockMvc.perform(notification().content(latin1)).andExpect(status().isUnauthorized());

        verify(payments).handleNotification(anyMap(), org.mockito.ArgumentMatchers.eq(""));
        assertThat(TradeWechatNotificationController.readBody(request(new byte[] {(byte) 0xC3, (byte) 0x28})))
                .isEmpty();
        assertThat(TradeWechatNotificationController.readBody(request(new byte[] {(byte) 0xED, (byte) 0xA0,
                (byte) 0x80}))).as("UTF-16 surrogate encoded as UTF-8").isEmpty();
        assertThat(TradeWechatNotificationController.readBody(request("支付成功".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("支付成功");
    }

    @Test
    void anEmptyBodyIsStillHandedToTheServiceToRefuse() throws Exception {
        when(payments.handleNotification(anyMap(), anyString())).thenReturn(NotificationResult.UNTRUSTED);

        mockMvc.perform(post(PATH)).andExpect(status().isUnauthorized());

        verify(payments).handleNotification(anyMap(), org.mockito.ArgumentMatchers.eq(""));
    }

    @Test
    void theRouteIsAPostToTheContractsPath() throws Exception {
        PostMapping mapping = TradeWechatNotificationController.class
                .getMethod("receive", jakarta.servlet.http.HttpServletRequest.class)
                .getAnnotation(PostMapping.class);

        assertThat(mapping.value()).containsExactly(PATH);
        assertThat(TradeWechatNotificationController.MAX_BODY_BYTES).isEqualTo(65_536);
    }

    // ---- 辅助 ----

    private static MockHttpServletRequestBuilder notification() {
        return post(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Wechatpay-Serial", "PUB_KEY_ID_0001")
                .header("Wechatpay-Signature", "c2lnbmF0dXJl")
                .header("Wechatpay-Timestamp", "1763483460")
                .header("Wechatpay-Nonce", "nonce-1");
    }

    private static MockHttpServletRequest request(byte[] body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", PATH);
        request.setContent(body);
        return request;
    }

    /** 没有 Content-Length 的请求（分块传输）。 */
    private static class ChunkedRequest extends MockHttpServletRequest {

        private final byte[] body;

        ChunkedRequest(byte[] body) {
            super("POST", PATH);
            this.body = body;
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public int getContentLength() {
            return -1;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return input.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int read() {
                    return input.read();
                }

                @Override
                public int read(byte[] buffer, int offset, int length) {
                    return input.read(buffer, offset, length);
                }
            };
        }
    }
}
