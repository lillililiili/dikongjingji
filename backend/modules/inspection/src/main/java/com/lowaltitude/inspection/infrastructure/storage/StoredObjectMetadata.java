package com.lowaltitude.inspection.infrastructure.storage;

public record StoredObjectMetadata(
        String bucket,
        String objectKey,
        long sizeBytes,
        String contentType
) {
}
