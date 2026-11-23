package com.lowaltitude.inspection.ops.common;

/**
 * 运营端（/api/v1/ops/…，S3-1）一类业务拒绝：HTTP 状态、稳定错误码（COMMON_NOT_FOUND 或 OPS_…）和给人看的中文说明。
 * 每块运营功能在自己的模块里写一个实现它的枚举（比如活动的 ActivityOpsError），抛 {@link OpsException}；
 * bootstrap 的 OpsExceptionHandler 统一翻成 ApiError，不用再改全局异常处理。契约测试按枚举的全部值核对错误码。
 */
public interface OpsError {

    /** HTTP 状态码（404、409、422、503 等）。 */
    int httpStatus();

    /** 稳定错误码：COMMON_NOT_FOUND，或者契约里列的 OPS_…。 */
    String code();

    /** 中文说明（页面另有自己的翻译，这里给没有翻译时兜底）。 */
    String message();

    /** 公共的几类，各模块的枚举可以直接用同样的码。 */
    String NOT_FOUND = "COMMON_NOT_FOUND";
    String VERSION_CONFLICT = "OPS_VERSION_CONFLICT";
    String PUBLISH_INCOMPLETE = "OPS_PUBLISH_INCOMPLETE";
}
