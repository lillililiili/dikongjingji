package com.lowaltitude.inspection.domain.files;

import java.time.Duration;
import java.util.List;

public record BundleDownloadRequest(
        String bucket,
        String objectKey,
        List<BundleObjectSource> sources,
        Duration ttl
) {
}
