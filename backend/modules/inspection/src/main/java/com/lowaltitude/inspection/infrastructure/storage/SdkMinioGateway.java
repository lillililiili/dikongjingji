package com.lowaltitude.inspection.infrastructure.storage;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import io.minio.CopyObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.SourceObject;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;

public final class SdkMinioGateway implements MinioGateway {

    private final MinioClient internalClient;
    private final MinioClient presignClient;

    public SdkMinioGateway(MinioClient internalClient, MinioClient presignClient) {
        this.internalClient = internalClient;
        this.presignClient = presignClient;
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
    public Optional<StoredObjectMetadata> statObjectIfPresent(
            String bucket,
            String objectKey
    ) {
        try {
            StatObjectResponse response = internalClient.statObject(
                    StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return Optional.of(new StoredObjectMetadata(
                    bucket,
                    objectKey,
                    response.size(),
                    response.contentType()));
        } catch (ErrorResponseException exception) {
            String code = exception.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchObject".equals(code)) {
                return Optional.empty();
            }
            throw new ObjectStorageException("Unable to inspect uploaded object", exception);
        } catch (Exception exception) {
            throw new ObjectStorageException("Unable to inspect uploaded object", exception);
        }
    }

    @Override
    public InputStream openObject(String bucket, String objectKey) {
        try {
            return internalClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .build());
        } catch (Exception exception) {
            throw new ObjectStorageException("Unable to read uploaded object", exception);
        }
    }

    @Override
    public void copyObject(
            String sourceBucket,
            String targetBucket,
            String objectKey
    ) {
        try {
            internalClient.copyObject(
                    CopyObjectArgs.builder()
                            .bucket(targetBucket)
                            .object(objectKey)
                            .source(
                                    SourceObject.builder()
                                            .bucket(sourceBucket)
                                            .object(objectKey)
                                            .build())
                            .build());
        } catch (Exception exception) {
            throw new ObjectStorageException(
                    "Unable to promote uploaded object",
                    exception);
        }
    }

    @Override
    public void removeObject(String bucket, String objectKey) {
        try {
            internalClient.removeObject(
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
            String url = presignClient.getPresignedObjectUrl(
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
