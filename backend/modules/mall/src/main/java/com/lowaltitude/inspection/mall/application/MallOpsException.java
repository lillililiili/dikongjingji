package com.lowaltitude.inspection.mall.application;

import java.util.List;

/** 运营端“商品与供给”的业务拒绝；状态码和错误码由全局异常处理按 error 映射（契约 OpsCatalogErrorCode）。 */
public final class MallOpsException extends RuntimeException {

    public enum Error {
        /** 商品、规格或供给不存在（已删除的商品也当作不存在）。 */
        NOT_FOUND,
        /** 页面读到的版本已经不是当前版本。 */
        VERSION_CONFLICT,
        /** 幂等键已用于内容不同的新建请求。 */
        IDEMPOTENCY_CONFLICT,
        /** 已经是这个状态。 */
        STATUS_UNCHANGED,
        /** 上架前缺必填（blockers 说明缺什么）。 */
        PRODUCT_INCOMPLETE,
        /** 规格已停用，或购买、租赁供给缺价格。 */
        OFFER_INCOMPLETE,
        /** 同一个规格、商家、城市已经有一条供给。 */
        OFFER_DUPLICATE,
        /** 规格数量到上限。 */
        SKU_LIMIT,
        /** 商家不是在用的经营机构。 */
        MERCHANT_UNKNOWN,
        /** 标价的购买供给，商家没有有效收款账户。 */
        MERCHANT_NOT_COLLECTING,
        /** 城市不在平台城市目录里。 */
        CITY_UNKNOWN,
        /** 图片地址不是受控地址。 */
        MEDIA_URL_UNCONTROLLED,
        /** 图片存储没有配置。 */
        FILE_STORE_UNAVAILABLE,
        /** 商家工作台：资质还没审核通过，不能上架。 */
        MERCHANT_NOT_QUALIFIED
    }

    private final Error error;
    private final List<String> blockers;

    public MallOpsException(Error error) {
        this(error, List.of());
    }

    public MallOpsException(Error error, List<String> blockers) {
        super(error.name());
        this.error = error;
        this.blockers = List.copyOf(blockers);
    }

    public Error error() {
        return error;
    }

    /** PRODUCT_INCOMPLETE 时缺的那些（OpsPublishBlocker 的编码）。 */
    public List<String> blockers() {
        return blockers;
    }
}
