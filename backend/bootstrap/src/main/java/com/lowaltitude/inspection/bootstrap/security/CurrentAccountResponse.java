package com.lowaltitude.inspection.bootstrap.security;

import java.util.List;
import java.util.UUID;

/** /api/v1/auth/me 的返回（契约 openapi-inspection-service-flow.yaml 2.4.0 的 CurrentAccount）。 */
public record CurrentAccountResponse(
        String displayName,
        String organizationName,
        String organizationType,
        List<String> roles,
        String maskedPhone,
        Identity identity
) {

    /** 当前身份：个人时只有 type。 */
    public record Identity(String type, UUID enterpriseId, String enterpriseName, String role) {
    }
}
