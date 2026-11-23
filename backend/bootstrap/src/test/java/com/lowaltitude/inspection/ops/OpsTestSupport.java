package com.lowaltitude.inspection.ops;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 运营端控制器测试共用：按角色造一个登录的人、带显示名的 OpsActors、和项目一致的 MockMvc。 */
public final class OpsTestSupport {

    public static final UUID OPERATOR_ID = UUID.fromString("90000000-0000-4000-8000-0000000000b1");
    public static final UUID SUPPORT_ID = UUID.fromString("90000000-0000-4000-8000-0000000000a1");
    public static final UUID PLATFORM_ORG = UUID.fromString("90000000-0000-4000-8000-000000000001");
    public static final String OPERATOR_NAME = "平台运营（示例）";
    public static final String SUPPORT_NAME = "平台客服（示例）";

    private OpsTestSupport() {
    }

    public static TestingAuthenticationToken signedIn(UUID subjectId, String... roles) {
        return new TestingAuthenticationToken(
                new CurrentSubject(subjectId, Set.of(roles), PLATFORM_ORG, Set.of(), 0), null);
    }

    public static TestingAuthenticationToken operator() {
        return signedIn(OPERATOR_ID, OpsRoles.PLATFORM_OPERATOR);
    }

    public static TestingAuthenticationToken support() {
        return signedIn(SUPPORT_ID, OpsRoles.PLATFORM_SUPPORT);
    }

    /** 两个平台账号有显示名，别的账号没有资料（按停用处理）。 */
    public static OpsActors actors() {
        return new OpsActors(subjectId -> {
            if (OPERATOR_ID.equals(subjectId)) {
                return Optional.of(new IdentityProfile(subjectId, OPERATOR_NAME, "低空产业平台（示例）", "PLATFORM"));
            }
            if (SUPPORT_ID.equals(subjectId)) {
                return Optional.of(new IdentityProfile(subjectId, SUPPORT_NAME, "低空产业平台（示例）", "PLATFORM"));
            }
            return Optional.empty();
        });
    }

    public static MockMvc mockMvc(Object... controllers) {
        return MockMvcBuilders.standaloneSetup(controllers)
                .setControllerAdvice(new OpsExceptionHandler(), new GlobalExceptionHandler())
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json()
                                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                                .build()))
                .build();
    }
}
