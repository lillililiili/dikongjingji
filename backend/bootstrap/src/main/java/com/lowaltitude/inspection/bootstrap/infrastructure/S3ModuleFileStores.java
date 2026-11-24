package com.lowaltitude.inspection.bootstrap.infrastructure;

import java.util.Optional;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.bootstrap.infrastructure.storage.PlatformStorage;
import com.lowaltitude.inspection.bootstrap.infrastructure.storage.S3ObjectClient.ObjectStorageUnavailableException;
import com.lowaltitude.inspection.identity.application.OnboardingPorts;
import com.lowaltitude.inspection.identity.application.account.AccountPorts;
import com.lowaltitude.inspection.mall.application.MallOpsPorts;
import com.lowaltitude.inspection.opsimport.application.OpsImportPorts;
import com.lowaltitude.inspection.serviceorder.application.ServiceOrderPorts;
import com.lowaltitude.inspection.trade.application.TradeAftersaleImageStore;

/**
 * 模块文件接 S3 兼容对象存储（platform.storage.provider=s3）：各模块的存储端口实现委托给
 * {@link PlatformStorage.Area}，每个模块一个命名空间，模块之间不互读文件。键的格式和对应的本机实现（Local*Store）一样，
 * 只认模块自己生成的键；存储出错时照各端口原有的约定报“不可用”（接口回 503），不假装存好了。
 *
 * <ul>
 *   <li>资质附件和个人信息导出（identity/）、服务订单交付文件（serviceorder/）、批量导入文件（opsimport/）、售后凭证（trade/）：
 *       私有桶，只经受权接口回读；</li>
 *   <li>商品图片（mall/）：公开桶，对外地址是公开 https 前缀加对象键。</li>
 * </ul>
 */
public final class S3ModuleFileStores {

    private S3ModuleFileStores() {
    }

    private static String requireKey(Pattern pattern, String key) {
        if (key == null || !pattern.matcher(key).matches()) {
            throw new IllegalArgumentException("storage key is invalid");
        }
        return key;
    }

    /** 个人信息导出 ZIP（私有），通过账号模块签发的一次性下载凭据读取。 */
    public static final class DataExports implements AccountPorts.ExportFiles {

        private final PlatformStorage.Area area;

        public DataExports(PlatformStorage storage) {
            this.area = storage.privateArea("identity");
        }

        @Override
        public void put(String key, byte[] content) {
            try {
                area.put(requireKey(LocalDataExportStore.KEY, key), content, "application/zip");
            } catch (ObjectStorageUnavailableException failure) {
                throw new AccountPorts.ExportStoreUnavailableException("data export was not written", failure);
            }
        }

        @Override
        public byte[] read(String key) {
            try {
                return area.get(requireKey(LocalDataExportStore.KEY, key)).orElseThrow(() ->
                        new AccountPorts.ExportStoreUnavailableException("data export is missing"));
            } catch (ObjectStorageUnavailableException failure) {
                throw new AccountPorts.ExportStoreUnavailableException("data export was not read", failure);
            }
        }

        @Override
        public void delete(String key) {
            try {
                area.delete(requireKey(LocalDataExportStore.KEY, key));
            } catch (ObjectStorageUnavailableException failure) {
                throw new AccountPorts.ExportStoreUnavailableException("data export was not deleted", failure);
            }
        }
    }

    /** 机构资质附件（私有）。 */
    public static final class QualificationFiles implements OnboardingPorts.QualificationFiles {

        private final PlatformStorage.Area area;

        public QualificationFiles(PlatformStorage storage) {
            this.area = storage.privateArea("identity");
        }

        @Override
        public void put(String key, byte[] content) {
            requireKey(LocalQualificationFileStore.KEY, key);
            try {
                area.put(key, content, "application/octet-stream");
            } catch (ObjectStorageUnavailableException failure) {
                throw new OnboardingPorts.FileStoreUnavailableException("qualification file was not written", failure);
            }
        }

        @Override
        public byte[] read(String key) {
            requireKey(LocalQualificationFileStore.KEY, key);
            try {
                return area.get(key).orElseThrow(() ->
                        new OnboardingPorts.FileStoreUnavailableException("qualification file is missing"));
            } catch (ObjectStorageUnavailableException failure) {
                throw new OnboardingPorts.FileStoreUnavailableException("qualification file was not read", failure);
            }
        }
    }

