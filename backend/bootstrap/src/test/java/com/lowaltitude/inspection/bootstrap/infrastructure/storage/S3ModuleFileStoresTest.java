package com.lowaltitude.inspection.bootstrap.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import com.lowaltitude.inspection.bootstrap.infrastructure.S3ModuleFileStores;
import com.lowaltitude.inspection.identity.application.OnboardingPorts;
import com.lowaltitude.inspection.identity.application.account.AccountPorts;
import com.lowaltitude.inspection.mall.application.MallOpsPorts;
import com.lowaltitude.inspection.ops.media.OpsMediaStore;
import com.lowaltitude.inspection.ops.media.S3OpsMediaStore;
import com.lowaltitude.inspection.opsimport.application.OpsImportPorts;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderPorts;
import com.lowaltitude.inspection.trade.application.TradeAftersaleImageStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 六类文件的端口实现接 S3（S6），对着本机假 S3：各模块写进自己的命名空间（私有的进私有桶，展示用的进公开桶）；
 * 只认模块自己生成的键；公开地址是 https 前缀加对象键；存储出错时报各端口原有的“不可用”。
 */
class S3ModuleFileStoresTest {

    private static final String PRIVATE = "unit-platform-private";
    private static final String PUBLIC = "unit-public-assets";
    private static final String BASE = "https://files.example.test/unit-public-assets";
    private static final String UUID_1 = "0b6f3c1e-1d2a-4c3b-8a9f-1234567890ab";
    private static final String UUID_2 = "7d1e2f3a-4b5c-4d6e-8f70-abcdefabcdef";

    private final S3ObjectClientTest.MutableClock clock =
            new S3ObjectClientTest.MutableClock(Instant.parse("2026-11-23T08:00:00Z"));
    private FakeS3Server server;
    private PlatformStorage storage;

    @BeforeEach
    void start() throws IOException {
        server = new FakeS3Server(S3ObjectClientTest.CREDENTIALS, "us-east-1", clock);
        S3ObjectClient client = new S3ObjectClient(new S3ObjectClient.Settings(server.endpoint(), "us-east-1",
                S3ObjectClientTest.CREDENTIALS, true, Duration.ofSeconds(5)),
                S3ObjectClient.httpClient(Duration.ofSeconds(2)), clock);
        storage = PlatformStorage.s3(client, PRIVATE, PUBLIC, BASE + "/");
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void privateFilesGoToTheirModuleNamespaceInThePrivateBucket() {
        byte[] content = "pdf".getBytes(StandardCharsets.UTF_8);

        OnboardingPorts.QualificationFiles qualification = new S3ModuleFileStores.QualificationFiles(storage);
        String qualificationKey = "organizations/" + UUID_1 + "/qualification/" + UUID_2;
        qualification.put(qualificationKey, content);
        assertThat(server.object(PRIVATE, "identity/" + qualificationKey)).isEqualTo(content);
        assertThat(qualification.read(qualificationKey)).isEqualTo(content);

        ServiceOrderPorts.Files orders = new S3ModuleFileStores.ServiceOrderFiles(storage);
        String orderKey = "orders/" + UUID_1 + "/files/" + UUID_2;
        orders.put(orderKey, content);
        assertThat(server.object(PRIVATE, "serviceorder/" + orderKey)).isEqualTo(content);
        assertThat(orders.read(orderKey)).isEqualTo(content);
        orders.delete(orderKey);
        assertThat(server.object(PRIVATE, "serviceorder/" + orderKey)).isNull();
        assertThatThrownBy(() -> orders.read(orderKey))
                .isInstanceOf(ServiceOrderPorts.FileStoreUnavailableException.class);

        OpsImportPorts.FileStore imports = new S3ModuleFileStores.ImportFiles(storage);
        String importKey = "imports/" + UUID_1 + "/3.csv";
        imports.put(importKey, content);
        assertThat(server.object(PRIVATE, "opsimport/" + importKey)).isEqualTo(content);
        assertThat(server.contentType(PRIVATE, "opsimport/" + importKey)).isEqualTo("text/csv");
        assertThat(imports.read(importKey)).isEqualTo(content);

        TradeAftersaleImageStore aftersale = new S3ModuleFileStores.TradeAftersaleImages(storage);
        String imageKey = "aftersale/" + UUID_1 + ".jpg";
        aftersale.put(imageKey, content, "image/jpeg");
        assertThat(server.object(PRIVATE, "trade/" + imageKey)).isEqualTo(content);
        assertThat(aftersale.read(imageKey)).contains(content);
        assertThat(aftersale.read("aftersale/" + UUID_2 + ".jpg")).isEmpty();
        assertThat(aftersale.read("../trade/" + imageKey)).isEmpty();

        assertThat(server.object(PUBLIC, "identity/" + qualificationKey)).isNull();
    }

    @Test
    void displayMediaGoesToThePublicBucketWithAControlledHttpsAddress() {
        byte[] image = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};

        MallOpsPorts.MediaStore mall = new S3ModuleFileStores.MallMedia(storage);
        String productKey = "products/" + UUID_1 + ".jpg";
        mall.put(productKey, image, "image/jpeg");
        String url = mall.publicUrl(productKey);
        assertThat(server.object(PUBLIC, "mall/" + productKey)).isEqualTo(image);
        assertThat(server.contentType(PUBLIC, "mall/" + productKey)).isEqualTo("image/jpeg");
        assertThat(url).isEqualTo(BASE + "/mall/" + productKey);
        assertThat(mall.isControlled(url)).isTrue();
        assertThat(mall.isControlled(BASE + "/ops/" + productKey)).isFalse();
        assertThat(mall.isControlled(BASE + "/mall/products/../x.jpg")).isFalse();
        assertThat(mall.isControlled("https://elsewhere.example.test/mall/" + productKey)).isFalse();

        OpsMediaStore ops = new S3OpsMediaStore(storage);
        String mediaKey = "ops-media/" + UUID_2 + ".mp4";
        ops.put(mediaKey, image, "video/mp4");
        assertThat(ops.publicUrl(mediaKey)).isEqualTo(BASE + "/ops/" + mediaKey);
        assertThat(ops.read(mediaKey)).isEqualTo(image);
        assertThat(ops.read("ops-media/" + UUID_1 + ".jpg")).isNull();
        assertThat(server.object(PRIVATE, "ops/" + mediaKey)).isNull();
    }

