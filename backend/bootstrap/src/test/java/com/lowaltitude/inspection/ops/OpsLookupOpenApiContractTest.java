package com.lowaltitude.inspection.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.lowaltitude.inspection.identity.application.account.OpsUserLookupService;
import com.lowaltitude.inspection.trade.application.OpsOrderLookupService;
import com.lowaltitude.inspection.trade.application.TradeOrderStatus;
import org.junit.jupiter.api.Test;

/** 契约 contracts/openapi-ops-lookup.yaml 和 OpsLookupController 逐项对照。 */
class OpsLookupOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-lookup.yaml",
            OpsLookupController.class)
            .response("OpsBuyer", OpsOrderLookupService.OpsBuyerView.class)
            .response("OpsOrderSummary", OpsOrderLookupService.OpsOrderSummaryView.class)
            .response("OpsOrderPage", OpsOrderLookupService.OpsOrderPageView.class)
            .response("OpsOrderDetail", OpsOrderLookupService.OpsOrderDetailView.class)
            .response("OpsOrderLine", OpsOrderLookupService.OpsOrderLineView.class)
            .response("OpsOrderDelivery", OpsOrderLookupService.OpsOrderDeliveryView.class)
            .response("OpsOrderPayment", OpsOrderLookupService.OpsOrderPaymentView.class)
            .response("OpsOrderRefund", OpsOrderLookupService.OpsOrderRefundView.class)
            .response("OpsOrderAftersale", OpsOrderLookupService.OpsOrderAftersaleView.class)
            .response("OpsOrderTransition", OpsOrderLookupService.OpsOrderTransitionView.class)
            .response("OpsUserSummary", OpsUserLookupService.OpsUserSummaryView.class)
            .response("OpsUserPage", OpsUserLookupService.OpsUserPageView.class)
            .response("OpsUserDetail", OpsUserLookupService.OpsUserDetailView.class)
            .response("OpsUserOrganization", OpsUserLookupService.OpsUserOrganizationView.class)
            .response("OpsUserEnterprise", OpsUserLookupService.OpsUserEnterpriseView.class)
            .enumeration("OpsOrderSource", OpsOrderLookupService.OpsOrderSource.class)
            .enumeration("OpsOrderStatus", TradeOrderStatus.class)
            .enumeration("OpsUserStatus", OpsUserLookupService.OpsUserStatus.class)
            .other("ApiError");

    @Test
    void contractMatchesTheController() {
        checker.verifyAll("1.0.0");
    }

    @Test
    void limitsAreTheOnesTheServicesEnforce() {
        assertThat(OpsOrderLookupService.MAX_PAGE_SIZE).isEqualTo(OpsUserLookupService.MAX_PAGE_SIZE);
        assertThat(checker.text()).contains("maximum: " + OpsOrderLookupService.MAX_PAGE_SIZE)
                .contains("1–" + OpsOrderLookupService.MAX_QUERY + " 字")
                .contains("1–" + OpsUserLookupService.MAX_QUERY + " 字")
                .contains("1–" + OpsUserLookupService.MAX_DISPLAY_NAME_QUERY + " 字")
                .contains("最长 " + OpsOrderLookupService.MAX_DAYS + " 天");
    }
}
