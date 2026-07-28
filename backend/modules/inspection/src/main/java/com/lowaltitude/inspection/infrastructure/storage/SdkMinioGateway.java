package com.lowaltitude.inspection.infrastructure.storage;

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import io.minio.CopyObjectArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.Http.Method;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.SourceObject;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.UploadObjectArgs;
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

    @Override
    public void createZipBundle(
            String bucket,
            String objectKey,
            List<ZipBundleEntry> entries
    ) {
        Path temporary = null;
        try {
            temporary = Files.createTempFile("inspection-delivery-", ".zip");
            Set<String> names = new HashSet<>();
            try (ZipOutputStream output =
                         new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (ZipBundleEntry entry : entries) {
                    if (!names.add(entry.archiveName())) {
                        throw new IllegalArgumentException(
                                "bundle archive names must be unique");
                    }
                    output.putNextEntry(new ZipEntry(entry.archiveName()));
                    try (InputStream input = openObject(bucket, entry.objectKey())) {
                        input.transferTo(output);
                    }
                    output.closeEntry();
                }
            }
            internalClient.uploadObject(
                    UploadObjectArgs.builder()
                            .bucket(bucket)
                            .object(objectKey)
                            .filename(temporary.toString())
                            .contentType("application/zip")
                            .build());
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ObjectStorageException(
                    "Unable to create delivery ZIP bundle",
                    exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (java.io.IOException ignored) {
                    // The OS temp cleaner is the fallback; do not mask the storage result.
                }
            }
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
