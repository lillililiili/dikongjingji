package com.lowaltitude.inspection.trade.infrastructure.wechat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 正式传输按字节读应答（E06：账单的 SHA1 要对收到的原始字节算，不能先按 UTF-8 解码再编码回去）。
 * 在本机回环的随机端口上起一个 JDK 自带的 HTTP 服务。
 */
class JdkWechatPayTransportTest {

    private HttpServer server;
    private byte[] answer;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/file", exchange -> {
            exchange.getResponseHeaders().add("Wechatpay-Nonce", "N1");
            exchange.sendResponseHeaders(200, answer.length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(answer);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void theRawBytesAreKeptEvenWhenTheyAreNotUtf8() throws Exception {
        answer = new byte[] {'`', 'a', (byte) 0xC3, (byte) 0x28, (byte) 0xB0, '\r', '\n'};
        JdkWechatPayTransport transport = new JdkWechatPayTransport(Duration.ofSeconds(2), Duration.ofSeconds(5));

        WechatPayTransport.Response response = transport.send(new WechatPayTransport.Request("GET",
                URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort()
                        + "/file?token=1"), Map.of("Accept", "application/json"), null));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.raw()).isEqualTo(answer);
        assertThat(FakeWechatPay.sha1(response.raw())).isEqualTo(FakeWechatPay.sha1(answer));
        // 文本是按 UTF-8 宽松解出的（坏字节换成替换符），所以不能拿它算哈希。
        assertThat(response.body().getBytes(StandardCharsets.UTF_8)).isNotEqualTo(answer);
        assertThat(response.headers()).containsEntry("wechatpay-nonce", "N1");
    }

    // raw() 每次给副本：调用方改了也不影响应答本身。
    @Test
    void theRawBytesCannotBeChangedFromOutside() {
        byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
        WechatPayTransport.Response response = new WechatPayTransport.Response(200, Map.of(), "abc", bytes);
        bytes[0] = 'x';
        response.raw()[1] = 'y';

        assertThat(response.raw()).isEqualTo("abc".getBytes(StandardCharsets.UTF_8));
        // 旧的三参构造器：原始字节就是文本的 UTF-8。
        assertThat(new WechatPayTransport.Response(200, Map.of(), "机器人").raw())
                .isEqualTo("机器人".getBytes(StandardCharsets.UTF_8));
        assertThat(new WechatPayTransport.Response(200, null, null).raw()).isEmpty();
    }
}
