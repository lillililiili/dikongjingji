package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.serviceorder.application.ServiceOrderPorts;

/**
 * 服务订单交付文件的本机文件实现（S4-3，只给开发环境用：application-dev.yml 的 serviceorder.files.local-dir）。
 * 真实环境接对象存储时另写一个 ServiceOrderPorts.Files 实现，不改服务订单模块。
 * 只接受服务订单模块生成的键（orders/订单 ID/files/文件 ID），解析后的路径必须落在配置的目录里；先写临时文件再改名。
 */
public final class LocalServiceOrderFileStore implements ServiceOrderPorts.Files {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern KEY = Pattern.compile("^orders/" + UUID_PATTERN + "/files/" + UUID_PATTERN + "$");

    private final Path root;

    public LocalServiceOrderFileStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new ServiceOrderPorts.FileStoreUnavailableException("local service order file was not written",
                    failure);
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException failure) {
            throw new ServiceOrderPorts.FileStoreUnavailableException("local service order file was not read",
                    failure);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (NoSuchFileException gone) {
            // 已经没有了。
        } catch (IOException failure) {
            throw new ServiceOrderPorts.FileStoreUnavailableException("local service order file was not deleted",
                    failure);
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

    /** 没有配置本机目录时（开发环境以外）：一律不可用，不假装存好了。 */
    public static final class Unavailable implements ServiceOrderPorts.Files {

        @Override
        public void put(String key, byte[] content) {
            throw new ServiceOrderPorts.FileStoreUnavailableException("service order file store is not configured");
        }

        @Override
        public byte[] read(String key) {
            throw new ServiceOrderPorts.FileStoreUnavailableException("service order file store is not configured");
        }

        @Override
        public void delete(String key) {
            throw new ServiceOrderPorts.FileStoreUnavailableException("service order file store is not configured");
        }
    }
}
