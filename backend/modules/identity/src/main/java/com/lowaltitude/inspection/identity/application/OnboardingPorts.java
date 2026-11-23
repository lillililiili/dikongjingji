package com.lowaltitude.inspection.identity.application;

import java.util.Optional;
import java.util.UUID;

/** 机构入驻用到的别的模块和外部服务，由启动模块接上；本模块不读别的模块的表。 */
public final class OnboardingPorts {

    private OnboardingPorts() {
    }

    /** 平台城市目录（巡检模块）：可选城市的名称；不存在或不可选时为空。 */
    public interface Cities {

        Optional<String> cityName(String cityCode);
    }

    /**
     * 服务商资料（S6-01）：巡检模块的 inspection_provider_profile（巡检迁移 V004），专业服务详情的服务商行、服务商主页、
     * 需求邀请、工作台“服务商资料”都以它为准。启动模块接到巡检模块；身份模块不读那张表。
     */
    public interface ProviderProfiles {

        /** 这家机构的服务商资料现在是什么状态；没有资料时 NOT_OPENED。 */
        ProviderProfileStatus status(UUID organizationId);

        /** 开通：没有就建一行（在用），停用的恢复在用，在用的不动。返回开通之前的状态。 */
        ProviderProfileStatus open(UUID organizationId);
    }

    /** 服务商资料的状态（契约 OpsProviderProfileStatus）。 */
    public enum ProviderProfileStatus {
        NOT_OPENED,
        ACTIVE,
        DISABLED
    }

    /** 口令摘要（启动模块的 BCrypt）。 */
    public interface PasswordHasher {

        String hash(String rawPassword);
    }

    /**
     * 资质附件的文件存储。开发环境是本机目录（bootstrap 的 LocalQualificationFileStore），其他环境没配置时
     * 一律抛 {@link FileStoreUnavailableException}，不假装存好了。key 由本模块生成，只含机构 ID 和附件 ID。
     */
    public interface QualificationFiles {

        void put(String key, byte[] content);

        byte[] read(String key);
    }

    /** 附件存储不可用。 */
    public static final class FileStoreUnavailableException extends RuntimeException {

        public FileStoreUnavailableException(String message) {
            super(message);
        }

        public FileStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
