package com.lowaltitude.inspection.infrastructure.storage;

public final class ObjectStorageException extends RuntimeException {

    public ObjectStorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
