package com.lowaltitude.inspection.infrastructure.storage;

import java.net.URI;
import java.time.Duration;

public interface MinioGateway {

    URI presignPut(String bucket, String objectKey, String contentType, Duration ttl);

    URI presignGet(String bucket, String objectKey, Duration ttl);
}
