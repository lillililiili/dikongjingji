package com.lowaltitude.inspection.ops;

import java.time.LocalDate;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.account.OpsUserLookupService;
import com.lowaltitude.inspection.identity.application.account.OpsUserLookupService.OpsUserDetailView;
import com.lowaltitude.inspection.identity.application.account.OpsUserLookupService.OpsUserPageView;
import com.lowaltitude.inspection.identity.application.account.OpsUserLookupService.ViewTarget;
import com.lowaltitude.inspection.ops.common.OpsError;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.trade.application.OpsOrderLookupService;
import com.lowaltitude.inspection.trade.application.OpsOrderLookupService.OpsOrderDetailView;
import com.lowaltitude.inspection.trade.application.OpsOrderLookupService.OpsOrderPageView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“订单查询”“用户查询”（契约 contracts/openapi-ops-lookup.yaml 1.0.0）。平台运营和平台客服都能用，只读。
 * 订单在交易模块、账号在身份模块，这里把两边接起来：用户详情里的订单数来自交易模块。打开详情时先读，读到了再记查看记录，
 * 记不下来就不给看。
 */
@RestController
public final class OpsLookupController {

    private static final String ORDERS = "/api/v1/ops/orders";
    private static final String USERS = "/api/v1/ops/users";

    private final OpsOrderLookupService orders;
    private final OpsUserLookupService users;
    private final OpsActors actors;

    public OpsLookupController(OpsOrderLookupService orders, OpsUserLookupService users, OpsActors actors) {
        this.orders = orders;
        this.users = users;
        this.actors = actors;
    }

    @GetMapping(ORDERS)
    public OpsOrderPageView orders(
            Authentication authentication,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID buyerId,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.staff(authentication);
        return orders.search(q, buyerId, source, status, from, to, page, size);
    }

    @GetMapping(ORDERS + "/{orderId}")
    public OpsOrderDetailView order(Authentication authentication, @PathVariable UUID orderId) {
        OpsActor actor = actors.staff(authentication);
        OpsOrderDetailView detail = orders.detail(orderId)
                .orElseThrow(() -> new OpsException(LookupError.NOT_FOUND));
        users.recordView(actor.accountId(), ViewTarget.TRADE_ORDER, orderId);
        return detail;
    }

    @GetMapping(USERS)
    public OpsUserPageView users(
            Authentication authentication,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.staff(authentication);
        return users.search(q, page, size);
    }

    @GetMapping(USERS + "/{userId}")
    public OpsUserDetailView user(Authentication authentication, @PathVariable UUID userId) {
        OpsActor actor = actors.staff(authentication);
        OpsUserDetailView detail = users.detail(userId, orders::countByBuyer)
                .orElseThrow(() -> new OpsException(LookupError.NOT_FOUND));
        users.recordView(actor.accountId(), ViewTarget.USER, userId);
        return detail;
    }

    /** 查不到的订单或账号。 */
    enum LookupError implements OpsError {
        NOT_FOUND;

        @Override
        public int httpStatus() {
            return 404;
        }

        @Override
        public String code() {
            return "COMMON_NOT_FOUND";
        }

        @Override
        public String message() {
            return "订单或账号不存在";
        }
    }
}
