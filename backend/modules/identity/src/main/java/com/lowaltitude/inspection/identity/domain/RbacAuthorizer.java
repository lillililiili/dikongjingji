package com.lowaltitude.inspection.identity.domain;

public final class RbacAuthorizer {

    public boolean hasPermission(CurrentSubject subject, String permissionCode) {
        return subject.permissions().contains(permissionCode);
    }
}
