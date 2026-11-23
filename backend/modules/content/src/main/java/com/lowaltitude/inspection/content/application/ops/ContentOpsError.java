package com.lowaltitude.inspection.content.application.ops;

import com.lowaltitude.inspection.ops.common.OpsError;

/** 运营端“发现内容”的业务拒绝（契约 OpsContentErrorCode）。 */
public enum ContentOpsError implements OpsError {
    NOT_FOUND(404, OpsError.NOT_FOUND, "资源不存在"),
    VERSION_CONFLICT(409, OpsError.VERSION_CONFLICT, "数据已被他人修改，请刷新后重试"),
    PUBLISH_INCOMPLETE(422, OpsError.PUBLISH_INCOMPLETE, "上线前还有必填项没填"),
    /** 城市不在平台城市目录里。 */
    CITY_UNKNOWN(422, "OPS_CITY_UNKNOWN", "城市不在平台城市目录里"),
    /** 作者、发布方或发布商家不是在用的服务商类机构，或者案例写了平台署名。 */
    AUTHOR_INVALID(422, "OPS_CONTENT_AUTHOR_INVALID", "作者要是在用的服务商或商家；案例不能由平台发布"),
    /** 状态不允许这个操作（下线没上线的、上线已上线的）。 */
    STATUS_CONFLICT(409, "OPS_CONTENT_STATUS_CONFLICT", "内容当前的状态不能做这个操作"),
    /** 专题挂的图文不存在或是视频。 */
    ARTICLE_UNKNOWN(422, "OPS_CONTENT_ARTICLE_UNKNOWN", "专题里挂的图文不存在"),
    /** 专题商家内容的商品在专题城市没有在售。 */
    PRODUCT_UNKNOWN(422, "OPS_CONTENT_PRODUCT_UNKNOWN", "商品不存在，或在专题的城市没有在售");

    private final int status;
    private final String code;
    private final String message;

    ContentOpsError(int status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    @Override
    public int httpStatus() {
        return status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
