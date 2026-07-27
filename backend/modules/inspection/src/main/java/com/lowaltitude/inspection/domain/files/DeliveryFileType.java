package com.lowaltitude.inspection.domain.files;

public enum DeliveryFileType {
    IMAGE_ARCHIVE(".zip", "application/zip"),
    DEFECT_LIST(".xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    POINT_KML(".kml", "application/vnd.google-earth.kml+xml"),
    INSPECTION_REPORT(".pdf", "application/pdf");

    private final String extension;
    private final String mimeType;

    DeliveryFileType(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public String extension() {
        return extension;
    }

    public String mimeType() {
        return mimeType;
    }
}
