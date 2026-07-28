package com.lowaltitude.inspection.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URLDecoder;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.domain.files.BundleDownloadRequest;
import com.lowaltitude.inspection.domain.files.BundleObjectSource;
import com.lowaltitude.inspection.domain.files.DeliveryFileType;
import com.lowaltitude.inspection.domain.files.DownloadObjectRequest;
import com.lowaltitude.inspection.domain.files.FileObjectStatus;
import com.lowaltitude.inspection.domain.files.PresignedObjectIntent;
import com.lowaltitude.inspection.domain.files.PreparedObjectUpload;
import com.lowaltitude.inspection.domain.files.UploadObjectRequest;
import com.lowaltitude.inspection.domain.files.UploadVerificationResult;
import io.minio.MinioClient;
import org.junit.jupiter.api.Test;

class InspectionObjectStorageTest {

    private static final UUID PROVIDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RESERVATION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID DELIVERY_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID FILE_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final Instant NOW = Instant.parse("2026-07-28T02:00:00Z");

    @Test
    void generatesStableObjectKeysWithoutOriginalNames() {
        InspectionObjectKeyFactory factory = new InspectionObjectKeyFactory();

        assertThat(factory.reservationAttachment(PROVIDER_ID, RESERVATION_ID, FILE_ID))
                .isEqualTo("tenant/" + PROVIDER_ID + "/reservation/" + RESERVATION_ID + "/" + FILE_ID);
        assertThat(factory.deliveryArtifact(PROVIDER_ID, ORDER_ID, DELIVERY_ID, FILE_ID))
                .isEqualTo("tenant/" + PROVIDER_ID + "/order/" + ORDER_ID + "/delivery/" + DELIVERY_ID + "/" + FILE_ID);
        assertThat(factory.deliveryBundle(PROVIDER_ID, ORDER_ID, DELIVERY_ID))
                .isEqualTo("tenant/" + PROVIDER_ID + "/order/" + ORDER_ID + "/bundle/" + DELIVERY_ID + ".zip");
    }

