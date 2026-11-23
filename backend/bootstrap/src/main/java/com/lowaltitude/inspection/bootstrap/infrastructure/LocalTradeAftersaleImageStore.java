package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.trade.application.TradeAftersaleImageStore;

/**
 * 售后凭证图片的本机文件实现（S4-2，只给开发环境用：application-dev.yml 的 trade.aftersale.image-dir）。
 * 图片不公开：只经买家和商家的受权接口回读。只接受交易模块生成的键（aftersale/UUID.扩展名），解析后的路径必须落在配置的目录里。
 * 真实环境接对象存储的私有桶时另写一个实现，不改交易模块。
 */
public final class LocalTradeAftersaleImageStore implements TradeAftersaleImageStore {

    static final Pattern KEY = Pattern.compile(
            "^aftersale/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp)$");

    private final Path root;

    public LocalTradeAftersaleImageStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
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
            throw new UnavailableException("local aftersale image was not written", failure);
        }
    }

    @Override
    public Optional<byte[]> read(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        Path target = resolve(key);
        try {
            return Files.isRegularFile(target) ? Optional.of(Files.readAllBytes(target)) : Optional.empty();
        } catch (IOException failure) {
            return Optional.empty();
        }
    }

    private Path resolve(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("aftersale image key is invalid");
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("aftersale image key is invalid");
        }
        return target;
    }
}
