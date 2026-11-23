package com.lowaltitude.inspection.identity.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 运营端“机构入驻与资质审核”对外的形状，字段与契约 contracts/openapi-ops-organization.yaml 逐个对应。
// 不含口令摘要、手机号；附件只给元数据，不给存储地址。

/** 列表里的一家机构。cityName 来自平台城市目录，城市不在目录里时为 null。 */
record OpsOrganizationView(
        UUID organizationId,
        String organizationCode,
        String name,
        OpsOrganizationType type,
        String cityCode,
        String cityName,
        String status,
        OpsQualificationStatus qualificationStatus,
        OffsetDateTime qualificationReviewedAt,
        OffsetDateTime createdAt
) {
}

record OpsOrganizationPageView(int page, int size, long total, boolean hasMore, List<OpsOrganizationView> items) {
}

/** 资质资料。version 是改资料、审核时要带的版本号。 */
record OpsQualificationView(
        String subjectName,
        String licenseNumber,
        String businessScope,
        String qualificationNote,
        OpsQualificationStatus status,
        OffsetDateTime submittedAt,
        OffsetDateTime reviewedAt,
        String rejectReason,
        int version
) {
}

record OpsAttachmentView(UUID attachmentId, String fileName, String contentType, long sizeBytes,
                         OffsetDateTime uploadedAt) {
}

/** 机构的一个账号：登录名给运营转告机构用；不含口令和手机号。roles 是现在有效的角色。 */
record OpsAccountView(UUID accountId, String loginName, String displayName, String status, List<String> roles,
                      OffsetDateTime createdAt) {
}

record OpsOperationView(UUID operationId, OpsOrganizationAction action, String detail, String operatorName,
                        OffsetDateTime occurredAt) {
}

/**
 * 机构详情：资料、附件、账号、能授的角色、服务商资料的状态（1.1.0，企业客户为 null）和最近 50 条操作记录。
 */
record OpsOrganizationDetailView(
        OpsOrganizationView organization,
        OpsQualificationView qualification,
        List<OpsAttachmentView> attachments,
        List<OpsAccountView> accounts,
        List<OpsGrantableRole> grantableRoles,
        OnboardingPorts.ProviderProfileStatus providerProfileStatus,
        List<OpsOperationView> operations
) {
}

/** 新建的账号和它的初始口令：口令只在这一次返回，服务端只存摘要。 */
record OpsAccountCreatedView(OpsAccountView account, String initialPassword) {
}

record OpsOrganizationCreated(OpsOrganizationDetailView detail, boolean created) {
}

/** 下载附件：文件名、类型和内容。 */
record OpsAttachmentContent(String fileName, String contentType, byte[] content) {
}
