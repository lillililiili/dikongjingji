package com.lowaltitude.inspection.infrastructure.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

public interface MinioGateway {

    URI presignPut(String bucket, String objectKey, String contentType, Duration ttl);

    URI presignGet(String bucket, String objectKey, Duration ttl);

    Optional<StoredObjectMetadata> statObjectIfPresent(
            String bucket,
            String objectKey
    );

    InputStream openObject(String bucket, String objectKey);

    void copyObject(
            String sourceBucket,
            String targetBucket,
            String objectKey
    );

    void removeObject(String bucket, String objectKey);
}
