package com.lowaltitude.inspection.application.provider;

import java.util.UUID;

import com.lowaltitude.inspection.infrastructure.persistence.InspectionProviderProfileMapper;
import org.springframework.transaction.annotation.Transactional;

/**
 * 服务商资料的开通（S6-01，契约 openapi-ops-organization.yaml 1.1.0）。服务商资料（inspection_provider_profile）归巡检模块，
 * 专业服务详情的服务商行、服务商主页、需求邀请和工作台“服务商资料”都以它是否在用为准。运营端“机构与资质”经身份模块的端口
 * 调这里，由启动模块接上；机构类型和资质审核由身份模块在调用前检查，这里不读身份的表。
 */
public class InspectionProviderProfiles {

    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";

    private final InspectionProviderProfileMapper mapper;

    public InspectionProviderProfiles(InspectionProviderProfileMapper mapper) {
        this.mapper = mapper;
    }

    /** 服务商资料的状态。 */
    public enum Status {
        NOT_OPENED,
        ACTIVE,
        DISABLED
    }

    @Transactional(readOnly = true)
    public Status status(UUID providerId) {
        if (providerId == null) {
            throw new IllegalArgumentException("provider is required");
        }
        return status(mapper.findStatus(providerId));
    }

    /**
     * 开通：没有就建一行（在用），停用的恢复在用，在用的不动。返回开通之前的状态，调用方据此决定要不要记操作记录。
     * 同一家机构的并发开通由调用方锁住机构行；这里另外锁住资料行，插入也用 ON CONFLICT 兜底，不会建出第二行。
     */
    @Transactional
    public Status open(UUID providerId) {
        if (providerId == null) {
            throw new IllegalArgumentException("provider is required");
        }
        Status before = status(mapper.lockStatus(providerId));
        switch (before) {
            case NOT_OPENED -> {
                if (mapper.insertActive(providerId) != 1) {
                    // 另一个请求刚刚建好了这一行：照它现在的状态再处理一次（那一次记操作记录，这一次不记）。
                    return open(providerId);
                }
            }
            case DISABLED -> mapper.reactivate(providerId);
            case ACTIVE -> {
                // 已开通，不动。
            }
        }
        return before;
    }

    private static Status status(String code) {
        if (code == null) {
            return Status.NOT_OPENED;
        }
        return switch (code) {
            case ACTIVE -> Status.ACTIVE;
            case DISABLED -> Status.DISABLED;
            default -> throw new IllegalStateException("unknown provider profile status " + code);
        };
    }
}
