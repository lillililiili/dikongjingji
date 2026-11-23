package com.lowaltitude.inspection.bootstrap.api;

import java.util.UUID;

import com.lowaltitude.inspection.account.application.AddressLimitException;
import com.lowaltitude.inspection.account.application.AddressNotFoundException;
import com.lowaltitude.inspection.activity.application.ActivityCheckinException;
import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException;
import com.lowaltitude.inspection.activity.application.ActivityNotFoundException;
import com.lowaltitude.inspection.demand.application.DemandException;
import com.lowaltitude.inspection.application.reservation.FileAccessDeniedException;
import com.lowaltitude.inspection.experience.application.VenueBookingException;
import com.lowaltitude.inspection.experience.application.VenueCheckinException;
import com.lowaltitude.inspection.checkin.application.CheckinRejectedException;
import com.lowaltitude.inspection.experience.application.VenueNotFoundException;
import com.lowaltitude.inspection.mall.application.MallCartException;
import com.lowaltitude.inspection.trade.application.TradeException;
import com.lowaltitude.inspection.mall.application.MallInquiryException;
import com.lowaltitude.inspection.mall.application.MallProductNotFoundException;
import com.lowaltitude.inspection.notification.application.NotificationNotFoundException;
import com.lowaltitude.inspection.proservice.application.ProServiceNotFoundException;
import com.lowaltitude.inspection.content.application.ContentException;
import com.lowaltitude.inspection.content.application.ContentNotFoundException;
import com.lowaltitude.inspection.rental.application.RentalException;
import com.lowaltitude.inspection.support.application.SupportException;
import com.lowaltitude.inspection.application.city.CityOperationException;
import com.lowaltitude.inspection.identity.application.OnboardingException;
import com.lowaltitude.inspection.live.application.LiveException;
import com.lowaltitude.inspection.application.reservation.FileCountExceededException;
import com.lowaltitude.inspection.application.reservation.FileTooLargeException;
import com.lowaltitude.inspection.application.reservation.FileTypeUnsupportedException;
import com.lowaltitude.inspection.application.reservation.FileUploadConflictException;
import com.lowaltitude.inspection.application.reservation.FileUploadValidationException;
import com.lowaltitude.inspection.application.reservation.IdempotencyConflictException;
import com.lowaltitude.inspection.application.reservation.InspectionCatalogCityNotEnabledException;
import com.lowaltitude.inspection.application.reservation.InspectionPortalConfigUnavailableException;
import com.lowaltitude.inspection.application.reservation.InspectionServiceNotFoundException;
import com.lowaltitude.inspection.application.reservation.ReservationAccessDeniedException;
import com.lowaltitude.inspection.application.reservation.ReservationCancellationNotAllowedException;
import com.lowaltitude.inspection.application.reservation.ReservationNotFoundException;
import com.lowaltitude.inspection.application.reservation.ReservationValidationException;
import com.lowaltitude.inspection.application.reservation.ReservationVersionConflictException;
import com.lowaltitude.inspection.application.reservation.TimeSlotFullException;
import com.lowaltitude.inspection.application.reservation.TimeSlotTooSoonException;
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
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({AccessDeniedException.class, ReservationAccessDeniedException.class})
    public ResponseEntity<ApiError> handleAccessDenied(
            RuntimeException exception,
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
            MissingServletRequestParameterException.class,
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

    @ExceptionHandler(TimeSlotTooSoonException.class)
    public ResponseEntity<ApiError> handleTimeSlotTooSoon(
            TimeSlotTooSoonException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(
                        "INS_TIME_SLOT_TOO_SOON",
                        "该时段需提前1小时预约，请重新选择",
                        traceId(request)));
    }

    @ExceptionHandler(ReservationCancellationNotAllowedException.class)
    public ResponseEntity<ApiError> handleReservationCancellationNotAllowed(
            ReservationCancellationNotAllowedException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError(
                        "INS_RESERVATION_NOT_CANCELLABLE",
                        "当前订单状态不允许取消",
                        traceId(request)));
    }

    @ExceptionHandler(ReservationVersionConflictException.class)
    public ResponseEntity<ApiError> handleReservationVersionConflict(
            ReservationVersionConflictException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED)
                .body(new ApiError(
                        "INS_VERSION_CONFLICT",
                        "页面数据已更新，请刷新后重试",
                        traceId(request)));
    }

    @ExceptionHandler(InspectionCatalogCityNotEnabledException.class)
    public ResponseEntity<ApiError> handleCatalogCityNotEnabled(
            InspectionCatalogCityNotEnabledException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(
                        "INS_CATALOG_CITY_NOT_ENABLED",
                        "当前城市暂未开通巡检服务",
                        traceId(request)));
    }

    @ExceptionHandler(InspectionPortalConfigUnavailableException.class)
    public ResponseEntity<ApiError> handlePortalConfigUnavailable(
            InspectionPortalConfigUnavailableException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiError(
                        "INS_CATALOG_CONFIG_UNAVAILABLE",
                        "入口配置暂不可用",
                        traceId(request)));
    }

    @ExceptionHandler({
            ReservationNotFoundException.class,
            InspectionServiceNotFoundException.class,
            ActivityNotFoundException.class,
            VenueNotFoundException.class,
            MallProductNotFoundException.class,
            NotificationNotFoundException.class,
            AddressNotFoundException.class,
            ProServiceNotFoundException.class,
            ContentNotFoundException.class
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
            case VISIT_STATE_INVALID -> {
                status = HttpStatus.CONFLICT;
                code = "INS_VISIT_STATE_INVALID";
                message = "这次巡检当前状态不允许此操作";
            }
            case VISIT_OUT_OF_ORDER -> {
                status = HttpStatus.CONFLICT;
                code = "INS_VISIT_OUT_OF_ORDER";
                message = "上一次巡检现场完成后才能开工";
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

    /** 需求与报价（F1，契约 openapi-demand.yaml 的 DemandErrorCode）。DEMAND_INCOMPLETE 的 details.field 指出第一处缺项。 */
    @ExceptionHandler(DemandException.class)
    public ResponseEntity<ApiError> handleDemand(DemandException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        String code;
        String message;
        java.util.Map<String, Object> details = null;
        switch (exception.error()) {
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case FORBIDDEN -> {
                status = HttpStatus.FORBIDDEN;
                code = "COMMON_FORBIDDEN";
                message = "无权限访问";
            }
            case IDEMPOTENCY_CONFLICT -> {
                code = "DEMAND_IDEMPOTENCY_KEY_REUSED";
                message = "幂等键已用于不同请求";
            }
            case INCOMPLETE -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "DEMAND_INCOMPLETE";
                String field = exception.field() == null ? "" : exception.field();
                message = switch (field) {
                    case "cityCode" -> "请选择服务城市";
                    case "address" -> "请填写服务地点";
                    case "scope" -> "请填写作业范围";
                    case "dueDate" -> "期望完成日期不能早于今天";
                    case "deliverables" -> "请至少选择一项交付物";
                    case "invitedProviderIds" -> "请邀请 1–3 家服务这个城市的服务商";
                    default -> "需求还没有填完整";
                };
                details = java.util.Map.of("field", field);
            }
            case STATE_INVALID -> {
                code = "DEMAND_STATE_INVALID";
                message = "需求当前状态不允许这个操作";
            }
            case VERSION_CONFLICT -> {
                status = HttpStatus.PRECONDITION_FAILED;
                code = "DEMAND_VERSION_CONFLICT";
                message = "需求已更新，请刷新后重试";
            }
            case QUOTING_CLOSED -> {
                code = "DEMAND_QUOTING_CLOSED";
                message = "报价已截止，不能再报价或改价";
            }
            case QUOTE_CHANGED -> {
                code = "DEMAND_QUOTE_CHANGED";
                message = "这份报价已更新或撤回，请刷新后再选";
            }
            case QUOTE_EXPIRED -> {
                code = "DEMAND_QUOTE_EXPIRED";
                message = "这份报价已过有效期";
            }
            case QUOTE_LOCKED -> {
                code = "DEMAND_QUOTE_LOCKED";
                message = "报价已被选中或需求已结束，不能再修改";
            }
            default -> throw new IllegalStateException("unhandled demand error " + exception.error());
        }
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, traceId(request), details));
    }

    /** 到馆预约（E10，契约 openapi-experience.yaml 1.1.0 的 VenueErrorCode）。 */
    @ExceptionHandler(RentalException.class)
    public ResponseEntity<ApiError> handleRental(RentalException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        String code;
        String message;
        switch (exception.error()) {
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case FORBIDDEN -> {
                status = HttpStatus.FORBIDDEN;
                code = "COMMON_FORBIDDEN";
                message = "无权限访问";
            }
            case IDEMPOTENCY_CONFLICT -> {
                code = "RENTAL_IDEMPOTENCY_KEY_REUSED";
                message = "幂等键已用于不同请求";
            }
            case OFFER_UNAVAILABLE -> {
                code = "RENTAL_OFFER_UNAVAILABLE";
                message = "这款设备现在不能在这个城市租用";
            }
            case PAYMENT_UNAVAILABLE -> {
                code = "RENTAL_PAYMENT_UNAVAILABLE";
                message = "该商家暂未开通在线支付";
            }
            case ADDRESS_NOT_FOUND -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "RENTAL_ADDRESS_NOT_FOUND";
                message = "使用地点不存在，请重新选择";
            }
            case STATE_INVALID -> {
                code = "RENTAL_STATE_INVALID";
                message = "当前状态不允许这个操作";
            }
            case VERSION_CONFLICT -> {
                status = HttpStatus.PRECONDITION_FAILED;
                code = "RENTAL_VERSION_CONFLICT";
                message = "询价单已更新，请刷新后重试";
            }
            case QUOTE_CHANGED -> {
                code = "RENTAL_QUOTE_CHANGED";
                message = "商家已更新报价，请核对后再确认";
            }
            case QUOTE_EXPIRED -> {
                code = "RENTAL_QUOTE_EXPIRED";
                message = "报价已过期，请重新询价或联系商家更新报价";
            }
            case SCHEDULE_UNAVAILABLE -> {
                code = "RENTAL_SCHEDULE_UNAVAILABLE";
                message = "商家这段时间不能出租";
            }
            case START_DATE_PASSED -> {
                code = "RENTAL_START_DATE_PASSED";
                message = "起租日已过，请重新询价";
            }
            case TERMS_NOT_AGREED -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "RENTAL_TERMS_NOT_AGREED";
                message = "请先核对租期、费用与合同条款";
            }
            case REFUND_NOT_ALLOWED -> {
                code = "RENTAL_REFUND_NOT_ALLOWED";
                message = "这张订单现在不能在线退款";
            }
            default -> throw new IllegalStateException("unhandled rental error " + exception.error());
        }
        return ResponseEntity.status(status).body(new ApiError(code, message, traceId(request)));
    }

    @ExceptionHandler(VenueBookingException.class)
    public ResponseEntity<ApiError> handleVenueBooking(
            VenueBookingException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.CONFLICT;
        String code;
        String message;
        switch (exception.error()) {
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case FORBIDDEN -> {
                status = HttpStatus.FORBIDDEN;
                code = "COMMON_FORBIDDEN";
                message = "无权限访问";
            }
            case IDEMPOTENCY_CONFLICT -> {
                code = "VENUE_IDEMPOTENCY_KEY_REUSED";
                message = "幂等键已用于不同请求";
            }
            case SESSION_NOT_OPEN -> {
                code = "VENUE_SESSION_NOT_OPEN";
                message = "这一场已经开始或停止预约，请换一场";
            }
            case PARTICIPANT_NOT_ELIGIBLE -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "VENUE_PARTICIPANT_NOT_ELIGIBLE";
                message = "有参与人不满这个项目的最低年龄";
            }
            case PARTICIPANT_ALREADY_BOOKED -> {
                code = "VENUE_PARTICIPANT_ALREADY_BOOKED";
                message = "有参与人已经约过这一场";
            }
            case SEATS_NOT_ENOUGH -> {
                code = "VENUE_SEATS_NOT_ENOUGH";
                message = "余位不够了，请减少人数或换一场";
            }
            case AMOUNT_CHANGED -> {
                code = "VENUE_AMOUNT_CHANGED";
                message = "项目价格有变化，请重新确认";
            }
            case PAYMENT_UNAVAILABLE -> {
                code = "VENUE_PAYMENT_UNAVAILABLE";
                message = "场馆暂未开通在线支付";
            }
            case VERSION_CONFLICT -> {
                status = HttpStatus.PRECONDITION_FAILED;
                code = "VENUE_VERSION_CONFLICT";
                message = "预约已更新，请刷新后重试";
            }
            case RESCHEDULE_NOT_ALLOWED -> {
                code = "VENUE_RESCHEDULE_NOT_ALLOWED";
                message = "这张预约现在不能改期";
            }
            case CANCEL_NOT_ALLOWED -> {
                code = "VENUE_CANCEL_NOT_ALLOWED";
                message = "这张预约现在不能取消";
            }
            case CHECKIN_NOT_OPEN -> {
                code = "VENUE_CHECKIN_NOT_OPEN";
                message = "不在核销时间内";
            }
            case CODE_NOT_VALID -> {
                code = "VENUE_CODE_NOT_VALID";
                message = "核销码已作废";
            }
            default -> throw new IllegalStateException("unhandled venue booking error " + exception.error());
        }
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, traceId(request)));
    }

    /** 核销工作台（S3-3，契约 openapi-checkin-workbench.yaml）：被拦下的核销，那条已落库的记录放在 details.record。 */
    @ExceptionHandler(CheckinRejectedException.class)
    public ResponseEntity<ApiError> handleCheckinRejected(
            CheckinRejectedException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(new ApiError(
                exception.code(),
                exception.message(),
                traceId(request),
                java.util.Map.of("record", exception.record())));
    }

    /** 核销工作台里活动凭证一半的业务拒绝。 */
    @ExceptionHandler(ActivityCheckinException.class)
    public ResponseEntity<ApiError> handleActivityCheckin(
            ActivityCheckinException exception,
            HttpServletRequest request
    ) {
        return checkinError(exception.reason().name(), request);
    }

    /** 核销工作台里到馆预约一半的业务拒绝，和活动的一半用同一套错误码。 */
    @ExceptionHandler(VenueCheckinException.class)
    public ResponseEntity<ApiError> handleVenueCheckin(
            VenueCheckinException exception,
            HttpServletRequest request
    ) {
        return checkinError(exception.reason().name(), request);
    }

    private ResponseEntity<ApiError> checkinError(String reason, HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        String code;
        String message;
        switch (reason) {
            case "NOT_FOUND" -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case "FORBIDDEN" -> {
                status = HttpStatus.FORBIDDEN;
                code = "COMMON_FORBIDDEN";
                message = "无权限访问";
            }
            case "NOT_OPEN" -> {
                code = "CHECKIN_NOT_OPEN";
                message = "还没到这一场的核销时间";
            }
            case "ACTION_NOT_ALLOWED" -> {
                code = "CHECKIN_ACTION_NOT_ALLOWED";
                message = "这条记录不能这样处理";
            }
            case "ALREADY_RESOLVED" -> {
                code = "CHECKIN_ALREADY_RESOLVED";
                message = "这条异常已经登记过";
            }
            case "RELEASE_NOT_ALLOWED" -> {
                code = "CHECKIN_RELEASE_NOT_ALLOWED";
                message = "这张凭证现在不能放行";
            }
            case "SEATS_NOT_ENOUGH" -> {
                code = "CHECKIN_SEATS_NOT_ENOUGH";
                message = "当前场次余位不足";
            }
            default -> throw new IllegalStateException("unhandled check-in error " + reason);
        }
        return ResponseEntity.status(status).body(new ApiError(code, message, traceId(request)));
    }

    @ExceptionHandler(ActivityEnrollmentException.class)
    public ResponseEntity<ApiError> handleActivityEnrollment(
            ActivityEnrollmentException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.CONFLICT;
        String code;
        String message;
        switch (exception.error()) {
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case FORBIDDEN -> {
                status = HttpStatus.FORBIDDEN;
                code = "COMMON_FORBIDDEN";
                message = "无权限访问";
            }
            case IDEMPOTENCY_CONFLICT -> {
                code = "ACT_IDEMPOTENCY_KEY_REUSED";
                message = "幂等键已用于不同请求";
            }
            case PARTICIPANT_DUPLICATE -> {
                code = "ACT_PARTICIPANT_DUPLICATE";
                message = "该参与人已在列表中，无需重复添加";
            }
            case PARTICIPANT_LIMIT -> {
                code = "ACT_PARTICIPANT_LIMIT";
                message = "参与人数量已达上限";
            }
            case PARTICIPANT_NOT_ELIGIBLE -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "ACT_PARTICIPANT_NOT_ELIGIBLE";
                message = "有参与人不符合这场活动的报名条件";
            }
            case PARTICIPANT_ALREADY_ENROLLED -> {
                code = "ACT_PARTICIPANT_ALREADY_ENROLLED";
                message = "有参与人已经报过这一场";
            }
            case ENROLLMENT_UNSUPPORTED -> {
                code = "ACT_ENROLLMENT_UNSUPPORTED";
                message = "该活动暂不支持在线报名";
            }
            case ENROLLMENT_NOT_OPEN -> {
                code = "ACT_ENROLLMENT_NOT_OPEN";
                message = "这一场已停止报名";
            }
            case SESSION_FULL -> {
                code = "ACT_SESSION_FULL";
                message = "名额不够了，请减少人数或换一场";
            }
            case CANCEL_NOT_ALLOWED -> {
                code = "ACT_CANCEL_NOT_ALLOWED";
                message = "活动已开始或已签到，不能取消";
            }
            case CHECKIN_NOT_OPEN -> {
                code = "ACT_CHECKIN_NOT_OPEN";
                message = "当前不在签到时间内";
            }
            case TICKET_NOT_VALID -> {
                code = "ACT_TICKET_NOT_VALID";
                message = "凭证已取消或还没付款";
            }
            case AMOUNT_CHANGED -> {
                code = "ACTIVITY_AMOUNT_CHANGED";
                message = "活动价格有变化，请重新确认";
            }
            case PAYMENT_UNAVAILABLE -> {
                code = "ACTIVITY_PAYMENT_UNAVAILABLE";
                message = "主办方暂未开通在线支付";
            }
            default -> throw new IllegalStateException(
                    "unhandled activity enrollment error " + exception.error());
        }
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, traceId(request)));
    }

    @ExceptionHandler(AddressLimitException.class)
    public ResponseEntity<ApiError> handleAddressLimit(
            AddressLimitException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ACCOUNT_ADDRESS_LIMIT", "收货地址数量已达上限", traceId(request)));
    }

    @ExceptionHandler(MallCartException.class)
    public ResponseEntity<ApiError> handleMallCart(
            MallCartException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.CONFLICT;
        String code;
        String message;
        switch (exception.error()) {
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case NOT_PURCHASABLE -> {
                code = "MALL_CART_NOT_PURCHASABLE";
                message = "租赁和询价商品不能加入购物车";
            }
            case NOT_SOLD_IN_CITY -> {
                code = "MALL_CART_NOT_SOLD_IN_CITY";
                message = "这个商家在当前城市不卖这件商品";
            }
            case CART_FULL -> {
                code = "MALL_CART_FULL";
                message = "购物车已满，请先清理一些商品";
            }
            case MULTIPLE_MERCHANTS -> {
                code = "MALL_CHECKOUT_MULTIPLE_MERCHANTS";
                message = "每次只能结算一个商家";
            }
            case ITEM_UNAVAILABLE -> {
                code = "MALL_CHECKOUT_ITEM_UNAVAILABLE";
                message = "有商品已失效，请回到购物车重新选择";
            }
            case OUT_OF_STOCK -> {
                code = "MALL_CART_OUT_OF_STOCK";
                message = "这个规格暂时缺货或现货不足";
            }
            default -> throw new IllegalStateException("unhandled mall cart error " + exception.error());
        }
        return ResponseEntity.status(status)
                .body(new ApiError(code, message, traceId(request)));
    }

    @ExceptionHandler(SupportException.class)
    public ResponseEntity<ApiError> handleSupport(SupportException exception, HttpServletRequest request) {
        HttpStatus status;
        String code;
        String message;
        switch (exception.error()) {
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case FORBIDDEN -> {
                status = HttpStatus.FORBIDDEN;
                code = "COMMON_FORBIDDEN";
                message = "无权限访问";
            }
            case IDEMPOTENCY_CONFLICT -> {
                status = HttpStatus.CONFLICT;
                code = "SUPPORT_IDEMPOTENCY_KEY_REUSED";
                message = "幂等键已用于不同请求";
            }
            case COUNTERPART_UNAVAILABLE -> {
                status = HttpStatus.CONFLICT;
                code = "SUPPORT_COUNTERPART_UNAVAILABLE";
                message = "对方暂时不能接收消息";
            }
            // 运营端处理反馈（契约 contracts/openapi-ops-feedback.yaml）。
            case ALREADY_HANDLED -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_FEEDBACK_ALREADY_HANDLED";
                message = "这条反馈已经处理过了";
            }
            case VERSION_CONFLICT -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_VERSION_CONFLICT";
                message = "数据已被他人修改，请刷新后重试";
            }
            default -> throw new IllegalStateException("unmapped support error " + exception.error());
        }
        return ResponseEntity.status(status).body(new ApiError(code, message, traceId(request)));
    }

    /** 发现页视频（契约 contracts/openapi-content.yaml 1.2.0）的业务拒绝。 */
    @ExceptionHandler(ContentException.class)
    public ResponseEntity<ApiError> handleContent(ContentException exception, HttpServletRequest request) {
        HttpStatus status;
        String code;
        String message;
        switch (exception.error()) {
            case UNAUTHORIZED -> {
                status = HttpStatus.UNAUTHORIZED;
                code = "COMMON_UNAUTHORIZED";
                message = "请先登录";
            }
            case FORBIDDEN -> {
                status = HttpStatus.FORBIDDEN;
                code = "COMMON_FORBIDDEN";
                message = "无权限访问";
            }
            case IDEMPOTENCY_CONFLICT -> {
                status = HttpStatus.CONFLICT;
                code = "CONTENT_IDEMPOTENCY_KEY_REUSED";
                message = "幂等键已用于不同请求";
            }
            case COMMENT_BLOCKED -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "CONTENT_COMMENT_BLOCKED";
                message = "评论里有不允许发布的内容";
            }
            case COMMENT_TOO_FREQUENT -> {
                status = HttpStatus.TOO_MANY_REQUESTS;
                code = "CONTENT_COMMENT_TOO_FREQUENT";
                message = "评论太频繁，请稍后再试";
            }
            default -> throw new IllegalStateException("unmapped content error " + exception.error());
        }
        return ResponseEntity.status(status).body(new ApiError(code, message, traceId(request)));
    }

    @ExceptionHandler(LiveException.class)
    public ResponseEntity<ApiError> handleLive(LiveException exception, HttpServletRequest request) {
        HttpStatus status;
        String code;
        String message;
        switch (exception.error()) {
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case FORBIDDEN -> {
                status = HttpStatus.FORBIDDEN;
                code = "COMMON_FORBIDDEN";
                message = "无权限访问";
            }
            case IDEMPOTENCY_CONFLICT -> {
                status = HttpStatus.CONFLICT;
                code = "LIVE_IDEMPOTENCY_KEY_REUSED";
                message = "幂等键已用于不同请求";
            }
            case NOT_SCHEDULED -> {
                status = HttpStatus.CONFLICT;
                code = "LIVE_ROOM_NOT_SCHEDULED";
                message = "直播已经开始或结束";
            }
            case NOT_LIVE -> {
                status = HttpStatus.CONFLICT;
                code = "LIVE_ROOM_NOT_LIVE";
                message = "直播不在进行中";
            }
            case ENDED -> {
                status = HttpStatus.CONFLICT;
                code = "LIVE_ROOM_ENDED";
                message = "直播已结束";
            }
            case COMMENT_BLOCKED -> {
                status = HttpStatus.CONFLICT;
                code = "LIVE_COMMENT_BLOCKED";
                message = "弹幕含有不允许发送的内容";
            }
            case COMMENT_TOO_FAST -> {
                status = HttpStatus.TOO_MANY_REQUESTS;
                code = "LIVE_COMMENT_TOO_FAST";
                message = "发得太快了，稍后再发";
            }
            case STREAM_UNAVAILABLE -> {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                code = "LIVE_STREAM_UNAVAILABLE";
                message = "直播服务暂不可用";
            }
            case CALLBACK_REJECTED -> {
                status = HttpStatus.UNAUTHORIZED;
                code = "LIVE_CALLBACK_REJECTED";
                message = "回调未通过校验";
            }
            default -> throw new IllegalStateException("unmapped live error " + exception.error());
        }
        return ResponseEntity.status(status).body(new ApiError(code, message, traceId(request)));
    }

    @ExceptionHandler(MallInquiryException.class)
    public ResponseEntity<ApiError> handleMallInquiry(MallInquiryException exception, HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        String code;
        String message;
        switch (exception.error()) {
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case FORBIDDEN -> {
                status = HttpStatus.FORBIDDEN;
                code = "COMMON_FORBIDDEN";
                message = "无权限访问";
            }
            case IDEMPOTENCY_CONFLICT -> {
                code = "MALL_INQUIRY_IDEMPOTENCY_KEY_REUSED";
                message = "幂等键已用于不同请求";
            }
            case OFFER_UNAVAILABLE -> {
                code = "MALL_INQUIRY_OFFER_UNAVAILABLE";
                message = "有商家在这个城市不再供应这件商品";
            }
            case MERCHANT_UNAVAILABLE -> {
                code = "MALL_INQUIRY_MERCHANT_UNAVAILABLE";
                message = "有商家暂未开通在线支付，不能向它询价";
            }
            case STATE_INVALID -> {
                code = "MALL_INQUIRY_STATE_INVALID";
                message = "询价单当前状态不允许这个操作";
            }
            case QUOTING_CLOSED -> {
                code = "MALL_INQUIRY_QUOTING_CLOSED";
                message = "报价已截止，不能再报价或改价";
            }
            case VERSION_CONFLICT -> {
                status = HttpStatus.PRECONDITION_FAILED;
                code = "MALL_INQUIRY_VERSION_CONFLICT";
                message = "询价单已更新，请刷新后重试";
            }
            case QUOTE_CHANGED -> {
                code = "MALL_INQUIRY_QUOTE_CHANGED";
                message = "这份报价已更新或撤回，请刷新后再选";
            }
            case QUOTE_EXPIRED -> {
                code = "MALL_INQUIRY_QUOTE_EXPIRED";
                message = "这份报价已过有效期，请重新询价";
            }
            case QUOTE_NOT_CHOSEN -> {
                code = "MALL_INQUIRY_QUOTE_NOT_CHOSEN";
                message = "只能按选定的报价下单";
            }
            default -> throw new IllegalStateException("unhandled mall inquiry error " + exception.error());
        }
        return ResponseEntity.status(status).body(new ApiError(code, message, traceId(request)));
    }

    @ExceptionHandler(TradeException.class)
    public ResponseEntity<ApiError> handleTrade(
            TradeException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.CONFLICT;
        String code;
        String message;
        switch (exception.error()) {
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case FORBIDDEN -> {
                status = HttpStatus.FORBIDDEN;
                code = "COMMON_FORBIDDEN";
                message = "无权限访问";
            }
            case IDEMPOTENCY_CONFLICT -> {
                code = "TRADE_IDEMPOTENCY_KEY_REUSED";
                message = "幂等键已用于不同请求";
            }
            case AMOUNT_CHANGED -> {
                code = "TRADE_AMOUNT_CHANGED";
                message = "商品价格有变化，请确认后重新提交";
            }
            case ADDRESS_REQUIRED -> {
                code = "TRADE_ADDRESS_REQUIRED";
                message = "请先添加收货地址";
            }
            case DELIVERY_UNAVAILABLE -> {
                code = "TRADE_DELIVERY_UNAVAILABLE";
                message = "这种配送方式现在不可用，请重新选择";
            }
            case MERCHANT_PAYMENT_UNAVAILABLE -> {
                code = "TRADE_MERCHANT_PAYMENT_UNAVAILABLE";
                message = "该商家暂未开通在线支付";
            }
            case ORDER_STATE_INVALID -> {
                code = "TRADE_ORDER_STATE_INVALID";
                message = "订单当前状态不允许这个操作";
            }
            case VERSION_CONFLICT -> {
                status = HttpStatus.PRECONDITION_FAILED;
                code = "TRADE_VERSION_CONFLICT";
                message = "订单已更新，请刷新后重试";
            }
            case ORDER_EXPIRED -> {
                code = "TRADE_ORDER_EXPIRED";
                message = "订单已超过付款时间";
            }
            case CHANNEL_UNAVAILABLE -> {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                code = "TRADE_CHANNEL_UNAVAILABLE";
                message = "微信支付暂不可用，请稍后再试";
            }
            case PICKUP_CODE_INVALID -> {
                code = "TRADE_PICKUP_CODE_INVALID";
                message = "取货码不对，或输错次数太多已锁定";
            }
            case REFUND_EXCEEDS_PAID -> {
                code = "TRADE_REFUND_EXCEEDS_PAID";
                message = "退款金额超过可退金额";
            }
            case REFUND_LIMIT_REACHED -> {
                code = "TRADE_REFUND_LIMIT_REACHED";
                message = "这笔订单已不能再退款";
            }
            case RECONCILIATION_NOT_READY -> {
                code = "TRADE_RECONCILIATION_NOT_READY";
                message = "这一天的账单还没有全部导入";
            }
            case QUOTE_ALREADY_ORDERED -> {
                code = "TRADE_INQUIRY_QUOTE_ORDERED";
                message = "这份报价已经下过单，请到商品订单里查看";
            }
            // 支付、退款服务会接住这两个；万一漏出来，对买家来说就是“订单状态变了”。
            case PAYMENT_IN_PROGRESS, REFUND_REQUIRED -> {
                code = "TRADE_ORDER_STATE_INVALID";
                message = "订单当前状态不允许这个操作";
            }
            default -> throw new IllegalStateException("unhandled trade error " + exception.error());
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

    /** 运营端“城市开通”（契约 contracts/openapi-ops-city.yaml）的业务拒绝。 */
    @ExceptionHandler(CityOperationException.class)
    public ResponseEntity<ApiError> handleCityOperation(CityOperationException exception,
                                                        HttpServletRequest request) {
        return switch (exception.error()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiError("COMMON_NOT_FOUND", "资源不存在", traceId(request)));
            case VERSION_CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiError("OPS_VERSION_CONFLICT", "数据已被他人修改，请刷新后重试", traceId(request)));
            case STATUS_UNCHANGED -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiError("OPS_CITY_STATUS_UNCHANGED", "城市已经是这个状态", traceId(request)));
        };
    }

    /** 运营端“机构入驻与资质审核”（契约 contracts/openapi-ops-organization.yaml）的业务拒绝。 */
    @ExceptionHandler(OnboardingException.class)
    public ResponseEntity<ApiError> handleOnboarding(OnboardingException exception, HttpServletRequest request) {
        HttpStatus status;
        String code;
        String message;
        switch (exception.error()) {
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case VERSION_CONFLICT -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_VERSION_CONFLICT";
                message = "数据已被他人修改，请刷新后重试";
            }
            case IDEMPOTENCY_CONFLICT -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_IDEMPOTENCY_KEY_REUSED";
                message = "幂等键已用于不同请求";
            }
            case CITY_UNKNOWN -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "OPS_CITY_UNKNOWN";
                message = "城市不在平台城市目录里";
            }
            case LOGIN_NAME_TAKEN -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_LOGIN_NAME_TAKEN";
                message = "登录名已被占用";
            }
            case ROLE_NOT_GRANTABLE -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "OPS_ROLE_NOT_GRANTABLE";
                message = "这类机构不能授这个角色";
            }
            case QUALIFICATION_NOT_PENDING -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_QUALIFICATION_NOT_PENDING";
                message = "资质资料不是待审核状态";
            }
            case QUALIFICATION_INCOMPLETE -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_QUALIFICATION_INCOMPLETE";
                message = "审核通过前至少要上传一份资质附件";
            }
            case ATTACHMENT_LIMIT -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_ATTACHMENT_LIMIT";
                message = "附件数量已到上限";
            }
            case FILE_STORE_UNAVAILABLE -> {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                code = "OPS_FILE_STORE_UNAVAILABLE";
                message = "附件存储暂不可用";
            }
            default -> throw new IllegalStateException("unmapped onboarding error " + exception.error());
        }
        return ResponseEntity.status(status).body(new ApiError(code, message, traceId(request)));
    }

    /** 运营端“商品与供给”（契约 contracts/openapi-ops-catalog.yaml）的业务拒绝。 */
    @ExceptionHandler(com.lowaltitude.inspection.mall.application.MallOpsException.class)
    public ResponseEntity<ApiError> handleMallOps(com.lowaltitude.inspection.mall.application.MallOpsException exception,
                                                  HttpServletRequest request) {
        java.util.Map<String, Object> details = null;
        HttpStatus status;
        String code;
        String message;
        switch (exception.error()) {
            case NOT_FOUND -> {
                status = HttpStatus.NOT_FOUND;
                code = "COMMON_NOT_FOUND";
                message = "资源不存在";
            }
            case VERSION_CONFLICT -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_VERSION_CONFLICT";
                message = "数据已被他人修改，请刷新后重试";
            }
            case IDEMPOTENCY_CONFLICT -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_IDEMPOTENCY_KEY_REUSED";
                message = "幂等键已用于不同请求";
            }
            case STATUS_UNCHANGED -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_STATUS_UNCHANGED";
                message = "已经是这个状态";
            }
            case OFFER_DUPLICATE -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_OFFER_DUPLICATE";
                message = "这个规格、商家、城市已经有一条供给";
            }
            case SKU_LIMIT -> {
                status = HttpStatus.CONFLICT;
                code = "OPS_SKU_LIMIT";
                message = "规格数量已到上限";
            }
            case PRODUCT_INCOMPLETE -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "OPS_PRODUCT_INCOMPLETE";
                message = "上架前还有必填没有完成";
                details = java.util.Map.of("blockers", exception.blockers());
            }
            case OFFER_INCOMPLETE -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "OPS_OFFER_INCOMPLETE";
                message = "规格已停用或缺少价格，不能启用";
            }
            case MERCHANT_UNKNOWN -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "OPS_MERCHANT_UNKNOWN";
                message = "商家不是在用的经营机构";
            }
            case MERCHANT_NOT_COLLECTING -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "OPS_MERCHANT_NOT_COLLECTING";
                message = "商家没有有效的收款账户";
            }
            case CITY_UNKNOWN -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "OPS_CITY_UNKNOWN";
                message = "城市不在平台城市目录里";
            }
            case MEDIA_URL_UNCONTROLLED -> {
                status = HttpStatus.UNPROCESSABLE_ENTITY;
                code = "OPS_MEDIA_URL_UNCONTROLLED";
                message = "图片地址不是受控地址";
            }
            case FILE_STORE_UNAVAILABLE -> {
                status = HttpStatus.SERVICE_UNAVAILABLE;
                code = "OPS_FILE_STORE_UNAVAILABLE";
                message = "图片存储暂不可用";
            }
            default -> throw new IllegalStateException("unmapped mall ops error " + exception.error());
        }
        return ResponseEntity.status(status).body(new ApiError(code, message, traceId(request), details));
    }

    /** 运营端“收款账户登记”（契约 contracts/openapi-ops-catalog.yaml）的业务拒绝。 */
    @ExceptionHandler(com.lowaltitude.inspection.trade.application.OpsMerchantAccountException.class)
    public ResponseEntity<ApiError> handleMerchantAccount(
            com.lowaltitude.inspection.trade.application.OpsMerchantAccountException exception,
            HttpServletRequest request) {
        return switch (exception.error()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiError("COMMON_NOT_FOUND", "资源不存在", traceId(request)));
            case VERSION_CONFLICT -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiError("OPS_VERSION_CONFLICT", "数据已被他人修改，请刷新后重试", traceId(request)));
            case MERCHANT_UNKNOWN -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ApiError("OPS_MERCHANT_UNKNOWN", "不是经营机构", traceId(request)));
            case SUB_MCHID_PLACEHOLDER -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ApiError("OPS_SUB_MCHID_PLACEHOLDER", "开发占位商户号只能在开发环境使用", traceId(request)));
        };
    }

    /** 运营端“批量导入”（契约 contracts/openapi-ops-import.yaml）的业务拒绝。 */
    @ExceptionHandler(com.lowaltitude.inspection.opsimport.application.OpsImportException.class)
    public ResponseEntity<ApiError> handleOpsImport(
            com.lowaltitude.inspection.opsimport.application.OpsImportException exception,
            HttpServletRequest request) {
        return switch (exception.error()) {
            case NOT_FOUND -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiError("COMMON_NOT_FOUND", "资源不存在", traceId(request)));
            case HAS_ISSUES -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiError("OPS_IMPORT_HAS_ISSUES", "这个批次有问题没解决，不能导入", traceId(request)));
            case NOT_WRITABLE -> ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new ApiError("OPS_IMPORT_NOT_WRITABLE", "这一类这一版只能预检", traceId(request)));
            case FILE_STORE_UNAVAILABLE -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiError("OPS_FILE_STORE_UNAVAILABLE", "导入文件存储暂不可用", traceId(request)));
        };
    }

    /** 上传的文件超过大小上限，或少了文件部分。 */
    @ExceptionHandler({
            org.springframework.web.multipart.MaxUploadSizeExceededException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.web.multipart.MultipartException.class
    })
    public ResponseEntity<ApiError> handleMultipart(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("COMMON_INVALID_ARGUMENT", "请求参数无效", traceId(request)));
    }

    private String traceId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
}
