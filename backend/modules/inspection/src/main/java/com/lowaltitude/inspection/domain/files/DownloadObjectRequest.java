package com.lowaltitude.inspection.domain.files;

import java.time.Duration;

public record DownloadObjectRequest(
        String bucket,
        String objectKey,
        FileObjectStatus status,
        Duration ttl
) {
}
