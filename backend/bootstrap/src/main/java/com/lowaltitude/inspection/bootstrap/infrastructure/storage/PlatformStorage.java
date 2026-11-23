package com.lowaltitude.inspection.bootstrap.infrastructure.storage;

import java.util.Objects;
import java.util.Optional;

/**
 * 选好的平台文件存储（S6）。local 时各模块照旧用本机目录（只开发环境配了）或“不可用”；s3 时各模块的端口实现委托给
 * {@link Area}：每个模块一个命名空间（对象键前面加模块名），模块之间不互相读对方的文件。
 */
public final class PlatformStorage {

    private final S3ObjectClient client;
    private final String privateBucket;
    private final String publicBucket;
    private final String publicBaseUrl;

    private PlatformStorage(S3ObjectClient client, String privateBucket, String publicBucket, String publicBaseUrl) {
        this.client = client;
        this.privateBucket = privateBucket;
        this.publicBucket = publicBucket;
        this.publicBaseUrl = publicBaseUrl;
    }

    public static PlatformStorage local() {
        return new PlatformStorage(null, null, null, null);
    }

    public static PlatformStorage s3(S3ObjectClient client, String privateBucket, String publicBucket,
                                     String publicBaseUrl) {
        Objects.requireNonNull(client, "client");
        String base = Objects.requireNonNull(publicBaseUrl, "publicBaseUrl");
        if (!base.startsWith("https://")) {
            throw new IllegalArgumentException("public base url must be https");
        }
        return new PlatformStorage(client, privateBucket, publicBucket,
                base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
    }

    public boolean isS3() {
        return client != null;
    }

    /** 不公开的文件（私有桶）：只经后端受权接口回读，没有对外地址。 */
    public Area privateArea(String namespace) {
        requireS3();
        return new Area(client, privateBucket, namespace, null);
    }

    /** 公开展示的图片和视频（公开桶，匿名只读）：对外地址是公开前缀加对象键。 */
    public Area publicArea(String namespace) {
        requireS3();
        return new Area(client, publicBucket, namespace, publicBaseUrl);
    }

    private void requireS3() {
        if (client == null) {
            throw new IllegalStateException("platform storage provider is not s3");
        }
    }

    /** 一个模块在一个桶里的命名空间：对象键是“命名空间/模块给的键”。 */
    public static final class Area {

        private final S3ObjectClient client;
        private final String bucket;
        private final String prefix;
        private final String publicBaseUrl;

        Area(S3ObjectClient client, String bucket, String namespace, String publicBaseUrl) {
            if (namespace == null || !namespace.matches("^[a-z][a-z0-9-]{1,31}$")) {
                throw new IllegalArgumentException("namespace is invalid");
            }
            this.client = client;
            this.bucket = bucket;
            this.prefix = namespace + "/";
            this.publicBaseUrl = publicBaseUrl;
        }

        public void put(String key, byte[] content, String contentType) {
            client.put(bucket, prefix + key, content, contentType);
        }

        public Optional<byte[]> get(String key) {
            return client.get(bucket, prefix + key);
        }

        public void delete(String key) {
            client.delete(bucket, prefix + key);
        }

        /** 公开地址：https 前缀加对象键（对象键只含模块生成的安全字符）。 */
        public String publicUrl(String key) {
            if (publicBaseUrl == null) {
                throw new IllegalStateException("private objects have no public url");
            }
            return publicBaseUrl + "/" + prefix + S3SigV4Signer.encodePath(key);
        }

        /** 地址是本命名空间的公开地址时，返回模块的键；否则为空（由调用方再校验键的格式）。 */
        public Optional<String> keyOfPublicUrl(String url) {
            String start = publicBaseUrl == null ? null : publicBaseUrl + "/" + prefix;
            if (start == null || url == null || !url.startsWith(start)) {
                return Optional.empty();
            }
            return Optional.of(url.substring(start.length()));
        }
    }
}
