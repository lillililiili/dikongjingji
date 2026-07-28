package com.lowaltitude.inspection.domain.files;

public record BundleObjectSource(
        String bucket,
        String objectKey,
        FileObjectStatus status,
        String archiveName
) {
}
