package com.lowaltitude.inspection.bootstrap.api;

import java.util.UUID;

import com.lowaltitude.inspection.application.reservation.FileAccessDeniedException;
import com.lowaltitude.inspection.application.reservation.FileCountExceededException;
import com.lowaltitude.inspection.application.reservation.FileTooLargeException;
import com.lowaltitude.inspection.application.reservation.FileTypeUnsupportedException;
import com.lowaltitude.inspection.application.reservation.FileUploadConflictException;
import com.lowaltitude.inspection.application.reservation.FileUploadValidationException;
import com.lowaltitude.inspection.application.reservation.IdempotencyConflictException;
import com.lowaltitude.inspection.application.reservation.InspectionServiceNotFoundException;
import com.lowaltitude.inspection.application.reservation.ReservationNotFoundException;
import com.lowaltitude.inspection.application.reservation.ReservationValidationException;
import com.lowaltitude.inspection.application.reservation.TimeSlotFullException;
import com.lowaltitude.inspection.application.serviceflow.PlanFlowException;
import com.lowaltitude.inspection.infrastructure.storage.ObjectStorageException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(
            AccessDeniedException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("COMMON_FORBIDDEN", "无权限访问", traceId(request)));
    }

    @ExceptionHandler(FileAccessDeniedException.class)
    public ResponseEntity<ApiError> handleFileAccessDenied(
            FileAccessDeniedException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("COMMON_FORBIDDEN", "无权限访问", traceId(request)));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("COMMON_INVALID_ARGUMENT", "请求参数无效", traceId(request)));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiError> handleUnreadableRequest(
            Exception exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("COMMON_INVALID_ARGUMENT", "请求参数无效", traceId(request)));
    }

    @ExceptionHandler(ReservationValidationException.class)
    public ResponseEntity<ApiError> handleReservationValidation(
            ReservationValidationException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.unprocessableEntity()
                .body(new ApiError("COMMON_INVALID_ARGUMENT", "预约信息无效", traceId(request)));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ApiError> handleIdempotencyConflict(
            IdempotencyConflictException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(
                        "INS_IDEMPOTENCY_KEY_REUSED",
                        "幂等键已用于不同请求",
                        traceId(request)));
    }

    @ExceptionHandler(TimeSlotFullException.class)
    public ResponseEntity<ApiError> handleTimeSlotFull(
            TimeSlotFullException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(
                        "INS_TIME_SLOT_FULL",
                        "该时段刚刚约满，请重新选择",
                        traceId(request)));
    }

    @ExceptionHandler({
            ReservationNotFoundException.class,
            InspectionServiceNotFoundException.class
    })
    public ResponseEntity<ApiError> handleReservationNotFound(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("COMMON_NOT_FOUND", "资源不存在", traceId(request)));
    }

    @ExceptionHandler(PlanFlowException.class)
    public ResponseEntity<ApiError> handlePlanFlow(
            PlanFlowException exception,
            HttpServletRequest request
    ) {
        HttpStatus status;
        String code;
        String message;
        switch (exception.error()) {
            case INVALID_ARGUMENT -> {
                status = HttpStatus.BAD_REQUEST;
                code = "COMMON_INVALID_ARGUMENT";
                message = "方案信息无效";
            }
            case FORBIDDEN -> {
                status = HttpStatus.FORBIDDEN;
                code = "COMMON_FORBIDDEN";
                message = "无权限访问";
            }
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case IDEMPOTENCY_CONFLICT -> {
                status = HttpStatus.CONFLICT;
                code = "INS_IDEMPOTENCY_KEY_REUSED";
                message = "幂等键已用于不同请求";
            }
            case VERSION_CONFLICT -> {
                status = HttpStatus.PRECONDITION_FAILED;
                code = "INS_VERSION_CONFLICT";
                message = "页面数据已更新，请刷新后重试";
            }
            case RESERVATION_STATE_INVALID -> {
                status = HttpStatus.CONFLICT;
                code = "INS_RESERVATION_STATE_INVALID";
                message = "预约状态不允许发布方案";
            }
            case PLAN_NOT_PUBLISHED -> {
                status = HttpStatus.CONFLICT;
                code = "INS_PLAN_NOT_PUBLISHED";
                message = "方案尚未发布或已失效";
            }
            case PLAN_EXPIRED -> {
                status = HttpStatus.CONFLICT;
                code = "INS_PLAN_EXPIRED";
                message = "方案已过期";
            }
            case PLAN_ALREADY_CONFIRMED -> {
                status = HttpStatus.CONFLICT;
                code = "INS_PLAN_ALREADY_CONFIRMED";
                message = "方案已确认";
            }
            case ORDER_STATE_INVALID -> {
                status = HttpStatus.CONFLICT;
                code = "INS_ORDER_STATE_INVALID";
                message = "服务单当前状态不允许此操作";
            }
            case DELIVERY_STATE_INVALID -> {
                status = HttpStatus.CONFLICT;
                code = "INS_DELIVERY_STATE_INVALID";
                message = "交付当前状态不允许此操作";
            }
            case DELIVERY_FILES_INVALID -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "INS_DELIVERY_FILES_INVALID";
                message = "交付文件不完整或无效";
            }
            default -> throw new IllegalStateException(
                    "unhandled plan error " + exception.error());
        }
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, traceId(request)));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(
            NoResourceFoundException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("COMMON_NOT_FOUND", "资源不存在", traceId(request)));
    }

    @ExceptionHandler(FileUploadValidationException.class)
    public ResponseEntity<ApiError> handleFileUploadValidation(
            FileUploadValidationException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.badRequest()
                .body(new ApiError("COMMON_INVALID_ARGUMENT", "文件参数无效", traceId(request)));
    }

    @ExceptionHandler(FileTypeUnsupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedFileType(
            FileTypeUnsupportedException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(new ApiError(
                        "INS_FILE_TYPE_UNSUPPORTED",
                        "不支持该文件类型",
                        traceId(request)));
    }

    @ExceptionHandler({FileTooLargeException.class, FileCountExceededException.class})
    public ResponseEntity<ApiError> handleFileLimit(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        String code = exception instanceof FileTooLargeException
                ? "INS_FILE_TOO_LARGE"
                : "INS_FILE_COUNT_EXCEEDED";
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError(code, "文件超出允许限制", traceId(request)));
    }

    @ExceptionHandler(FileUploadConflictException.class)
    public ResponseEntity<ApiError> handleFileUploadConflict(
            FileUploadConflictException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(
                        "INS_ATTACHMENT_NOT_READY",
                        "文件状态不允许当前操作",
                        traceId(request)));
    }

    @ExceptionHandler(ObjectStorageException.class)
    public ResponseEntity<ApiError> handleObjectStorageFailure(
            ObjectStorageException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(
                        "INS_STORAGE_UNAVAILABLE",
                        "文件存储暂不可用，请稍后重试",
                        traceId(request)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        String traceId = traceId(request);
        LOGGER.error("Unhandled API failure traceId={}", traceId, exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("COMMON_INTERNAL_ERROR", "系统繁忙，请稍后重试", traceId));
    }

    private String traceId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
}