    /** 通用服务订单交付文件（私有）。 */
    public static final class ServiceOrderFiles implements ServiceOrderPorts.Files {

        private final PlatformStorage.Area area;

        public ServiceOrderFiles(PlatformStorage storage) {
            this.area = storage.privateArea("serviceorder");
        }

        @Override
        public void put(String key, byte[] content) {
            requireKey(LocalServiceOrderFileStore.KEY, key);
            try {
                area.put(key, content, "application/octet-stream");
            } catch (ObjectStorageUnavailableException failure) {
                throw new ServiceOrderPorts.FileStoreUnavailableException("service order file was not written",
                        failure);
            }
        }

        @Override
        public byte[] read(String key) {
            requireKey(LocalServiceOrderFileStore.KEY, key);
            try {
                return area.get(key).orElseThrow(() ->
                        new ServiceOrderPorts.FileStoreUnavailableException("service order file is missing"));
            } catch (ObjectStorageUnavailableException failure) {
                throw new ServiceOrderPorts.FileStoreUnavailableException("service order file was not read",
                        failure);
            }
        }

        @Override
        public void delete(String key) {
            requireKey(LocalServiceOrderFileStore.KEY, key);
            try {
                area.delete(key);
            } catch (ObjectStorageUnavailableException failure) {
                throw new ServiceOrderPorts.FileStoreUnavailableException("service order file was not deleted",
                        failure);
            }
        }
    }

    /** 批量导入文件（私有）。 */
    public static final class ImportFiles implements OpsImportPorts.FileStore {

        private final PlatformStorage.Area area;

        public ImportFiles(PlatformStorage storage) {
            this.area = storage.privateArea("opsimport");
        }

        @Override
        public void put(String key, byte[] content) {
            requireKey(LocalImportFileStore.KEY, key);
            try {
                area.put(key, content, "text/csv");
            } catch (ObjectStorageUnavailableException failure) {
                throw new OpsImportPorts.FileStoreUnavailableException("import file was not written", failure);
            }
        }

        @Override
        public byte[] read(String key) {
            requireKey(LocalImportFileStore.KEY, key);
            try {
                return area.get(key).orElseThrow(() ->
                        new OpsImportPorts.FileStoreUnavailableException("import file is missing"));
            } catch (ObjectStorageUnavailableException failure) {
                throw new OpsImportPorts.FileStoreUnavailableException("import file was not read", failure);
            }
        }
    }

    /** 售后凭证图片（私有）。读不到（不存在或存储出错）时为空，和本机实现一致。 */
    public static final class TradeAftersaleImages implements TradeAftersaleImageStore {

        private final PlatformStorage.Area area;

        public TradeAftersaleImages(PlatformStorage storage) {
            this.area = storage.privateArea("trade");
        }

        @Override
        public void put(String key, byte[] content, String contentType) {
            requireKey(LocalTradeAftersaleImageStore.KEY, key);
            try {
                area.put(key, content, contentType);
            } catch (ObjectStorageUnavailableException failure) {
                throw new UnavailableException("aftersale image was not written", failure);
            }
        }

        @Override
        public Optional<byte[]> read(String key) {
            if (key == null || !LocalTradeAftersaleImageStore.KEY.matcher(key).matches()) {
                return Optional.empty();
            }
            try {
                return area.get(key);
            } catch (ObjectStorageUnavailableException failure) {
                return Optional.empty();
            }
        }
    }

    /** 商品图片（公开桶）。 */
    public static final class MallMedia implements MallOpsPorts.MediaStore {

        private final PlatformStorage.Area area;

        public MallMedia(PlatformStorage storage) {
            this.area = storage.publicArea("mall");
        }

        @Override
        public void put(String key, byte[] content, String contentType) {
            requireKey(LocalMallMediaStore.KEY, key);
            try {
                area.put(key, content, contentType);
            } catch (ObjectStorageUnavailableException failure) {
                throw new MallOpsPorts.MediaStoreUnavailableException("mall media was not written", failure);
            }
        }

        @Override
        public String publicUrl(String key) {
            return area.publicUrl(requireKey(LocalMallMediaStore.KEY, key));
        }

        @Override
        public boolean isControlled(String url) {
            return area.keyOfPublicUrl(url).filter(key -> LocalMallMediaStore.KEY.matcher(key).matches()).isPresent();
        }
    }
}
