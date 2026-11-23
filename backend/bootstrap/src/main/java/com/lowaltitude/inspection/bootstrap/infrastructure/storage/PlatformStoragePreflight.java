package com.lowaltitude.inspection.bootstrap.infrastructure.storage;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 平台文件存储配置的逐项自检（S6），照 WechatPayPreflight 的写法：一次列出所有问题，每项只说哪里不对、改哪个环境变量，
 * 不带密钥和口令。只查配置本身，不连存储（存储比后端晚起时不因此启动失败；连不上时各上传接口照旧回 503）。
 *
 * <p>provider 为 local 时只查 provider 本身；为 s3 时有一项 FAIL 就不让后端启动。
 */
public final class PlatformStoragePreflight {

    public static final String PASS = "PASS";
    public static final String WARN = "WARN";
    public static final String FAIL = "FAIL";

    /** 每一项对应的环境变量，启动失败时写在项目代号后面。 */
    static final Map<String, String> VARIABLES = Map.ofEntries(
            Map.entry("PROVIDER", "PLATFORM_STORAGE_PROVIDER"),
            Map.entry("ENDPOINT", "PLATFORM_STORAGE_S3_ENDPOINT"),
            Map.entry("REGION", "PLATFORM_STORAGE_S3_REGION"),
            Map.entry("ACCESS_KEY", "PLATFORM_STORAGE_S3_ACCESS_KEY"),
            Map.entry("SECRET_KEY", "PLATFORM_STORAGE_S3_SECRET_KEY"),
            Map.entry("PRIVATE_BUCKET", "PLATFORM_STORAGE_S3_PRIVATE_BUCKET"),
            Map.entry("PUBLIC_BUCKET", "PLATFORM_STORAGE_S3_PUBLIC_BUCKET"),
            Map.entry("PUBLIC_BASE_URL", "PLATFORM_STORAGE_S3_PUBLIC_BASE_URL"));

    private static final Pattern BUCKET = Pattern.compile("^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$");
    private static final Pattern REGION = Pattern.compile("^[a-z0-9][a-z0-9-]{0,31}$");
    private static final int MIN_SECRET_LENGTH = 8;

    public record Item(String code, String status, String message) {
    }

    private PlatformStoragePreflight() {
    }

    public static List<Item> inspect(PlatformStorageProperties properties) {
        List<Item> items = new ArrayList<>();
        String provider = properties.provider().toLowerCase(Locale.ROOT);
        if (PlatformStorageProperties.LOCAL.equals(provider)) {
            items.add(pass("PROVIDER", "文件存储是 local：只有开发配置的本机目录可用，其他环境上传下载返回 503"));
            return items;
        }
        if (!PlatformStorageProperties.S3_PROVIDER.equals(provider)) {
            items.add(fail("PROVIDER", "文件存储只能是 local 或 s3"));
            return items;
        }
        items.add(new Item("PROVIDER", PASS, "文件存储是 s3（S3 兼容对象存储）"));
        PlatformStorageProperties.S3 s3 = properties.s3();
        items.add(endpoint(s3.endpoint()));
        items.add(REGION.matcher(s3.region()).matches()
                ? pass("REGION", "区域格式对")
                : fail("REGION", "区域只能是小写字母、数字和短横线（MinIO 用默认的 us-east-1）"));
        items.add(blank(s3.accessKey())
                ? fail("ACCESS_KEY", "没有配置访问账号")
                : pass("ACCESS_KEY", "已配置访问账号"));
        if (blank(s3.secretKey())) {
            items.add(fail("SECRET_KEY", "没有配置访问密钥"));
        } else if (s3.secretKey().trim().length() < MIN_SECRET_LENGTH) {
            items.add(fail("SECRET_KEY", "访问密钥太短（至少 8 位）"));
        } else {
            items.add(pass("SECRET_KEY", "已配置访问密钥"));
        }
        items.add(bucket("PRIVATE_BUCKET", "私有桶", s3.privateBucket()));
        Item publicBucket = bucket("PUBLIC_BUCKET", "公开桶", s3.publicBucket());
        if (PASS.equals(publicBucket.status()) && s3.publicBucket().equals(s3.privateBucket())) {
            publicBucket = fail("PUBLIC_BUCKET", "公开桶和私有桶不能是同一个（私有文件会被匿名读到）");
        }
        items.add(publicBucket);
        items.add(publicBaseUrl(s3.publicBaseUrl()));
        return items;
    }

    /** 启动时用：provider 写错或 s3 配置有 FAIL 就不启动，一次列出全部 FAIL。返回 WARN 项给调用方记日志。 */
    public static List<Item> requireStartable(PlatformStorageProperties properties) {
        List<Item> items = inspect(properties);
        List<Item> failures = items.stream().filter(item -> FAIL.equals(item.status())).toList();
        if (!failures.isEmpty()) {
            StringBuilder message = new StringBuilder("文件存储配置自检不通过（共 " + failures.size() + " 项）：");
            for (Item failure : failures) {
                message.append("\n  - ").append(failure.code())
                        .append("（").append(VARIABLES.get(failure.code())).append("）：")
                        .append(failure.message());
            }
            throw new IllegalStateException(message.toString());
        }
        return items.stream().filter(item -> WARN.equals(item.status())).toList();
    }

    // ---- 各项 ----

    private static Item endpoint(URI endpoint) {
        if (endpoint == null || endpoint.toString().isBlank()) {
            return fail("ENDPOINT", "没有配置存储地址（例如 http://minio:9000）");
        }
        String scheme = endpoint.getScheme();
        if (endpoint.getHost() == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return fail("ENDPOINT", "存储地址必须是完整的 http 或 https 地址");
        }
        if (endpoint.getRawQuery() != null || endpoint.getRawFragment() != null || endpoint.getRawUserInfo() != null) {
            return fail("ENDPOINT", "存储地址只写到主机和端口，不带账号、查询参数");
        }
        String path = endpoint.getRawPath();
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return fail("ENDPOINT", "存储地址只写到主机和端口，不带路径");
        }
        return pass("ENDPOINT", "存储地址格式对");
    }

    private static Item bucket(String code, String label, String value) {
        if (blank(value)) {
            return fail(code, "没有配置" + label);
        }
        return BUCKET.matcher(value).matches()
                ? pass(code, label + "名格式对")
                : fail(code, label + "名只能是 3–63 位小写字母、数字和短横线，首尾不能是短横线");
    }

    private static Item publicBaseUrl(URI url) {
        if (url == null || url.toString().isBlank()) {
            return fail("PUBLIC_BASE_URL", "没有配置公开文件的对外地址（例如 https://files.example.com/prod-public-assets）");
        }
        if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null) {
            return fail("PUBLIC_BASE_URL", "公开文件的对外地址必须是 https（App 只显示受控的 https 地址）");
        }
        if (url.getRawQuery() != null || url.getRawFragment() != null || url.getRawUserInfo() != null) {
            return fail("PUBLIC_BASE_URL", "公开文件的对外地址不带账号、查询参数");
        }
        return pass("PUBLIC_BASE_URL", "公开文件的对外地址是 https");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Item pass(String code, String message) {
        return new Item(code, PASS, message);
    }

    private static Item fail(String code, String message) {
        return new Item(code, FAIL, message);
    }
}
