package com.lowaltitude.inspection.ops.media;

import com.lowaltitude.inspection.bootstrap.infrastructure.storage.PlatformStorage;
import com.lowaltitude.inspection.bootstrap.infrastructure.storage.S3ObjectClient.ObjectStorageUnavailableException;

/**
 * 运营端素材接 S3 兼容对象存储（S6，platform.storage.provider=s3）：放公开桶的 ops/ 命名空间，对外地址是公开 https 前缀
 * 加对象键（App 和运营端只认 https 地址）；运营端预览照旧经 GET /api/v1/ops/media/{fileName} 带登录读回。
 * 只认 {@link OpsMediaService} 生成的键。
 */
public final class S3OpsMediaStore implements OpsMediaStore {

    private final PlatformStorage.Area area;

    public S3OpsMediaStore(PlatformStorage storage) {
        this.area = storage.publicArea("ops");
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        requireKey(key);
        try {
            area.put(key, content, contentType);
        } catch (ObjectStorageUnavailableException failure) {
            throw new UnavailableException("ops media was not written", failure);
        }
    }

    @Override
    public byte[] read(String key) {
        requireKey(key);
        try {
            return area.get(key).orElse(null);
        } catch (ObjectStorageUnavailableException failure) {
            throw new UnavailableException("ops media was not read", failure);
        }
    }

    @Override
    public String publicUrl(String key) {
        return area.publicUrl(requireKey(key));
    }

    private static String requireKey(String key) {
        if (key == null || !OpsMediaService.KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("storage key is invalid");
        }
        return key;
    }
}
