package com.lowaltitude.inspection.bootstrap.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 平台文件存储配置自检（S6）：local 什么都不查；s3 一次列出全部缺项、写明改哪个环境变量，消息里不带密钥；
 * 有 FAIL 时后端起不来。账号和密钥都是现编的。
 */
class PlatformStoragePreflightTest {

    private static final String SECRET = "unit-secret-0123456789";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PlatformStorageConfiguration.class);

    private static PlatformStorageProperties s3(String endpoint, String accessKey, String secretKey,
                                                String privateBucket, String publicBucket, String publicBaseUrl) {
        return new PlatformStorageProperties("s3", new PlatformStorageProperties.S3(
                endpoint == null ? null : URI.create(endpoint), null, accessKey, secretKey, privateBucket,
                publicBucket, publicBaseUrl == null ? null : URI.create(publicBaseUrl), null, null, null));
    }

    private static Map<String, String> statuses(List<PlatformStoragePreflight.Item> items) {
        return items.stream().collect(Collectors.toMap(PlatformStoragePreflight.Item::code,
                PlatformStoragePreflight.Item::status));
    }

    @Test
    void localNeedsNothing() {
        List<PlatformStoragePreflight.Item> items = PlatformStoragePreflight.requireStartable(
                new PlatformStorageProperties(null, null));

        assertThat(items).isEmpty();
        assertThat(statuses(PlatformStoragePreflight.inspect(new PlatformStorageProperties("LOCAL", null))))
                .containsEntry("PROVIDER", "PASS");
    }

    @Test
    void aCompleteS3ConfigurationPasses() {
        PlatformStorageProperties properties = s3("http://minio:9000", "access", SECRET, "prod-platform-private",
                "prod-public-assets", "https://files.example.test/prod-public-assets");

        assertThat(PlatformStoragePreflight.inspect(properties))
                .allSatisfy(item -> assertThat(item.status()).isEqualTo("PASS"));
        assertThat(PlatformStoragePreflight.requireStartable(properties)).isEmpty();
    }

    @Test
    void listsEveryMissingItemWithItsVariableAndNoSecret() {
        PlatformStorageProperties properties = s3(null, " ", null, null, null, null);

        assertThat(statuses(PlatformStoragePreflight.inspect(properties)))
                .containsEntry("PROVIDER", "PASS")
                .containsEntry("ENDPOINT", "FAIL")
                .containsEntry("REGION", "PASS")
                .containsEntry("ACCESS_KEY", "FAIL")
                .containsEntry("SECRET_KEY", "FAIL")
                .containsEntry("PRIVATE_BUCKET", "FAIL")
                .containsEntry("PUBLIC_BUCKET", "FAIL")
                .containsEntry("PUBLIC_BASE_URL", "FAIL");
        assertThatThrownBy(() -> PlatformStoragePreflight.requireStartable(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("共 6 项")
                .hasMessageContaining("PLATFORM_STORAGE_S3_ENDPOINT")
                .hasMessageContaining("PLATFORM_STORAGE_S3_ACCESS_KEY")
                .hasMessageContaining("PLATFORM_STORAGE_S3_SECRET_KEY")
                .hasMessageContaining("PLATFORM_STORAGE_S3_PRIVATE_BUCKET")
                .hasMessageContaining("PLATFORM_STORAGE_S3_PUBLIC_BUCKET")
                .hasMessageContaining("PLATFORM_STORAGE_S3_PUBLIC_BASE_URL");
    }

    @Test
    void rejectsUnsafeValues() {
        PlatformStorageProperties properties = s3("http://minio:9000/bucket?x=1", "access", "short",
                "Same_Bucket", "same-bucket", "http://files.example.test/public");

        Map<String, String> statuses = statuses(PlatformStoragePreflight.inspect(properties));
        assertThat(statuses).containsEntry("ENDPOINT", "FAIL").containsEntry("SECRET_KEY", "FAIL")
                .containsEntry("PRIVATE_BUCKET", "FAIL").containsEntry("PUBLIC_BASE_URL", "FAIL");

        PlatformStorageProperties sameBucket = s3("http://minio:9000", "access", SECRET, "same-bucket",
                "same-bucket", "https://files.example.test/same-bucket");
        assertThat(statuses(PlatformStoragePreflight.inspect(sameBucket))).containsEntry("PUBLIC_BUCKET", "FAIL");

        assertThatThrownBy(() -> PlatformStoragePreflight.requireStartable(
                new PlatformStorageProperties("oss", null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PLATFORM_STORAGE_PROVIDER");
        assertThatThrownBy(() -> PlatformStoragePreflight.requireStartable(sameBucket))
                .hasMessageNotContaining(SECRET);
        assertThat(sameBucket.s3().toString()).doesNotContain(SECRET).doesNotContain("access,");
    }

    @Test
    void startupUsesLocalByDefaultAndFailsOnAnIncompleteS3Configuration() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PlatformStorage.class).isS3()).isFalse();
        });

        runner.withPropertyValues("platform.storage.provider=s3",
                        "platform.storage.s3.endpoint=http://minio:9000",
                        "platform.storage.s3.access-key=access",
                        "platform.storage.s3.secret-key=" + SECRET)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("PLATFORM_STORAGE_S3_PRIVATE_BUCKET")
                            .hasMessageContaining("PLATFORM_STORAGE_S3_PUBLIC_BASE_URL")
                            .hasMessageNotContaining(SECRET);
                });

        runner.withPropertyValues("platform.storage.provider=s3",
                        "platform.storage.s3.endpoint=http://minio:9000",
                        "platform.storage.s3.access-key=access",
                        "platform.storage.s3.secret-key=" + SECRET,
                        "platform.storage.s3.private-bucket=prod-platform-private",
                        "platform.storage.s3.public-bucket=prod-public-assets",
                        "platform.storage.s3.public-base-url=https://files.example.test/prod-public-assets")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(PlatformStorage.class).isS3()).isTrue();
                });
    }
}
