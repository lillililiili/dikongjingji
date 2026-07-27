package com.lowaltitude.inspection.domain.files;

public interface ObjectStoragePort {

    PresignedObjectIntent createUploadIntent(UploadObjectRequest request);

    PresignedObjectIntent createDownloadIntent(DownloadObjectRequest request);
}
