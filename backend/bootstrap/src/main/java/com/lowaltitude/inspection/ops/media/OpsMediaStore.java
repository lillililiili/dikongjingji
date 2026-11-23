package com.lowaltitude.inspection.ops.media;

/**
 * 运营端素材（封面、图集、正文配图、设备图、视频）的对象存储端口（S3-1）。键由 {@link OpsMediaService} 生成
 * （ops-media/&lt;uuid&gt;.&lt;扩展名&gt;），对外地址是配置的受控 HTTPS 前缀加键。开发环境用本机目录
 * （{@link LocalOpsMediaStore}）；真实环境接对象存储时另写一个实现，业务模块只认 https 地址，不用改。
 */
public interface OpsMediaStore {

    void put(String key, byte[] content, String contentType);

    byte[] read(String key);

    /** App 和运营端用的受控 HTTPS 地址。 */
    String publicUrl(String key);

    /** 存储暂不可用（没配置、写不进去、读不到）。 */
    final class UnavailableException extends RuntimeException {

        public UnavailableException(String message) {
            super(message);
        }

        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 没有配置存储时：一律不可用，不假装存好了。 */
    final class Unavailable implements OpsMediaStore {

        @Override
        public void put(String key, byte[] content, String contentType) {
            throw new UnavailableException("ops media store is not configured");
        }

        @Override
        public byte[] read(String key) {
            throw new UnavailableException("ops media store is not configured");
        }

        @Override
        public String publicUrl(String key) {
            throw new UnavailableException("ops media store is not configured");
        }
    }
}
