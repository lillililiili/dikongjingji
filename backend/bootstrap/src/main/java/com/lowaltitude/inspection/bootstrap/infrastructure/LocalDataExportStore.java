package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.identity.application.account.AccountPorts;

/**
 * 个人信息导出文件的本机实现（S5-8，只给开发环境用：application-dev.yml 的 identity.data-export.local-dir）。
 * 真实环境接对象存储时另写一个 AccountPorts.ExportFiles 实现。只接受身份模块生成的键，路径必须落在配置的目录里。
 */
public final class LocalDataExportStore implements AccountPorts.ExportFiles {

    private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final Pattern KEY = Pattern.compile("^data-exports/" + UUID + "/" + UUID + "\\.zip$");

    private final Path root;

    public LocalDataExportStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void put(String key, byte[] content) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), ".export-", ".tmp");
            Files.write(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            throw new AccountPorts.ExportStoreUnavailableException("local export file was not written", failure);
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException failure) {
            throw new AccountPorts.ExportStoreUnavailableException("local export file was not read", failure);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException failure) {
            throw new AccountPorts.ExportStoreUnavailableException("local export file was not deleted", failure);
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

    /** 没有配置本机目录时（开发环境以外）：一律不可用，导出记为失败，不假装生成好了。 */
    public static final class Unavailable implements AccountPorts.ExportFiles {

        @Override
        public void put(String key, byte[] content) {
            throw new AccountPorts.ExportStoreUnavailableException("data export store is not configured");
        }

        @Override
        public byte[] read(String key) {
            throw new AccountPorts.ExportStoreUnavailableException("data export store is not configured");
        }

        @Override
        public void delete(String key) {
            throw new AccountPorts.ExportStoreUnavailableException("data export store is not configured");
        }
    }
}
