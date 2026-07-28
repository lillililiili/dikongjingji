package com.lowaltitude.inspection.domain.files;

public interface ObjectStoragePort {

    PreparedObjectUpload createUploadIntent(UploadObjectRequest request);

    UploadVerificationResult verifyUpload(UploadObjectRequest request);

    PresignedObjectIntent createDownloadIntent(DownloadObjectRequest request);

    default PresignedObjectIntent createBundleDownloadIntent(
            BundleDownloadRequest request
    ) {
        throw new UnsupportedOperationException("bundle download is unavailable");
    }
}
