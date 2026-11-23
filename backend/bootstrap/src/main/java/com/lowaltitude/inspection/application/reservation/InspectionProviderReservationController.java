package com.lowaltitude.inspection.application.reservation;

import java.util.UUID;

import com.lowaltitude.inspection.application.reservation.InspectionReservationController.CustomerReservationPageResponse;
import com.lowaltitude.inspection.application.reservation.InspectionReservationController.CustomerReservationSummaryResponse;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务商工作台的巡检预约列表（契约 openapi-inspection-service-flow.yaml 2.3.0，S3-2）：分给本机构的预约，
 * 形状和客户的“我的订单”一样。要 PROVIDER_OPERATOR 角色和 inspection.reservation.read.assigned 权限；
 * 机构只从令牌里取，别的机构的预约当作不存在。出方案、安排、排期、履约、交付仍用原来的受权接口。
 */
@RestController
@RequestMapping("/api/v1/provider/inspection/reservations")
public final class InspectionProviderReservationController {

    private static final String READ_ASSIGNED_PERMISSION = "inspection.reservation.read.assigned";
    private static final int MAX_PAGE_SIZE = 50;

    private final CustomerReservationQueryService queryService;

    public InspectionProviderReservationController(CustomerReservationQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public CustomerReservationPageResponse list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "ALL") String stage,
            Authentication authentication
    ) {
        UUID providerId = provider(authentication);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE || (long) (page - 1) * size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("provider reservation page is invalid");
        }
        return CustomerReservationPageResponse.from(
                queryService.listAssigned(providerId, CustomerReservationFilter.parse(stage), page, size));
    }

    @GetMapping("/{reservationId}")
    public CustomerReservationSummaryResponse read(@PathVariable UUID reservationId, Authentication authentication) {
        return CustomerReservationSummaryResponse.from(
                queryService.readAssigned(provider(authentication), reservationId));
    }

    private static UUID provider(Authentication authentication) {
        CurrentSubject subject = InspectionReservationController.currentSubject(authentication);
        if (!subject.roles().contains("PROVIDER_OPERATOR")
                || !subject.permissions().contains(READ_ASSIGNED_PERMISSION)
                || subject.organizationId() == null) {
            throw new AccessDeniedException("provider assigned reservation permission is required");
        }
        return subject.organizationId();
    }
}
