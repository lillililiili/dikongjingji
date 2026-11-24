package com.lowaltitude.inspection.identity.application;

import java.util.List;
import java.util.UUID;

// 商家自助入驻（契约 contracts/openapi-organization-signup.yaml）对外的形状。不含口令、手机号。

/** 申请入驻的结果：新建的机构和登录名；之后用这个登录名在同一个登录页登录工作台。 */
record OrganizationSignupResult(
        UUID organizationId,
        String organizationName,
        String loginName,
        OpsQualificationStatus qualificationStatus
) {
}

/** 工作台“入驻资料”：本机构的资料、资质（含审核结果）、附件、服务商资料状态。账号和操作记录不给机构自己看。 */
record OwnOrganizationView(
        OpsOrganizationView organization,
        OpsQualificationView qualification,
        List<OpsAttachmentView> attachments,
        OnboardingPorts.ProviderProfileStatus providerProfileStatus
) {
}

/** created 为 false 表示同一个幂等键重放。 */
record OrganizationSignupCreated(OrganizationSignupResult result, boolean created) {
}
