package com.lowaltitude.inspection.bootstrap.infrastructure.storage;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 平台文件存储（platform.storage.*，S6）：运营端素材、商品图片、批量导入文件、机构资质附件、服务订单交付文件、售后凭证
 * 六类文件共用。provider 是 local（默认：只有 application-dev.yml 配了各自的本机目录，没配的环境上传下载返回 503）
 * 或 s3（S3 兼容对象存储，MinIO 可用）。s3 的地址、账号、桶都从环境变量来，仓库里只有占位；toString 不输出 secret。
 *
 * <p>巡检预约附件和交付文件仍走巡检模块自己的 MinIO 配置（inspection.infrastructure.storage.*），不在这里。
 */
@ConfigurationProperties(prefix = "platform.storage")
public record PlatformStorageProperties(String provider, S3 s3) {

    public static final String LOCAL = "local";
    public static final String S3_PROVIDER = "s3";

    public PlatformStorageProperties {
        provider = provider == null || provider.isBlank() ? LOCAL : provider.trim().toLowerCase(java.util.Locale.ROOT);
        s3 = s3 == null ? new S3(null, null, null, null, null, null, null, null, null, null) : s3;
    }

    public boolean s3Selected() {
        return S3_PROVIDER.equals(provider);
    }

    /**
     * endpoint：后端访问存储的内部地址（如 http://minio:9000）；privateBucket 放不公开的文件（只经受权接口回读），
     * publicBucket 放公开展示的图片和视频（匿名只读），publicBaseUrl 是 publicBucket 对外的 https 前缀
     * （如 https://files.example.com/prod-public-assets），App 和运营端拿到的地址是它加对象键。
     */
    public record S3(
            URI endpoint,
            String region,
            String accessKey,
            String secretKey,
            String privateBucket,
            String publicBucket,
            URI publicBaseUrl,
            Boolean pathStyle,
            Duration connectTimeout,
            Duration requestTimeout
    ) {

        public S3 {
            region = region == null || region.isBlank() ? "us-east-1" : region.trim();
            pathStyle = pathStyle == null || pathStyle;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        }

        @Override
        public String toString() {
            return "S3[endpoint=" + endpoint + ", region=" + region + ", privateBucket=" + privateBucket
                    + ", publicBucket=" + publicBucket + ", publicBaseUrl=" + publicBaseUrl
                    + ", accessKey=<redacted>, secretKey=<redacted>]";
        }
    }
}
