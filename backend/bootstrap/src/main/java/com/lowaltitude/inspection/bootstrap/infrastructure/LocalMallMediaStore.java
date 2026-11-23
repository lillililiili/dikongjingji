package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.mall.application.MallOpsPorts;

/**
 * 商品图片的本机文件实现（S3-1，只给开发环境用：application-dev.yml 的 mall.media.local-dir）。图片由后端开发接口
 * GET /api/v1/mall/media/products/{文件名} 回读（MallMediaDevController），对外地址是 mall.media.public-base-url 加存储键。
 * 开发地址是 http，App 按 https 规则不显示；真实环境接对象存储的公共读桶或 CDN 时另写一个 MediaStore 实现，不改商城模块。
 * 只接受商城模块生成的键（products/UUID.扩展名），解析后的路径必须落在配置的目录里。
 */
public final class LocalMallMediaStore implements MallOpsPorts.MediaStore {

    static final Pattern KEY = Pattern.compile(
            "^products/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$");

    private final Path root;
    private final String publicBaseUrl;

    public LocalMallMediaStore(Path root, String publicBaseUrl) {
        this.root = root.toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new MallOpsPorts.MediaStoreUnavailableException("local mall media was not written", failure);
        }
    }

    @Override
    public String publicUrl(String key) {
        resolve(key);
        return publicBaseUrl + "/" + key;
    }

    @Override
    public boolean isControlled(String url) {
        if (url == null || !url.startsWith(publicBaseUrl + "/")) {
            return false;
        }
        return KEY.matcher(url.substring(publicBaseUrl.length() + 1)).matches();
    }

    /** 开发接口回读：键不对、文件不在时为空。 */
    public Optional<byte[]> read(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(resolve(key)));
        } catch (IOException missing) {
            return Optional.empty();
        }
    }

    Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("storage key is invalid");
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("storage key is invalid");
        }
        return target;
    }

    /** 没有配置本机目录时（开发环境以外，还没接对象存储）：上传一律不可用，不假装存好了；只认 https 地址。 */
    public static final class Unavailable implements MallOpsPorts.MediaStore {

        @Override
        public void put(String key, byte[] content, String contentType) {
            throw new MallOpsPorts.MediaStoreUnavailableException("mall media store is not configured");
        }

        @Override
        public String publicUrl(String key) {
            throw new MallOpsPorts.MediaStoreUnavailableException("mall media store is not configured");
        }

        @Override
        public boolean isControlled(String url) {
            return false;
        }
    }
}
