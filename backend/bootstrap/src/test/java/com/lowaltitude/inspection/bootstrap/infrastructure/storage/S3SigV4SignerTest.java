package com.lowaltitude.inspection.bootstrap.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * SigV4 签名对照 AWS 公开文档里的标准测试向量（S6）：
 * <ul>
 *   <li>AWS Signature Version 4 测试套件（aws-sig-v4-test-suite）的 get-vanilla、get-vanilla-query-order-key-case；</li>
 *   <li>Amazon S3 API 参考“Signature Calculations for the Authorization Header: Transferring Payload in a Single Chunk”
 *       的 GET Object、PUT Object、GET Bucket Lifecycle、Get Bucket（List Objects）四例；</li>
 *   <li>Amazon S3 API 参考“Authenticating Requests: Using Query Parameters”的预签名 GET 示例。</li>
 * </ul>
 * 文档里的访问密钥是 AWS 公开的示例值，不对应任何真实账号。
 */
class S3SigV4SignerTest {

    private static final S3SigV4Signer.Credentials SUITE =
            new S3SigV4Signer.Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
    private static final Instant SUITE_AT = Instant.parse("2015-08-30T12:36:00Z");

    private static final S3SigV4Signer.Credentials S3_DOC =
            new S3SigV4Signer.Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
    private static final Instant S3_DOC_AT = Instant.parse("2013-05-24T00:00:00Z");
    private static final String S3_HOST = "examplebucket.s3.amazonaws.com";

    @Test
    void signsTheTestSuiteGetVanillaRequest() {
        String authorization = S3SigV4Signer.authorization("GET", URI.create("https://example.amazonaws.com/"),
                Map.of("Host", "example.amazonaws.com", "X-Amz-Date", "20150830T123600Z"),
                S3SigV4Signer.EMPTY_PAYLOAD_SHA256, SUITE_AT, "us-east-1", "service", SUITE);

        assertThat(authorization).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/"
                + "aws4_request, SignedHeaders=host;x-amz-date, "
                + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31");
    }

    @Test
    void sortsQueryParametersLikeTheTestSuite() {
        String authorization = S3SigV4Signer.authorization("GET",
                URI.create("https://example.amazonaws.com/?Param2=value2&Param1=value1"),
                Map.of("Host", "example.amazonaws.com", "X-Amz-Date", "20150830T123600Z"),
                S3SigV4Signer.EMPTY_PAYLOAD_SHA256, SUITE_AT, "us-east-1", "service", SUITE);

        assertThat(authorization)
                .endsWith("Signature=b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500");
    }

    @Test
    void signsTheS3GetObjectExample() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", S3_HOST);
        headers.put("Range", "bytes=0-9");
        headers.put("x-amz-content-sha256", S3SigV4Signer.EMPTY_PAYLOAD_SHA256);
        headers.put("x-amz-date", "20130524T000000Z");

        String authorization = S3SigV4Signer.authorization("GET", URI.create("https://" + S3_HOST + "/test.txt"),
                headers, S3SigV4Signer.EMPTY_PAYLOAD_SHA256, S3_DOC_AT, "us-east-1", "s3", S3_DOC);

        assertThat(authorization).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/"
                + "aws4_request, SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, "
                + "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41");
    }

    @Test
    void signsTheS3PutObjectExampleWithAnEncodedKey() {
        byte[] body = "Welcome to Amazon S3.".getBytes(StandardCharsets.UTF_8);
        String payloadHash = S3SigV4Signer.sha256Hex(body);
        assertThat(payloadHash).isEqualTo("44ce7dd67c959e0d3524ffac1771dfbba87d2b6b4b4e99e42034a8b803f8b072");
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", S3_HOST);
        headers.put("Date", "Fri, 24 May 2013 00:00:00 GMT");
        headers.put("x-amz-date", "20130524T000000Z");
        headers.put("x-amz-storage-class", "REDUCED_REDUNDANCY");
        headers.put("x-amz-content-sha256", payloadHash);

        URI uri = URI.create("https://" + S3_HOST + "/" + S3SigV4Signer.encodePath("test$file.text"));
        String authorization = S3SigV4Signer.authorization("PUT", uri, headers, payloadHash, S3_DOC_AT,
                "us-east-1", "s3", S3_DOC);

        assertThat(uri.getRawPath()).isEqualTo("/test%24file.text");
        assertThat(authorization).isEqualTo("AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/"
                + "aws4_request, SignedHeaders=date;host;x-amz-content-sha256;x-amz-date;x-amz-storage-class, "
                + "Signature=98ad721746da40c64f1a55b78f14c238d841ea1380cd77a1b5971af0ece108bd");
    }

    @Test
    void signsTheS3SubresourceAndListExamples() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", S3_HOST);
        headers.put("x-amz-content-sha256", S3SigV4Signer.EMPTY_PAYLOAD_SHA256);
        headers.put("x-amz-date", "20130524T000000Z");

        String lifecycle = S3SigV4Signer.authorization("GET", URI.create("https://" + S3_HOST + "/?lifecycle"),
                headers, S3SigV4Signer.EMPTY_PAYLOAD_SHA256, S3_DOC_AT, "us-east-1", "s3", S3_DOC);
        String list = S3SigV4Signer.authorization("GET", URI.create("https://" + S3_HOST + "/?max-keys=2&prefix=J"),
                headers, S3SigV4Signer.EMPTY_PAYLOAD_SHA256, S3_DOC_AT, "us-east-1", "s3", S3_DOC);

        assertThat(lifecycle).endsWith("Signature=fea454ca298b7da1c68078a5d1bdbfbbe0d65c699e0f91ac7a200a0136783543");
        assertThat(list).endsWith("Signature=34b48302e7b5fa45bde8084f4b7868a86f0a534bc59db6670ed5711ef69dc6f7");
    }

    @Test
    void presignsTheS3QueryParameterExample() {
        URI presigned = S3SigV4Signer.presign("GET", URI.create("https://" + S3_HOST + "/test.txt"), S3_DOC_AT,
                Duration.ofSeconds(86400), "us-east-1", "s3", S3_DOC);

        assertThat(presigned.toString()).isEqualTo("https://" + S3_HOST + "/test.txt"
                + "?X-Amz-Algorithm=AWS4-HMAC-SHA256"
                + "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
                + "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host"
                + "&X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
    }

    @Test
    void refusesPresignedUrlsBeyondSevenDaysAndKeepsTheSecretOutOfText() {
        assertThatThrownBy(() -> S3SigV4Signer.presign("GET", URI.create("https://" + S3_HOST + "/test.txt"),
                S3_DOC_AT, Duration.ofDays(8), "us-east-1", "s3", S3_DOC))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(S3_DOC.toString()).doesNotContain(S3_DOC.secretKey());
        assertThat(S3SigV4Signer.hostHeader(URI.create("http://minio:9000"))).isEqualTo("minio:9000");
        assertThat(S3SigV4Signer.hostHeader(URI.create("https://Files.Example.com"))).isEqualTo("files.example.com");
        assertThat(S3SigV4Signer.encodePath("a b/中.txt")).isEqualTo("a%20b/%E4%B8%AD.txt");
    }
}
