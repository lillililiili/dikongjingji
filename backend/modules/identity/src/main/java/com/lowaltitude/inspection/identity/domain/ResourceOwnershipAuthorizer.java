package com.lowaltitude.inspection.identity.domain;

public interface ResourceOwnershipAuthorizer {

    boolean isAuthorized(CurrentSubject subject, ResourceOwnership ownership);
}
