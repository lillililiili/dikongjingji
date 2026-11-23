package com.lowaltitude.inspection.trade.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.trade.application.OpsMerchantAccountException.Error;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeMerchantAccountMapper;
import com.lowaltitude.inspection.trade.infrastructure.persistence.TradeMerchantAccountMapper.AccountRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“收款账户”（S3-1，契约 contracts/openapi-ops-catalog.yaml 1.0.0，迁移 V009）：经营机构的微信二级商户号登记。
 * 只登记，不做进件（进件在微信服务商后台完成）。商户号 8–32 位数字；借微信文档示例号段的开发占位号
 * （1900000000–1900000999，开发种子里的就是）只在开发环境能登记（allowPlaceholder，application-dev.yml 打开）。
 * 每次登记、修改、批量导入都记一条操作记录。只有平台运营能调，角色由控制器检查。
 */
public class OpsMerchantAccountService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_KEYWORD = 40;
    public static final int OPERATION_HISTORY_LIMIT = 50;
    static final Pattern SUB_MCHID = Pattern.compile("^[0-9]{8,32}$");
    /** 微信支付文档示例用的号段，开发种子借来当占位（R__dev_trade_*.sql）。 */
    static final Pattern PLACEHOLDER = Pattern.compile("^1900000[0-9]{3}$");

    private final TradeMerchantAccountMapper mapper;
    private final boolean allowPlaceholder;
    private final Clock clock;

    public OpsMerchantAccountService(TradeMerchantAccountMapper mapper, boolean allowPlaceholder, Clock clock) {
        this.mapper = mapper;
        this.allowPlaceholder = allowPlaceholder;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OpsMerchantAccountPageView accounts(String keyword, Boolean registered, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        String pattern = keywordPattern(keyword);
        long total = mapper.countAccounts(pattern, registered);
        List<OpsMerchantAccountView> items = mapper.findAccounts(pattern, registered, size,
                        Math.multiplyExact(page - 1, size)).stream()
                .map(OpsMerchantAccountService::view)
                .toList();
        return new OpsMerchantAccountPageView(page, size, total, (long) page * size < total, items);
    }

    @Transactional(readOnly = true)
    public List<OpsMerchantAccountOperationView> operations(UUID organizationId) {
        requireProvider(organizationId);
        return mapper.findOperations(organizationId, OPERATION_HISTORY_LIMIT).stream()
                .map(row -> new OpsMerchantAccountOperationView(row.id(),
                        OpsMerchantAccountAction.valueOf(row.action()), row.detail(), row.operatorName(),
                        row.occurredAt()))
                .toList();
    }

    /** 登记（第一次，不带 version）或修改（带列表里读到的 version）。 */
    @Transactional
    public OpsMerchantAccountView save(UUID organizationId, String subMchid, Boolean active, Integer version,
                                       Operator operator) {
        requireOperator(operator);
        String code = requireUsable(subMchid);
        if (active == null) {
            throw new IllegalArgumentException("active is required");
        }
        lockProvider(organizationId);
        AccountRow current = mapper.findAccount(organizationId);
        OffsetDateTime at = now();
        if (!current.registered()) {
            if (version != null) {
                throw new OpsMerchantAccountException(Error.VERSION_CONFLICT);
            }
            if (mapper.insertAccount(organizationId, code, active, at) == null) {
                throw new OpsMerchantAccountException(Error.VERSION_CONFLICT);
            }
            record(organizationId, OpsMerchantAccountAction.REGISTER,
                    "登记收款账户：二级商户号 " + code + "，" + (active ? "收款" : "暂停收款"), operator, at);
        } else {
            if (version == null || mapper.updateAccount(organizationId, code, active, version, at) == null) {
                throw new OpsMerchantAccountException(Error.VERSION_CONFLICT);
            }
            record(organizationId, OpsMerchantAccountAction.CHANGE, change(current, code, active), operator, at);
        }
        return view(mapper.findAccount(organizationId));
    }

    /**
     * 批量导入（opsimport 的 MERCHANT_ACCOUNTS 类，在它的事务里调）：没有就登记，有就改，不比版本；记一条 IMPORT 记录。
     * 返回 true 表示新登记。
     */
    @Transactional
    public boolean importAccount(UUID organizationId, String subMchid, boolean active, String batchLabel,
                                 Operator operator) {
        requireOperator(operator);
        String code = requireUsable(subMchid);
        lockProvider(organizationId);
        AccountRow current = mapper.findAccount(organizationId);
        OffsetDateTime at = now();
        if (current.registered()) {
            mapper.updateAccount(organizationId, code, active, null, at);
            record(organizationId, OpsMerchantAccountAction.IMPORT, "批量导入（" + batchLabel + "）："
                    + change(current, code, active), operator, at);
            return false;
        }
        mapper.insertAccount(organizationId, code, active, at);
        record(organizationId, OpsMerchantAccountAction.IMPORT, "批量导入（" + batchLabel + "）：登记二级商户号 " + code
                + "，" + (active ? "收款" : "暂停收款"), operator, at);
        return true;
    }

    /** 商户号能不能用：格式，以及开发环境以外不接受占位号段。批量导入预检也用它。 */
    public String requireUsable(String subMchid) {
        String code = subMchid == null ? "" : subMchid.strip();
        if (!SUB_MCHID.matcher(code).matches()) {
            throw new IllegalArgumentException("sub_mchid is invalid");
        }
        if (!allowPlaceholder && PLACEHOLDER.matcher(code).matches()) {
            throw new OpsMerchantAccountException(Error.SUB_MCHID_PLACEHOLDER);
        }
        return code;
    }

    /** 开发环境以外拒绝的占位号段。 */
    public boolean placeholderRejected(String subMchid) {
        return !allowPlaceholder && subMchid != null && PLACEHOLDER.matcher(subMchid.strip()).matches();
    }

    private static String change(AccountRow current, String code, boolean active) {
        StringBuilder detail = new StringBuilder();
        if (!code.equals(current.subMchid())) {
            detail.append("二级商户号 ").append(current.subMchid()).append(" → ").append(code);
        }
        if (current.active() != active) {
            if (!detail.isEmpty()) {
                detail.append("，");
            }
            detail.append(active ? "恢复收款" : "暂停收款");
        }
        return detail.isEmpty() ? "保存收款账户（没有改动）" : detail.toString();
    }

    private void requireProvider(UUID organizationId) {
        String type = organizationId == null ? null : mapper.findOrganizationType(organizationId);
        if (type == null) {
            throw new OpsMerchantAccountException(Error.NOT_FOUND);
        }
        if (!"PROVIDER".equals(type)) {
            throw new OpsMerchantAccountException(Error.MERCHANT_UNKNOWN);
        }
    }

    private void lockProvider(UUID organizationId) {
        requireProvider(organizationId);
        mapper.lockOrganization(organizationId);
    }

    private void record(UUID organizationId, OpsMerchantAccountAction action, String detail, Operator operator,
                        OffsetDateTime at) {
        mapper.insertOperation(organizationId, action.name(), clip(detail, 500), operator.accountId(),
                clip(operator.displayName(), 80), at);
    }

    private static OpsMerchantAccountView view(AccountRow row) {
        return new OpsMerchantAccountView(row.organizationId(), row.organizationCode(), row.organizationName(),
                row.organizationStatus(), row.registered(), row.subMchid(), row.active(), row.updatedAt(),
                row.version());
    }

    static String keywordPattern(String keyword) {
        if (keyword == null) {
            return null;
        }
        String cleaned = keyword.replaceAll("[%_\\\\]", "").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_KEYWORD) {
            throw new IllegalArgumentException("keyword is too long");
        }
        return "%" + cleaned + "%";
    }

    private static String clip(String value, int max) {
        return value.codePointCount(0, value.length()) <= max
                ? value
                : value.substring(0, value.offsetByCodePoints(0, max));
    }

    private static void requireOperator(Operator operator) {
        if (operator == null || operator.accountId() == null || operator.displayName() == null
                || operator.displayName().isBlank()) {
            throw new IllegalArgumentException("operator is required");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    public record Operator(UUID accountId, String displayName) {
    }

    /** REGISTER 第一次登记；CHANGE 改商户号或收款开关；IMPORT 批量导入写的。 */
    public enum OpsMerchantAccountAction {
        REGISTER,
        CHANGE,
        IMPORT
    }

    public record OpsMerchantAccountView(UUID organizationId, String organizationCode, String organizationName,
                                         String organizationStatus, boolean registered, String subMchid,
                                         boolean active, OffsetDateTime updatedAt, Integer version) {
    }

    public record OpsMerchantAccountPageView(int page, int size, long total, boolean hasMore,
                                             List<OpsMerchantAccountView> items) {
    }

    public record OpsMerchantAccountOperationView(UUID operationId, OpsMerchantAccountAction action, String detail,
                                                  String operatorName, OffsetDateTime occurredAt) {
    }
}