    @Test
    void reservationMimePolicyFailsClosedByDefault() {
        MinioObjectStorageAdapter adapter = adapter(properties(Set.of()));

        UploadObjectRequest request = UploadObjectRequest.reservationAttachment(
                PROVIDER_ID, RESERVATION_ID, FILE_ID, "photo.jpg", "image/jpeg", 100);

        assertThatThrownBy(() -> adapter.createUploadIntent(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME");
    }

    @Test
    void deliveryUploadUsesQuarantineAndEnforcesMimeAndMaximumSize() {
        RecordingMinioGateway gateway = new RecordingMinioGateway();
        MinioObjectStorageAdapter adapter = adapter(properties(Set.of()), gateway);
        UploadObjectRequest valid = UploadObjectRequest.deliveryArtifact(
                PROVIDER_ID, ORDER_ID, DELIVERY_ID, FILE_ID,
                DeliveryFileType.INSPECTION_REPORT, "report.pdf", "application/pdf", 1_073_741_824L);

        PreparedObjectUpload prepared = adapter.createUploadIntent(valid);
        PresignedObjectIntent intent = prepared.intent();

        assertThat(gateway.bucket).isEqualTo("prod-inspection-quarantine");
        assertThat(gateway.objectKey).isEqualTo(
                "tenant/" + PROVIDER_ID + "/order/" + ORDER_ID + "/delivery/" + DELIVERY_ID + "/" + FILE_ID);
        assertThat(gateway.contentType).isEqualTo("application/pdf");
        assertThat(intent.method()).isEqualTo("PUT");
        assertThat(intent.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThatThrownBy(() -> adapter.createUploadIntent(valid.withSizeBytes(1_073_741_825L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("size");
        assertThatThrownBy(() -> adapter.createUploadIntent(valid.withContentType("application/zip")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME");
    }

    @Test
    void verifiesCompletedUploadAndDeletesRejectedQuarantineObjects() {
        RecordingMinioGateway gateway = new RecordingMinioGateway();
        MinioObjectStorageAdapter adapter = adapter(properties(Set.of()), gateway);
        UploadObjectRequest request = UploadObjectRequest.deliveryArtifact(
                PROVIDER_ID, ORDER_ID, DELIVERY_ID, FILE_ID,
                DeliveryFileType.INSPECTION_REPORT, "report.pdf", "application/pdf", 123L);
        byte[] content = pdfContent(123);
        request = request.withSha256(sha256(content));
        String key = new InspectionObjectKeyFactory().deliveryArtifact(
                PROVIDER_ID, ORDER_ID, DELIVERY_ID, FILE_ID);

        gateway.content = content;
        gateway.metadata = new StoredObjectMetadata(
                "prod-inspection-quarantine", key, 123L, "application/pdf");
        assertThat(adapter.verifyUpload(request)).isEqualTo(UploadVerificationResult.VERIFIED);
        assertThat(gateway.copiedKeys).containsExactly(key);
        assertThat(gateway.deletedKeys).containsExactly(key);
        assertThat(adapter.verifyUpload(request)).isEqualTo(UploadVerificationResult.VERIFIED);
        assertThat(gateway.copiedKeys).containsExactly(key);
        assertThat(gateway.deletedKeys).containsExactly(key);

        List<StoredObjectMetadata> rejectedObjects = List.of(
                new StoredObjectMetadata("prod-inspection-quarantine", key, 124L, "application/pdf"),
                new StoredObjectMetadata("prod-inspection-quarantine", key, 1_073_741_825L, "application/pdf"),
                new StoredObjectMetadata("prod-inspection-quarantine", key, 123L, "application/zip"));
        for (StoredObjectMetadata rejected : rejectedObjects) {
            gateway.metadata = rejected;
            assertThat(adapter.verifyUpload(request)).isEqualTo(UploadVerificationResult.REJECTED);
        }
        assertThat(gateway.deletedKeys).containsExactly(key, key, key, key);
    }

    @Test
    void minioNinePutPresignDoesNotSignExtraHeaders() {
        MinioClient internalClient = MinioClient.builder()
                .endpoint("https://storage.internal.example.test")
                .credentials("access-key", "secret-key")
                .region("us-east-1")
                .build();
        MinioClient publicClient = MinioClient.builder()
                .endpoint("https://storage.public.example.test")
                .credentials("access-key", "secret-key")
                .region("us-east-1")
                .build();

        URI url = new SdkMinioGateway(internalClient, publicClient).presignPut(
                "prod-inspection-quarantine",
                "tenant/" + PROVIDER_ID + "/reservation/" + RESERVATION_ID + "/" + FILE_ID,
                "application/pdf",
                Duration.ofMinutes(5));

        assertThat(url.getHost()).isEqualTo("storage.public.example.test");
        assertThat(URLDecoder.decode(url.getRawQuery(), StandardCharsets.UTF_8))
                .contains("X-Amz-SignedHeaders=host")
                .doesNotContain("content-length");
    }

    @Test
    void downloadRequiresPrivateReadyObjectAndAtMostTenMinutes() {
        RecordingMinioGateway gateway = new RecordingMinioGateway();
        MinioObjectStorageAdapter adapter = adapter(properties(Set.of()), gateway);
        String key = new InspectionObjectKeyFactory().deliveryArtifact(
                PROVIDER_ID, ORDER_ID, DELIVERY_ID, FILE_ID);
        DownloadObjectRequest valid = new DownloadObjectRequest(
                "prod-inspection-private", key, FileObjectStatus.READY, Duration.ofMinutes(10));

        PresignedObjectIntent intent = adapter.createDownloadIntent(valid);

        assertThat(intent.method()).isEqualTo("GET");
        assertThat(intent.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
        assertThatThrownBy(() -> adapter.createDownloadIntent(
                new DownloadObjectRequest("prod-inspection-quarantine", key, FileObjectStatus.READY,
                        Duration.ofMinutes(1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.createDownloadIntent(
                new DownloadObjectRequest("prod-inspection-private", key, FileObjectStatus.UPLOADED,
                        Duration.ofMinutes(1))))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> adapter.createDownloadIntent(
                new DownloadObjectRequest("prod-inspection-private", key, FileObjectStatus.READY,
                        Duration.ofMinutes(11))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10");
    }

    @Test
    void bundleUsesPrivateReadySourcesSafeUniqueNamesAndShortPresign() {
        RecordingMinioGateway gateway = new RecordingMinioGateway();
        MinioObjectStorageAdapter adapter = adapter(properties(Set.of()), gateway);
        InspectionObjectKeyFactory keyFactory = new InspectionObjectKeyFactory();
        UUID secondFileId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        String bundleKey = keyFactory.deliveryBundle(PROVIDER_ID, ORDER_ID, DELIVERY_ID);
        String firstSourceKey = keyFactory.deliveryArtifact(
                PROVIDER_ID, ORDER_ID, DELIVERY_ID, FILE_ID);
        String secondSourceKey = keyFactory.deliveryArtifact(
                PROVIDER_ID, ORDER_ID, DELIVERY_ID, secondFileId);
        List<BundleObjectSource> sources = List.of(
                new BundleObjectSource(
                        "prod-inspection-private", firstSourceKey,
                        FileObjectStatus.READY, "巡检报告.pdf"),
                new BundleObjectSource(
                        "prod-inspection-private", secondSourceKey,
                        FileObjectStatus.READY, "缺陷清单.xlsx"));

        PresignedObjectIntent intent = adapter.createBundleDownloadIntent(
                new BundleDownloadRequest(
                        "prod-inspection-private", bundleKey, sources, Duration.ofMinutes(5)));

        assertThat(gateway.zipBucket).isEqualTo("prod-inspection-private");
        assertThat(gateway.zipObjectKey).isEqualTo(bundleKey);
        assertThat(gateway.zipEntries).containsExactly(
                new ZipBundleEntry(firstSourceKey, "巡检报告.pdf"),
                new ZipBundleEntry(secondSourceKey, "缺陷清单.xlsx"));
        assertThat(gateway.presignGetTtl).isEqualTo(Duration.ofMinutes(5));
        assertThat(intent.method()).isEqualTo("GET");
        assertThat(intent.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
    }

    @Test
    void bundleRejectsNonPrivateNonReadyUnsafeOrDuplicatedSources() {
        MinioObjectStorageAdapter adapter = adapter(properties(Set.of()));
        InspectionObjectKeyFactory keyFactory = new InspectionObjectKeyFactory();
        UUID secondFileId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        String bundleKey = keyFactory.deliveryBundle(PROVIDER_ID, ORDER_ID, DELIVERY_ID);
        String firstSourceKey = keyFactory.deliveryArtifact(
                PROVIDER_ID, ORDER_ID, DELIVERY_ID, FILE_ID);
        String secondSourceKey = keyFactory.deliveryArtifact(
                PROVIDER_ID, ORDER_ID, DELIVERY_ID, secondFileId);

        assertThatThrownBy(() -> adapter.createBundleDownloadIntent(
                bundleRequest(
                        bundleKey,
                        new BundleObjectSource(
                                "prod-inspection-quarantine", firstSourceKey,
                                FileObjectStatus.READY, "report.pdf"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private READY");
        assertThatThrownBy(() -> adapter.createBundleDownloadIntent(
                bundleRequest(
                        bundleKey,
                        new BundleObjectSource(
                                "prod-inspection-private", firstSourceKey,
                                FileObjectStatus.UPLOADED, "report.pdf"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("private READY");
        assertThatThrownBy(() -> adapter.createBundleDownloadIntent(
                bundleRequest(
                        bundleKey,
                        new BundleObjectSource(
                                "prod-inspection-private", firstSourceKey,
                                FileObjectStatus.READY, "../report.pdf"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe or duplicated");
        assertThatThrownBy(() -> adapter.createBundleDownloadIntent(
                new BundleDownloadRequest(
                        "prod-inspection-private",
                        bundleKey,
                        List.of(
                                new BundleObjectSource(
                                        "prod-inspection-private", firstSourceKey,
                                        FileObjectStatus.READY, "report.pdf"),
                                new BundleObjectSource(
                                        "prod-inspection-private", secondSourceKey,
                                        FileObjectStatus.READY, "report.pdf")),
                        Duration.ofMinutes(5))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe or duplicated");
        assertThatThrownBy(() -> adapter.createBundleDownloadIntent(
                new BundleDownloadRequest(
                        "prod-inspection-private",
                        firstSourceKey,
                        List.of(new BundleObjectSource(
                                "prod-inspection-private", firstSourceKey,
                                FileObjectStatus.READY, "report.pdf")),
                        Duration.ofMinutes(5))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BundleDownloadRequest bundleRequest(
            String bundleKey,
            BundleObjectSource source
    ) {
        return new BundleDownloadRequest(
                "prod-inspection-private", bundleKey, List.of(source), Duration.ofMinutes(5));
    }

    private static MinioObjectStorageAdapter adapter(MinioStorageProperties properties) {
        return adapter(properties, new RecordingMinioGateway());
    }

    private static MinioObjectStorageAdapter adapter(
            MinioStorageProperties properties,
            RecordingMinioGateway gateway
    ) {
        return new MinioObjectStorageAdapter(
                gateway, properties, new InspectionObjectKeyFactory(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static MinioStorageProperties properties(Set<String> reservationMimes) {
        return new MinioStorageProperties(
                "prod", URI.create("https://minio.internal"), URI.create("https://minio.public"),
                "us-east-1", "access", "secret",
                Duration.ofMinutes(5), Duration.ofMinutes(10), reservationMimes);
    }

    private static byte[] pdfContent(int size) {
        byte[] content = new byte[size];
        byte[] prefix = "%PDF-1.7".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, content, 0, prefix.length);
        return content;
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class RecordingMinioGateway implements MinioGateway {

        private String bucket;
        private String objectKey;
        private String contentType;
        private StoredObjectMetadata metadata;
        private StoredObjectMetadata privateMetadata;
        private byte[] content = new byte[0];
        private final List<String> deletedKeys = new ArrayList<>();
        private final List<String> copiedKeys = new ArrayList<>();
        private String zipBucket;
        private String zipObjectKey;
        private List<ZipBundleEntry> zipEntries = List.of();
        private Duration presignGetTtl;

        @Override
        public URI presignPut(String bucket, String objectKey, String contentType, Duration ttl) {
            this.bucket = bucket;
            this.objectKey = objectKey;
            this.contentType = contentType;
            return URI.create("https://storage.test/upload");
        }

        @Override
        public URI presignGet(String bucket, String objectKey, Duration ttl) {
            this.bucket = bucket;
            this.objectKey = objectKey;
            this.presignGetTtl = ttl;
            return URI.create("https://storage.test/download");
        }

        @Override
        public Optional<StoredObjectMetadata> statObjectIfPresent(
                String bucket,
                String objectKey
        ) {
            this.bucket = bucket;
            this.objectKey = objectKey;
            if (metadata != null && metadata.bucket().equals(bucket)) {
                return Optional.of(metadata);
            }
            return Optional.ofNullable(privateMetadata)
                    .filter(value -> value.bucket().equals(bucket));
        }

        @Override
        public InputStream openObject(String bucket, String objectKey) {
            this.bucket = bucket;
            this.objectKey = objectKey;
            return new ByteArrayInputStream(content);
        }

        @Override
        public void copyObject(
                String sourceBucket,
                String targetBucket,
                String objectKey
        ) {
            this.bucket = targetBucket;
            this.objectKey = objectKey;
            privateMetadata = new StoredObjectMetadata(
                    targetBucket,
                    objectKey,
                    metadata.sizeBytes(),
                    metadata.contentType());
            copiedKeys.add(objectKey);
        }

        @Override
        public void removeObject(String bucket, String objectKey) {
            this.bucket = bucket;
            if (metadata != null && metadata.bucket().equals(bucket)) {
                metadata = null;
            }
            deletedKeys.add(objectKey);
        }

        @Override
        public void createZipBundle(
                String bucket,
                String objectKey,
                List<ZipBundleEntry> entries
        ) {
            this.zipBucket = bucket;
            this.zipObjectKey = objectKey;
            this.zipEntries = List.copyOf(entries);
        }
    }
}
