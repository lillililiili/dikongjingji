package com.lowaltitude.inspection.identity.application;

import java.util.Arrays;
import java.util.List;

// 运营端“机构入驻与资质审核”的稳定编码（契约 contracts/openapi-ops-organization.yaml），与迁移 V007、V011 的 CHECK 约束一致。

/** 运营端能建、能管的机构类型：PROVIDER 经营机构（商家、服务商、活动主办、场馆运营都是这类）；CUSTOMER 企业客户。 */
enum OpsOrganizationType {
    PROVIDER,
    CUSTOMER
}

/** 资质状态：NOT_SUBMITTED 未提交；SUBMITTED 待审核；APPROVED 已通过；REJECTED 已驳回。 */
enum OpsQualificationStatus {
    NOT_SUBMITTED,
    SUBMITTED,
    APPROVED,
    REJECTED
}

/** 审核结论。 */
enum OpsReviewDecision {
    APPROVE,
    REJECT
}

/** 运营端对机构做过的事（操作记录）。 */
enum OpsOrganizationAction {
    CREATE,
    QUALIFICATION_SUBMIT,
    QUALIFICATION_APPROVE,
    QUALIFICATION_REJECT,
    ATTACHMENT_ADD,
    ATTACHMENT_REMOVE,
    ACCOUNT_CREATE,
    ROLES_CHANGE,
    /** 开通服务商资料（S6-01，契约 1.1.0，迁移 V011）。 */
    PROVIDER_PROFILE_OPEN
}

/**
 * 运营端能授给机构账号的角色。机构角色限定在账号的默认机构（就是这家）；CUSTOMER 是全局角色，给企业客户的账号。
 * 平台运营、平台客服这类平台角色不能在这里授。
 * <ul>
 *   <li>PROVIDER_OPERATOR 服务商操作员（经营机构的日常操作：报价、发货、核销、回复客服等受权接口）</li>
 *   <li>LIVE_HOST 直播主办（M20：开通直播权限）</li>
 *   <li>CUSTOMER 客户（企业客户的账号）</li>
 * </ul>
 */
enum OpsGrantableRole {
    PROVIDER_OPERATOR(OpsOrganizationType.PROVIDER, true),
    LIVE_HOST(OpsOrganizationType.PROVIDER, true),
    CUSTOMER(OpsOrganizationType.CUSTOMER, false);

    private final OpsOrganizationType organizationType;
    private final boolean organizationScoped;

    OpsGrantableRole(OpsOrganizationType organizationType, boolean organizationScoped) {
        this.organizationType = organizationType;
        this.organizationScoped = organizationScoped;
    }

    OpsOrganizationType organizationType() {
        return organizationType;
    }

    boolean organizationScoped() {
        return organizationScoped;
    }

    static List<OpsGrantableRole> forType(OpsOrganizationType type) {
        return Arrays.stream(values()).filter(role -> role.organizationType == type).toList();
    }
}
