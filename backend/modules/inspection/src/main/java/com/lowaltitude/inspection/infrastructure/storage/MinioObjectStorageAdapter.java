package com.lowaltitude.inspection.infrastructure.storage;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import com.lowaltitude.inspection.domain.files.DownloadObjectRequest;
import com.lowaltitude.inspection.domain.files.FileObjectStatus;
import com.lowaltitude.inspection.domain.files.FilePurpose;
import com.lowaltitude.inspection.domain.files.ObjectStoragePort;
import com.lowaltitude.inspection.domain.files.PresignedObjectIntent;
import com.lowaltitude.inspection.domain.files.UploadObjectRequest;

public final class MinioObjectStorageAdapter implements ObjectStoragePort {

    private static final long MAX_UPLOAD_SIZE_BYTES = 1_073_741_824L;
    private static final Duration MAX_DOWNLOAD_TTL = Duration.ofMinutes(10);

    private final MinioGateway gateway;
    private final MinioStorageProperties properties;
    private final InspectionObjectKeyFactory objectKeyFactory;
    private final Clock clock;

    public MinioObjectStorageAdapter(
            MinioGateway gateway,
            MinioStorageProperties properties,
            InspectionObjectKeyFactory objectKeyFactory,
            Clock clock
    ) {
        this.gateway = gateway;
        this.properties = properties;
        this.objectKeyFactory = objectKeyFactory;
        this.clock = clock;
    }

    @Override
    public PresignedObjectIntent createUploadIntent(UploadObjectRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.sizeBytes() <= 0 || request.sizeBytes() > MAX_UPLOAD_SIZE_BYTES) {
            throw new IllegalArgumentException("upload size must be between 1 and 1073741824 bytes");
        }
        requireText(request.originalName(), "originalName");
        requireText(request.contentType(), "contentType");

        String objectKey;
        if (request.purpose() == FilePurpose.RESERVATION_ATTACHMENT) {
            validateReservationMime(request.contentType());
            objectKey = objectKeyFactory.reservationAttachment(
                    request.providerId(), request.reservationId(), request.fileId());
        } else if (request.purpose() == FilePurpose.DELIVERY_ARTIFACT) {
            validateDeliveryFile(request);
            objectKey = objectKeyFactory.deliveryArtifact(
                    request.providerId(), request.serviceOrderId(), request.deliveryId(), request.fileId());
        } else {
            throw new IllegalArgumentException("unsupported file purpose");
        }
        ensureAllowedKey(objectKey);
        URI url = gateway.presignPut(
                properties.quarantineBucket(),
                objectKey,
                request.contentType(),
                properties.uploadTtl());
        return new PresignedObjectIntent(
                "PUT",
                url,
                Map.of("Content-Type", request.contentType()),
                clock.instant().plus(properties.uploadTtl()));
    }

    @Override
    public PresignedObjectIntent createDownloadIntent(DownloadObjectRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (!properties.privateBucket().equals(request.bucket())) {
            throw new IllegalArgumentException("downloads require the private bucket");
        }
        ensureAllowedKey(request.objectKey());
        if (request.status() != FileObjectStatus.READY) {
            throw new IllegalStateException("only READY objects can be downloaded");
        }
        Duration ttl = Objects.requireNonNull(request.ttl(), "ttl must not be null");
        if (ttl.isZero()
                || ttl.isNegative()
                || ttl.compareTo(MAX_DOWNLOAD_TTL) > 0
                || ttl.compareTo(properties.downloadTtl()) > 0) {
            throw new IllegalArgumentException("download ttl must be positive and not exceed 10 minutes");
        }
        URI url = gateway.presignGet(request.bucket(), request.objectKey(), ttl);
        return new PresignedObjectIntent("GET", url, Map.of(), clock.instant().plus(ttl));
    }

    private void validateReservationMime(String contentType) {
        if (!properties.reservationAllowedMimeTypes().contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("reservation attachment MIME is not allowed");
        }
    }

    private static void validateDeliveryFile(UploadObjectRequest request) {
        if (request.deliveryFileType() == null
                || !request.deliveryFileType().mimeType().equalsIgnoreCase(request.contentType())
                || !request.originalName().toLowerCase(Locale.ROOT)
                        .endsWith(request.deliveryFileType().extension())) {
            throw new IllegalArgumentException("delivery file MIME or extension does not match its type");
        }
    }

    private void ensureAllowedKey(String objectKey) {
        if (!objectKeyFactory.isAllowed(objectKey)) {
            throw new IllegalArgumentException("object key does not match the inspection key policy");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
