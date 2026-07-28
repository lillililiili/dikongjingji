package com.lowaltitude.inspection.domain.files;

public record PreparedObjectUpload(
        String uploadBucket,
        String finalBucket,
        String objectKey,
        PresignedObjectIntent intent
) {
}
