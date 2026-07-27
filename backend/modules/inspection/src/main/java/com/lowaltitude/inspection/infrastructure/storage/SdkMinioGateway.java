package com.lowaltitude.inspection.infrastructure.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MinioClient;

public final class SdkMinioGateway implements MinioGateway {

    private final MinioClient client;

    public SdkMinioGateway(MinioClient client) {
        this.client = client;
    }

    @Override
    public URI presignPut(String bucket, String objectKey, String contentType, Duration ttl) {
        return presign(
                Method.PUT,
                bucket,
                objectKey,
                ttl,
                Map.of("Content-Type", contentType));
    }

    @Override
    public URI presignGet(String bucket, String objectKey, Duration ttl) {
        return presign(Method.GET, bucket, objectKey, ttl, Map.of());
    }

    private URI presign(
            Method method,
            String bucket,
            String objectKey,
            Duration ttl,
            Map<String, String> headers
    ) {
        try {
            String url = client.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(method)
                            .bucket(bucket)
                            .object(objectKey)
                            .expiry(Math.toIntExact(ttl.toSeconds()))
                            .extraHeaders(headers)
                            .build());
            return URI.create(url);
        } catch (Exception exception) {
            throw new ObjectStorageException("Unable to create object storage intent", exception);
        }
    }
}
