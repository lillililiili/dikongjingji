package com.lowaltitude.inspection.ops.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 运营端素材的本机文件实现，只给开发环境用（application-dev.yml 的 ops.media.local-dir）：模拟对象存储。
 * 地址是配置的 HTTPS 前缀加键（开发默认是一个不存在的主机，App 里打不开；运营端经 GET /api/v1/ops/media/{fileName}
 * 带登录取回预览）。只认 OpsMediaService 生成的键，解析后的路径必须落在配置目录里；先写临时文件再改名。
 */
public final class LocalOpsMediaStore implements OpsMediaStore {

    private final Path root;
    private final String publicBaseUrl;

    public LocalOpsMediaStore(Path root, String publicBaseUrl) {
        if (publicBaseUrl == null || !publicBaseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("ops.media.public-base-url must be an https address");
        }
        this.root = root.toAbsolutePath().normalize();
        this.publicBaseUrl = publicBaseUrl.endsWith("/")
                ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1)
                : publicBaseUrl;
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
            throw new UnavailableException("local ops media was not written", failure);
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (NoSuchFileException missing) {
            return null;
        } catch (IOException failure) {
            throw new UnavailableException("local ops media was not read", failure);
        }
    }

    @Override
    public String publicUrl(String key) {
        resolve(key);
        return publicBaseUrl + "/" + key;
    }

    Path resolve(String key) {
        if (key == null || !OpsMediaService.KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("storage key is invalid");
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("storage key is invalid");
        }
        return target;
    }
}
