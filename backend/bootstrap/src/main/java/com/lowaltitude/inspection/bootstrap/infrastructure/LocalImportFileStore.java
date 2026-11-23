package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.opsimport.application.OpsImportPorts;

/**
 * 批量导入文件的本机实现（S2-3，只给开发环境用：application-dev.yml 的 opsimport.files.local-dir）。
 * 真实环境接对象存储的私有桶（保留 180 天）时另写一个 FileStore 实现，不改导入模块。
 * 只接受导入模块生成的键（imports/批次 ID/序号.csv），解析后的路径必须落在配置的目录里。
 */
public final class LocalImportFileStore implements OpsImportPorts.FileStore {

    static final Pattern KEY = Pattern.compile(
            "^imports/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9]{1,2}\\.csv$");

    private final Path root;

    public LocalImportFileStore(Path root) {
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
            throw new OpsImportPorts.FileStoreUnavailableException("local import file was not written", failure);
        }
    }

    @Override
    public byte[] read(String key) {
        try {
            return Files.readAllBytes(resolve(key));
        } catch (IOException failure) {
            throw new OpsImportPorts.FileStoreUnavailableException("local import file was not read", failure);
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

    /** 没有配置本机目录时（开发环境以外）：一律不可用，预检返回 503，不假装存好了。 */
    public static final class Unavailable implements OpsImportPorts.FileStore {

        @Override
        public void put(String key, byte[] content) {
            throw new OpsImportPorts.FileStoreUnavailableException("import file store is not configured");
        }

        @Override
        public byte[] read(String key) {
            throw new OpsImportPorts.FileStoreUnavailableException("import file store is not configured");
        }
    }
}
