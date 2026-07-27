package com.lowaltitude.inspection.domain.files;

import java.util.UUID;

public record UploadObjectRequest(
        FilePurpose purpose,
        UUID providerId,
        UUID reservationId,
        UUID serviceOrderId,
        UUID deliveryId,
        UUID fileId,
        DeliveryFileType deliveryFileType,
        String originalName,
        String contentType,
        long sizeBytes
) {

    public static UploadObjectRequest reservationAttachment(
            UUID providerId,
            UUID reservationId,
            UUID fileId,
            String originalName,
            String contentType,
            long sizeBytes
    ) {
        return new UploadObjectRequest(
                FilePurpose.RESERVATION_ATTACHMENT,
                providerId,
                reservationId,
                null,
                null,
                fileId,
                null,
                originalName,
                contentType,
                sizeBytes);
    }

    public static UploadObjectRequest deliveryArtifact(
            UUID providerId,
            UUID serviceOrderId,
            UUID deliveryId,
            UUID fileId,
            DeliveryFileType deliveryFileType,
            String originalName,
            String contentType,
            long sizeBytes
    ) {
        return new UploadObjectRequest(
                FilePurpose.DELIVERY_ARTIFACT,
                providerId,
                null,
                serviceOrderId,
                deliveryId,
                fileId,
                deliveryFileType,
                originalName,
                contentType,
                sizeBytes);
    }

    public UploadObjectRequest withSizeBytes(long newSizeBytes) {
        return new UploadObjectRequest(
                purpose, providerId, reservationId, serviceOrderId, deliveryId, fileId,
                deliveryFileType, originalName, contentType, newSizeBytes);
    }

    public UploadObjectRequest withContentType(String newContentType) {
        return new UploadObjectRequest(
                purpose, providerId, reservationId, serviceOrderId, deliveryId, fileId,
                deliveryFileType, originalName, newContentType, sizeBytes);
    }
}