    @Test
    void onlyAcceptsKeysTheModulesGenerate() {
        assertThatThrownBy(() -> new S3ModuleFileStores.QualificationFiles(storage).put("../secrets", new byte[1]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new S3ModuleFileStores.ImportFiles(storage).read("imports/x.csv"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new S3ModuleFileStores.MallMedia(storage).publicUrl("products/a.exe"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new S3OpsMediaStore(storage).put("ops-media/a.html", new byte[1], "text/html"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.requests()).isEmpty();
    }

    @Test
    void storageFailuresBecomeEachPortsUnavailableError() {
        server.failWith(503);
        byte[] content = {1};

        assertThatThrownBy(() -> new S3ModuleFileStores.QualificationFiles(storage)
                .put("organizations/" + UUID_1 + "/qualification/" + UUID_2, content))
                .isInstanceOf(OnboardingPorts.FileStoreUnavailableException.class);
        assertThatThrownBy(() -> new S3ModuleFileStores.ServiceOrderFiles(storage)
                .delete("orders/" + UUID_1 + "/files/" + UUID_2))
                .isInstanceOf(ServiceOrderPorts.FileStoreUnavailableException.class);
        assertThatThrownBy(() -> new S3ModuleFileStores.ImportFiles(storage).put("imports/" + UUID_1 + "/1.csv", content))
                .isInstanceOf(OpsImportPorts.FileStoreUnavailableException.class);
        assertThatThrownBy(() -> new S3ModuleFileStores.TradeAftersaleImages(storage)
                .put("aftersale/" + UUID_1 + ".png", content, "image/png"))
                .isInstanceOf(TradeAftersaleImageStore.UnavailableException.class);
        assertThat(new S3ModuleFileStores.TradeAftersaleImages(storage).read("aftersale/" + UUID_1 + ".png")).isEmpty();
        assertThatThrownBy(() -> new S3ModuleFileStores.MallMedia(storage)
                .put("products/" + UUID_1 + ".png", content, "image/png"))
                .isInstanceOf(MallOpsPorts.MediaStoreUnavailableException.class);
        assertThatThrownBy(() -> new S3OpsMediaStore(storage).read("ops-media/" + UUID_1 + ".png"))
                .isInstanceOf(OpsMediaStore.UnavailableException.class);
    }

    @Test
    void personalDataExportsArePrivateZipsAndExpiryCanDeleteThemTwice() {
        var exports = new S3ModuleFileStores.DataExports(storage);
        String key = "data-exports/" + UUID_1 + "/" + UUID_2 + ".zip";
        byte[] content = {80, 75, 3, 4};
        exports.put(key, content);
        assertThat(exports.read(key)).isEqualTo(content);
        assertThat(server.object(PRIVATE, "identity/" + key)).isEqualTo(content);
        assertThat(server.contentType(PRIVATE, "identity/" + key)).isEqualTo("application/zip");
        assertThat(server.object(PUBLIC, "identity/" + key)).isNull();
        exports.delete(key);
        exports.delete(key);
        assertThatThrownBy(() -> exports.read(key)).isInstanceOf(AccountPorts.ExportStoreUnavailableException.class);
        assertThatThrownBy(() -> exports.read("../qualification/" + UUID_2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void personalDataExportsReportS3FailuresToTheExistingAccountPort() {
        var exports = new S3ModuleFileStores.DataExports(storage);
        String key = "data-exports/" + UUID_1 + "/" + UUID_2 + ".zip";
        server.failWith(503);
        assertThatThrownBy(() -> exports.put(key, new byte[1]))
                .isInstanceOf(AccountPorts.ExportStoreUnavailableException.class);
        assertThatThrownBy(() -> exports.read(key)).isInstanceOf(AccountPorts.ExportStoreUnavailableException.class);
        assertThatThrownBy(() -> exports.delete(key)).isInstanceOf(AccountPorts.ExportStoreUnavailableException.class);
    }

    @Test
    void localStorageHasNoAreas() {
        assertThat(PlatformStorage.local().isS3()).isFalse();
        assertThatThrownBy(() -> PlatformStorage.local().privateArea("identity"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PlatformStorage.s3(
                new S3ObjectClient(new S3ObjectClient.Settings(server.endpoint(), "us-east-1",
                        S3ObjectClientTest.CREDENTIALS, true, null), S3ObjectClient.httpClient(null), clock),
                PRIVATE, PUBLIC, "http://files.example.test"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
