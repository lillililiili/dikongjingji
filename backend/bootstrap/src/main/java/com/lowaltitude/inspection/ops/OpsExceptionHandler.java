package com.lowaltitude.inspection.ops;

import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.ops.common.OpsException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 运营端各块（活动与场次、场馆、发现内容、直播、服务目录、素材上传……）的业务拒绝：各模块抛 OpsException，带自己的
 * OpsError 枚举（状态码、错误码、中文说明）和可选的 details（上线前缺的必填项）。排在全局异常处理前面，
 * 免得被它的 Exception 兜底成 500。
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OpsExceptionHandler {

    @ExceptionHandler(OpsException.class)
    public ResponseEntity<ApiError> handleOps(OpsException exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.error().httpStatus())
                .body(new ApiError(exception.error().code(), exception.error().message(), traceId(request),
                        exception.details()));
    }

    private static String traceId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
}
