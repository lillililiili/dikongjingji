package com.lowaltitude.inspection.identity.domain;

import java.util.Optional;
import java.util.UUID;

public interface IdentityAccountPort {

    Optional<IdentityAccount> findByLoginName(String loginName);

    /**
     * 按账号 ID 找（S5-8 验证码登录、切换身份后重新签发令牌用），条件与按登录名相同。默认找不到，
     * 只有数据库适配器实现它，测试里的替身不用管。
     */
    default Optional<IdentityAccount> findBySubjectId(UUID subjectId) {
        return Optional.empty();
    }
}
