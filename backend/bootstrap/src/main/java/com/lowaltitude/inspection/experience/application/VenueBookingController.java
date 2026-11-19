package com.lowaltitude.inspection.experience.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.VenueBookingException.Error;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 到馆预约和核销（E10，契约 contracts/openapi-experience.yaml 1.1.0）。都要登录。
 * 账号只从令牌里取，没有任何指定别人的参数；不是自己的预约一律当作不存在。
 * 参与人和预约要客户角色；核销要服务商操作员角色，并且只能核销本机构运营的场馆的码。
 * 返回预约的接口都带 ETag（预约版本），改期时用 If-Match 带回来。
 */
@RestController
public final class VenueBookingController {

    private static final String CUSTOMER = "CUSTOMER";
    private static final String PROVIDER_OPERATOR = "PROVIDER_OPERATOR";

    private final VenueBookingService service;
    private final IdentityProfilePort profiles;

    public VenueBookingController(VenueBookingService service, IdentityProfilePort profiles) {
        this.service = service;
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/venue-sessions/{sessionId}/participants")
    public VenueParticipantListView participants(Authentication authentication, @PathVariable UUID sessionId) {
        UUID accountId = customer(authentication);
        return service.listParticipants(accountId, accountName(accountId), sessionId);
    }

    @PostMapping("/api/v1/venue-bookings")
    public ResponseEntity<VenueBookingView> book(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody BookRequest request
    ) {
        UUID accountId = customer(authentication);
        BookingResult result = service.book(
                accountId,
                accountName(accountId),
                idempotencyKey,
                new BookCommand(
                        request.sessionId(),
                        request.participantIds(),
                        request.expectedAmountMinor(),
                        Boolean.TRUE.equals(request.noticeAgreed())));
        // 第一次创建返回 201；同一个幂等键重放返回 200 和同一张预约。
        return withVersion(ResponseEntity.status(result.created() ? 201 : 200), result.booking());
    }

    @GetMapping("/api/v1/venue-bookings")
    public VenueBookingPageView bookings(
            Authentication authentication,
            @RequestParam(required = false) String bucket,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listBookings(customer(authentication), bucket, page, size);
    }

    @GetMapping("/api/v1/venue-bookings/{bookingId}")
    public ResponseEntity<VenueBookingView> booking(Authentication authentication, @PathVariable UUID bookingId) {
        return withVersion(ResponseEntity.ok(), service.readBooking(customer(authentication), bookingId));
    }

    /** 改到同一项目的另一场。If-Match 是读预约时的 ETag（预约版本）。 */
    @PostMapping("/api/v1/venue-bookings/{bookingId}/reschedule")
    public ResponseEntity<VenueBookingView> reschedule(
            Authentication authentication,
            @PathVariable UUID bookingId,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody RescheduleRequest request
    ) {
        VenueBookingView booking = service.reschedule(
                customer(authentication), bookingId, version(ifMatch), request.sessionId());
        return withVersion(ResponseEntity.ok(), booking);
    }

    /** 待付款的关单；已付款的整单退款；免费的直接取消。 */
    @PostMapping("/api/v1/venue-bookings/{bookingId}/cancel")
    public ResponseEntity<VenueBookingView> cancel(Authentication authentication, @PathVariable UUID bookingId) {
        return withVersion(ResponseEntity.ok(), service.cancel(customer(authentication), bookingId));
    }

    @PostMapping("/api/v1/venue-checkins")
    public VenueCheckinView checkIn(Authentication authentication, @RequestBody CheckinRequest request) {
        Subject subject = subject(authentication);
        if (!subject.roles().contains(PROVIDER_OPERATOR) || subject.organizationId() == null) {
            throw new VenueBookingException(Error.FORBIDDEN);
        }
        return service.checkIn(subject.subjectId(), subject.organizationId(), request.code());
    }

    // ---- 工具 ----

    private String accountName(UUID accountId) {
        // “本人”第一次出现时用账号显示名；读不到资料就由参与人名单给一个默认称呼。
        return profiles.findProfile(accountId).map(IdentityProfile::displayName).orElse(null);
    }

    private static ResponseEntity<VenueBookingView> withVersion(ResponseEntity.BodyBuilder builder, VenueBookingView booking) {
        return builder.eTag(Integer.toString(booking.version())).body(booking);
    }

    /** If-Match 是 ETag：可能带引号或 W/ 前缀。 */
    static int version(String ifMatch) {
        String value = ifMatch == null ? "" : ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            int version = Integer.parseInt(value);
            if (version < 0) {
                throw new IllegalArgumentException("If-Match is invalid");
            }
            return version;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("If-Match is invalid", invalid);
        }
    }

    private static UUID customer(Authentication authentication) {
        Subject subject = subject(authentication);
        if (!subject.roles().contains(CUSTOMER)) {
            throw new VenueBookingException(Error.FORBIDDEN);
        }
        return subject.subjectId();
    }

    private static Subject subject(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentSubject current) {
            return new Subject(current.subjectId(), current.roles(), current.organizationId());
        }
        if (principal instanceof Jwt jwt) {
            try {
                List<String> roles = jwt.getClaimAsStringList("roles");
                String organization = jwt.getClaimAsString("organizationId");
                return new Subject(
                        UUID.fromString(jwt.getClaimAsString("subjectId")),
                        roles == null ? Set.of() : Set.copyOf(roles),
                        organization == null || organization.isBlank() ? null : UUID.fromString(organization));
            } catch (RuntimeException invalidClaim) {
                throw new AccessDeniedException("authenticated subject is invalid", invalidClaim);
            }
        }
        throw new AccessDeniedException("authenticated subject is invalid");
    }

    private record Subject(UUID subjectId, Set<String> roles, UUID organizationId) {
    }

    /**
     * @param expectedAmountMinor 页面看到的应付金额（分）= 单价 × 人数；免费项目带 0
     */
    record BookRequest(UUID sessionId, List<UUID> participantIds, Long expectedAmountMinor, Boolean noticeAgreed) {
    }

    record RescheduleRequest(UUID sessionId) {
    }

    record CheckinRequest(String code) {
    }
}
