package com.lowaltitude.inspection.identity.application;

import java.util.Optional;

/** 机构入驻用到的别的模块和外部服务，由启动模块接上；本模块不读别的模块的表。 */
public final class OnboardingPorts {

    private OnboardingPorts() {
    }

    /** 平台城市目录（巡检模块）：可选城市的名称；不存在或不可选时为空。 */
    public interface Cities {

        Optional<String> cityName(String cityCode);
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
