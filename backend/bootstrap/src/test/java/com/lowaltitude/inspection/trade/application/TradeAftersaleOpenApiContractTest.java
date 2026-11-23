package com.lowaltitude.inspection.trade.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.lowaltitude.inspection.ops.OpsContractChecker;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleActionsView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleCountsView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleEligibilityView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleEventView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleImageUploadView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleImageView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleMerchantView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleOptionView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersalePageView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleReasonOptionView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleRefundView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleReturnAddressView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.AftersaleWaybillView;
import com.lowaltitude.inspection.trade.application.TradeAftersaleViews.ReturnAddressView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeMediaView;
import com.lowaltitude.inspection.trade.application.TradeViews.TradeOrderLineView;
import org.junit.jupiter.api.Test;

/**
 * 契约 contracts/openapi-trade-aftersale.yaml 1.0.0 和两个售后控制器逐项对照（S4-2）：路由、参数、请求体、响应对象的字段和类型、
 * 枚举、错误码、限值。路由不在 /api/v1/ops 下，所以路由一项自己查，其余交给 OpsContractChecker。
 */
class TradeAftersaleOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-trade-aftersale.yaml",
            TradeAftersaleController.class, TradeAftersaleMerchantController.class)
            .response("Aftersale", AftersaleView.class)
            .response("AftersaleMerchant", AftersaleMerchantView.class)
            .response("AftersaleReturnAddress", AftersaleReturnAddressView.class)
            .response("AftersaleWaybill", AftersaleWaybillView.class)
            .response("AftersaleRefund", AftersaleRefundView.class)
            .response("AftersaleImage", AftersaleImageView.class)
            .response("AftersaleEvent", AftersaleEventView.class)
            .response("AftersaleActions", AftersaleActionsView.class)
            .response("AftersalePage", AftersalePageView.class)
            .response("AftersaleCounts", AftersaleCountsView.class)
            .response("AftersaleEligibility", AftersaleEligibilityView.class)
            .response("AftersaleOption", AftersaleOptionView.class)
            .response("AftersaleReasonOption", AftersaleReasonOptionView.class)
            .response("AftersaleImageUpload", AftersaleImageUploadView.class)
            .response("ReturnAddress", ReturnAddressView.class)
            .response("TradeOrderLine", TradeOrderLineView.class)
            .response("MediaAsset", TradeMediaView.class)
            .request("ApplyAftersaleRequest", TradeAftersaleController.ApplyAftersaleRequest.class, "kind", "reason")
            .request("ReturnShipmentRequest", TradeAftersaleController.ReturnShipmentRequest.class,
                    "carrierName", "trackingNo")
            .request("ApproveAftersaleRequest", TradeAftersaleMerchantController.AgreeRequest.class)
            .request("RejectAftersaleRequest", TradeAftersaleMerchantController.RejectRequest.class, "reason")
            .request("AftersaleReceiptRequest", TradeAftersaleMerchantController.ReceiptRequest.class)
            .request("ReturnAddressRequest", TradeAftersaleMerchantController.ReturnAddressRequest.class,
                    "contactName", "phone", "address")
            .requestEnumField("ApplyAftersaleRequest.kind", "AftersaleKind")
            .requestEnumField("ApplyAftersaleRequest.reason", "AftersaleReason")
            .requestEnumField("Aftersale.kind", "AftersaleKind")
            .requestEnumField("Aftersale.reason", "AftersaleReason")
            .requestEnumField("Aftersale.status", "AftersaleStatus")
            .requestEnumField("AftersaleEvent.status", "AftersaleStatus")
            .requestEnumField("AftersaleOption.kind", "AftersaleKind")
            .requestEnumField("AftersaleReasonOption.reason", "AftersaleReason")
            .requestEnumField("AftersaleReasonOption.kind", "AftersaleKind")
            .enumeration("AftersaleKind", TradeAftersaleRules.Kind.class)
            .enumeration("AftersaleReason", TradeAftersaleRules.Reason.class)
            .enumeration("AftersaleStatus", TradeAftersaleRules.Status.class)
            .other("ApiError", "AftersaleErrorCode", "AftersaleTab", "AftersaleImageSlot", "AftersaleAction",
                    "AftersaleUnavailableReason");

    @Test
    void contractMatchesTheControllers() {
        assertThat(OpsContractChecker.map(checker.root().get("info")).get("version")).isEqualTo("1.0.0");
        checker.verifyParameters();
        checker.verifyBodies();
        checker.verifyResponseObjects();
        checker.verifyRequestObjects();
        checker.verifyFieldTypes();
        checker.verifyEnums();
        checker.verifyEverySchemaIsCovered();
    }

    @Test
    void everyRouteIsDeclaredNeedsALoginAndDeclaresTheRefusals() {
        List<OpsContractChecker.Operation> operations = checker.operations();
        Map<String, Object> paths = OpsContractChecker.map(checker.root().get("paths"));
        Set<String> declared = new TreeSet<>();
        paths.forEach((path, item) -> OpsContractChecker.map(item).keySet()
                .forEach(method -> declared.add(method.toUpperCase() + " " + path)));
        assertThat(declared).containsExactlyInAnyOrderElementsOf(
                operations.stream().map(OpsContractChecker.Operation::key).toList());
        assertThat(operations).allSatisfy(operation -> {
            Map<String, Object> item = OpsContractChecker.map(OpsContractChecker.map(paths.get(operation.path()))
                    .get(operation.httpMethod().toLowerCase()));
            assertThat(item.get("security")).as(operation.key()).isEqualTo(List.of(Map.of("bearerAuth", List.of())));
            Map<String, Object> responses = OpsContractChecker.map(item.get("responses"));
            assertThat(responses.keySet()).as(operation.key()).contains("401", "403");
            if (operation.path().contains("{")) {
                assertThat(responses).as(operation.key()).containsKey("404");
            }
            assertThat(operation.path()).as(operation.key()).matches("^/api/v1/(trade|provider/trade)/.*");
        });
    }

    @Test
    void errorCodesAreTheOnesTheServiceRaises() {
        Set<String> produced = new TreeSet<>();
        for (TradeAftersaleError error : TradeAftersaleError.values()) {
            if (!error.code().startsWith("COMMON_")) {
                produced.add(error.code());
            }
        }
        assertThat(OpsContractChecker.strings(OpsContractChecker.map(checker.schemas().get("AftersaleErrorCode"))
                .get("enum"))).containsExactlyInAnyOrderElementsOf(produced);
    }

    @Test
    void limitsAndListsAreTheOnesTheServiceUses() {
        assertThat(checker.text())
                .contains("maxLength: " + TradeAftersaleService.MAX_DESCRIPTION)
                .contains("maxItems: " + TradeAftersaleService.MAX_IMAGES)
                .contains("maximum: " + TradeAftersaleService.MAX_IMAGE_BYTES)
                .contains("maximum: " + TradeAftersaleService.MAX_PAGE_SIZE)
                .contains("一小时最多 " + TradeAftersaleService.MAX_UPLOADS_PER_HOUR + " 张")
                .contains("每格最多 " + TradeAftersaleService.MAX_IMAGES_PER_SLOT + " 张");
        assertThat(OpsContractChecker.strings(OpsContractChecker.map(checker.schemas().get("AftersaleImageSlot"))
                .get("enum"))).containsExactlyInAnyOrderElementsOf(TradeAftersaleService.SLOTS);
        assertThat(OpsContractChecker.strings(OpsContractChecker.map(checker.schemas().get("AftersaleTab"))
                .get("enum"))).containsExactly("ALL", "DOING", "DONE", "CLOSED");
        // 时限写在契约的说明里，和规则类一致。
        assertThat(checker.text()).contains("48 小时不处理自动同意")
                .contains("7 天内不填自动关闭")
                .contains("确认收货后 15 天内")
                .contains("运单提交后 10 天");
        assertThat(TradeAftersaleRules.REVIEW_WINDOW.toHours()).isEqualTo(48);
        assertThat(TradeAftersaleRules.RETURN_WINDOW.toDays()).isEqualTo(7);
        assertThat(TradeAftersaleRules.QUALITY_WINDOW.toDays()).isEqualTo(15);
        assertThat(TradeAftersaleRules.RECEIVE_WINDOW.toDays()).isEqualTo(10);
        assertThat(TradeAftersaleRules.NO_REASON_WINDOW.toDays()).isEqualTo(7);
    }
}
