package com.lowaltitude.inspection.infrastructure.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.lowaltitude.inspection.domain.files.DownloadObjectRequest;
import com.lowaltitude.inspection.domain.files.FileObjectStatus;
import com.lowaltitude.inspection.domain.files.FilePurpose;
import com.lowaltitude.inspection.domain.files.ObjectStoragePort;
import com.lowaltitude.inspection.domain.files.PreparedObjectUpload;
import com.lowaltitude.inspection.domain.files.PresignedObjectIntent;
import com.lowaltitude.inspection.domain.files.UploadObjectRequest;
import com.lowaltitude.inspection.domain.files.UploadVerificationResult;

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
    public PreparedObjectUpload createUploadIntent(UploadObjectRequest request) {
        String objectKey = validatedObjectKey(request);
        URI url = gateway.presignPut(
                properties.quarantineBucket(),
                objectKey,
                request.contentType(),
                properties.uploadTtl());
        return new PreparedObjectUpload(
                properties.quarantineBucket(),
                properties.privateBucket(),
                objectKey,
                new PresignedObjectIntent(
                        "PUT",
                        url,
                        Map.of("Content-Type", request.contentType()),
                        clock.instant().plus(properties.uploadTtl())));
    }

    @Override
    public UploadVerificationResult verifyUpload(UploadObjectRequest request) {
        String objectKey = validatedObjectKey(request);
        String quarantineBucket = properties.quarantineBucket();
        Optional<StoredObjectMetadata> quarantine =
                gateway.statObjectIfPresent(quarantineBucket, objectKey);
        if (quarantine.isEmpty()) {
            String privateBucket = properties.privateBucket();
            StoredObjectMetadata promoted = gateway
                    .statObjectIfPresent(privateBucket, objectKey)
                    .orElseThrow(() -> new ObjectStorageException(
                            "Uploaded object is missing from quarantine and private storage"));
            return metadataMatches(request, privateBucket, objectKey, promoted)
                    && contentMatches(request, privateBucket, objectKey)
                    ? UploadVerificationResult.VERIFIED
                    : UploadVerificationResult.REJECTED;
        }
        StoredObjectMetadata actual = quarantine.get();
        boolean verified = metadataMatches(
                request,
                quarantineBucket,
                objectKey,
                actual);
        verified = verified && contentMatches(request, quarantineBucket, objectKey);
        if (!verified) {
            gateway.removeObject(quarantineBucket, objectKey);
            return UploadVerificationResult.REJECTED;
        }
        gateway.copyObject(
                quarantineBucket,
                properties.privateBucket(),
                objectKey);
        gateway.removeObject(quarantineBucket, objectKey);
        return UploadVerificationResult.VERIFIED;
    }

    private static boolean metadataMatches(
            UploadObjectRequest request,
            String bucket,
            String objectKey,
            StoredObjectMetadata actual
    ) {
        return bucket.equals(actual.bucket())
                && objectKey.equals(actual.objectKey())
                && actual.sizeBytes() > 0
                && actual.sizeBytes() <= MAX_UPLOAD_SIZE_BYTES
                && actual.sizeBytes() == request.sizeBytes()
                && request.contentType().equalsIgnoreCase(actual.contentType());
    }

    private boolean contentMatches(
            UploadObjectRequest request,
            String bucket,
            String objectKey
    ) {
        if (request.sha256() == null
                || !request.sha256().matches("^[a-f0-9]{64}$")) {
            return false;
        }
        try (InputStream input = gateway.openObject(bucket, objectKey)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8_192];
            byte[] prefix = new byte[8];
            int prefixLength = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                if (prefixLength < prefix.length) {
                    int copyLength = Math.min(read, prefix.length - prefixLength);
                    System.arraycopy(buffer, 0, prefix, prefixLength, copyLength);
                    prefixLength += copyLength;
                }
                digest.update(buffer, 0, read);
            }
            return request.sha256().equals(HexFormat.of().formatHex(digest.digest()))
                    && validMagic(
                            request.contentType().toLowerCase(Locale.ROOT),
                            Arrays.copyOf(prefix, prefixLength));
        } catch (IOException exception) {
            throw new ObjectStorageException("Unable to verify uploaded object", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean validMagic(String contentType, byte[] prefix) {
        return switch (contentType) {
            case "image/jpeg" -> startsWith(prefix, new byte[] {
                    (byte) 0xff, (byte) 0xd8, (byte) 0xff
            });
            case "image/png" -> startsWith(prefix, new byte[] {
                    (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
            });
            case "application/pdf" -> startsWith(
                    prefix,
                    "%PDF-".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            case "application/zip",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                    startsWith(prefix, new byte[] {0x50, 0x4b, 0x03, 0x04});
            case "application/vnd.google-earth.kml+xml" ->
                    startsWith(prefix, "<?xml".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                            || startsWith(prefix, "<kml".getBytes(
                                    java.nio.charset.StandardCharsets.US_ASCII));
            default -> false;
        };
    }

    private static boolean startsWith(byte[] value, byte[] expected) {
        if (value.length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (value[index] != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private String validatedObjectKey(UploadObjectRequest request) {
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
        return objectKey;
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
