package com.lowaltitude.inspection.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.domain.files.DeliveryFileType;
import com.lowaltitude.inspection.domain.files.DownloadObjectRequest;
import com.lowaltitude.inspection.domain.files.FileObjectStatus;
import com.lowaltitude.inspection.domain.files.PresignedObjectIntent;
import com.lowaltitude.inspection.domain.files.UploadObjectRequest;
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

        PresignedObjectIntent intent = adapter.createUploadIntent(valid);

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
                "prod", URI.create("https://minio.internal"), "access", "secret",
                Duration.ofMinutes(5), Duration.ofMinutes(10), reservationMimes);
    }

    private static final class RecordingMinioGateway implements MinioGateway {

        private String bucket;
        private String objectKey;
        private String contentType;

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
            return URI.create("https://storage.test/download");
        }
    }
}
