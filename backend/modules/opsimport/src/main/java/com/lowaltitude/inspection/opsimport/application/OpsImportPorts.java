package com.lowaltitude.inspection.opsimport.application;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 批量导入用到的别的模块和外部服务，由启动模块接上。导入工具只做解析、校验和留痕；业务数据经这里的写入端口交给
 * 各模块自己写（身份、交易、商城），模块之间不直接写对方的表。
 */
public final class OpsImportPorts {

    private OpsImportPorts() {
    }

    /** 谁在操作（平台运营的账号和显示名）。 */
    public record Actor(UUID accountId, String displayName) {
    }

    /** 预检要查库的那些。 */
    public interface Lookups {

        /** 这些 organization_code 在库里的机构；不认识的不在结果里。 */
        Map<String, Organization> organizations(Collection<String> codes);

        /** 平台城市目录里有这个可选城市。 */
        boolean cityExists(String cityCode);

        /** 这家机构在交易模块登记了有效的收款账户。 */
        boolean merchantCollecting(UUID organizationId);

        /** 这些登录名已被用了：值是那个账号的默认机构（可能为空）；没被用的不在结果里。 */
        Map<String, Optional<UUID>> loginOwners(Collection<String> loginNames);

        /** 这个二级商户号在当前环境不能登记（开发环境以外的占位号段）。 */
        boolean subMchidRejected(String subMchid);
    }

    public record Organization(UUID id, String organizationType, String status) {
    }

    /** 写入端口：每类一个方法，在导入工具的事务里调。 */
    public interface Writers {

        OrganizationsWritten organizations(List<OrganizationRecord> organizations, List<OperatorRecord> operators,
                                           String batchLabel, Actor actor);

        List<Written> merchantAccounts(List<MerchantAccountRecord> accounts, String batchLabel, Actor actor);

        List<Written> mall(List<ProductRecord> products, String batchLabel, Actor actor);
    }

    public record OrganizationRecord(String code, String name, String status, LocalDate qualificationReviewedOn) {
    }

    /** 一个操作员账号（同一登录名的几行合在一起）和要授的角色。 */
    public record OperatorRecord(String organizationCode, String loginName, String displayName, List<String> roles) {
    }

    public record MerchantAccountRecord(String organizationCode, UUID organizationId, String subMchid,
                                        boolean active) {
    }

    /** 一个商品。specs、packItems、sellingPoints 为 null 表示这一批没带，原有的不动。 */
    public record ProductRecord(UUID productId, String reference, String category, String name, String summary,
                                String coverUrl, String saleMode, String experienceBadge, int sortOrder,
                                String status, List<String> sellingPoints, List<SpecRecord> specs,
                                List<PackItemRecord> packItems, List<SkuRecord> skus) {
    }

    public record SpecRecord(String label, String value, int sortOrder) {
    }

    public record PackItemRecord(String name, int quantity, int sortOrder) {
    }

    public record SkuRecord(UUID skuId, String reference, String name, int sortOrder, List<OfferRecord> offers) {
    }

    public record OfferRecord(UUID offerId, String reference, UUID merchantId, String merchantKind, String cityCode,
                              Long priceAmountMinor, String priceUnit, Integer stockQuantity,
                              boolean expressAvailable, long shippingFeeMinor, String pickupPoint,
                              String deliveryNote, String shippingNote, String fulfilledFrom, String status) {
    }

    /** 写好的一个对象：最终的 ID，新建还是更新。 */
    public record Written(OpsImportRefKind kind, String reference, UUID targetId, boolean created) {
    }

    public record CreatedAccount(String organizationCode, String loginName, String initialPassword) {
    }

    public record OrganizationsWritten(List<Written> written, List<CreatedAccount> createdAccounts) {
    }

    /** 导入文件的私有存储（真实环境接对象存储的私有桶；开发环境是本机目录）。 */
    public interface FileStore {

        void put(String key, byte[] content);

        byte[] read(String key);
    }

    public static final class FileStoreUnavailableException extends RuntimeException {

        public FileStoreUnavailableException(String message) {
            super(message);
        }

        public FileStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
