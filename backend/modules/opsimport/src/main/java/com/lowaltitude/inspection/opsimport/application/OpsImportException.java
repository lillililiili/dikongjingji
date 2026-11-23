package com.lowaltitude.inspection.opsimport.application;

/** 批量导入的业务拒绝；错误码由全局异常处理映射（契约 OpsImportErrorCode）。 */
public final class OpsImportException extends RuntimeException {

    public enum Error {
        /** 批次不存在，或 kind 不认识。 */
        NOT_FOUND,
        /** 预检没通过，或导入前按库里的当前数据再校验时出了问题。 */
        HAS_ISSUES,
        /** 这一类这一版只能预检。 */
        NOT_WRITABLE,
        /** 导入文件的存储没有配置。 */
        FILE_STORE_UNAVAILABLE
    }

    private final Error error;

    public OpsImportException(Error error) {
        super(error.name());
        this.error = error;
    }

    public Error error() {
        return error;
    }
}
