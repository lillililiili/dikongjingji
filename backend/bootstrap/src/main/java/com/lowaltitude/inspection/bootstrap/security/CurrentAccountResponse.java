package com.lowaltitude.inspection.bootstrap.security;

import java.util.List;

public record CurrentAccountResponse(
        String displayName,
        String organizationName,
        String organizationType,
        List<String> roles
) {
}
