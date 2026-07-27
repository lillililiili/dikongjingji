package com.lowaltitude.inspection.infrastructure.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;

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

    @Override
    public StoredObjectMetadata statObject(String bucket, String objectKey) {
        try {
            StatObjectResponse response = client.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return new StoredObjectMetadata(
                    bucket,
                    objectKey,
                    response.size(),
                    response.contentType());
        } catch (Exception exception) {
            throw new ObjectStorageException("Unable to inspect uploaded object", exception);
        }
    }

    @Override
    public void removeObject(String bucket, String objectKey) {
        try {
            client.removeObject(
                    RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
        } catch (Exception exception) {
            throw new ObjectStorageException("Unable to remove rejected object", exception);
        }
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
