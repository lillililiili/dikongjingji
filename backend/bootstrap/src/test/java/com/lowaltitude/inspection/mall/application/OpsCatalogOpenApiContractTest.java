package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsCatalogOperationView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsIntroBlockView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsMediaView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsOfferView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsPackItemView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductDetailView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductPageView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductSummaryView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsSkuView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsSpecView;
import com.lowaltitude.inspection.ops.OpsContractChecker;
import com.lowaltitude.inspection.trade.application.OpsMerchantAccountController;
import com.lowaltitude.inspection.trade.application.OpsMerchantAccountException;
import com.lowaltitude.inspection.trade.application.OpsMerchantAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

/** 契约 contracts/openapi-ops-catalog.yaml 和 OpsCatalogController、OpsMerchantAccountController 逐项对照（S3-1）。 */
class OpsCatalogOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-catalog.yaml",
            OpsCatalogController.class, OpsMerchantAccountController.class)
            .response("OpsProductSummary", OpsProductSummaryView.class)
            .response("OpsProductPage", OpsProductPageView.class)
            .response("OpsProduct", OpsProductView.class)
            .response("OpsSpec", OpsSpecView.class)
            .response("OpsPackItem", OpsPackItemView.class)
            .response("OpsIntroBlock", OpsIntroBlockView.class)
            .response("OpsSku", OpsSkuView.class)
            .response("OpsOffer", OpsOfferView.class)
            .response("OpsCatalogOperation", OpsCatalogOperationView.class)
            .response("OpsProductDetail", OpsProductDetailView.class)
            .response("OpsMedia", OpsMediaView.class)
            .response("OpsMerchantAccount", OpsMerchantAccountService.OpsMerchantAccountView.class)
            .response("OpsMerchantAccountPage", OpsMerchantAccountService.OpsMerchantAccountPageView.class)
            .response("OpsMerchantAccountOperation", OpsMerchantAccountService.OpsMerchantAccountOperationView.class)
            .request("OpsProductCreateRequest", OpsCatalogController.CreateProductRequest.class,
                    "category", "name", "saleMode")
            .request("OpsProductUpdateRequest", OpsCatalogController.UpdateProductRequest.class,
                    "category", "name", "sellingPoints", "specs", "packItems", "gallery", "introBlocks", "version")
            .request("OpsProductStatusRequest", OpsCatalogController.StatusRequest.class, "action", "version")
            .request("OpsSkuCreateRequest", OpsCatalogController.CreateSkuRequest.class, "name")
            .request("OpsSkuUpdateRequest", OpsCatalogController.UpdateSkuRequest.class, "name", "status", "version")
            .request("OpsOfferCreateRequest", OpsCatalogController.CreateOfferRequest.class,
                    "skuId", "merchantId", "merchantKind", "priceUnit", "expressAvailable", "shippingFeeMinor")
            .request("OpsOfferUpdateRequest", OpsCatalogController.UpdateOfferRequest.class,
                    "merchantKind", "priceUnit", "expressAvailable", "shippingFeeMinor", "version")
            .request("OpsOfferStatusRequest", OpsCatalogController.OfferStatusRequest.class, "action", "version")
            .request("OpsMerchantAccountRequest", OpsMerchantAccountController.SaveRequest.class, "subMchid", "active")
            .requestEnumField("OpsProductCreateRequest.category", "MallCategory")
            .requestEnumField("OpsProductCreateRequest.saleMode", "MallSaleMode")
            .requestEnumField("OpsProductUpdateRequest.category", "MallCategory")
            .requestEnumField("OpsProductUpdateRequest.experienceBadge", "MallExperienceBadge")
            .requestEnumField("OpsProductStatusRequest.action", "OpsProductStatusAction")
            .requestEnumField("OpsSkuUpdateRequest.status", "OpsSkuStatus")
            .requestEnumField("OpsOfferCreateRequest.merchantKind", "MallMerchantKind")
            .requestEnumField("OpsOfferCreateRequest.priceUnit", "MallPriceUnit")
            .requestEnumField("OpsOfferUpdateRequest.merchantKind", "MallMerchantKind")
            .requestEnumField("OpsOfferUpdateRequest.priceUnit", "MallPriceUnit")
            .requestEnumField("OpsOfferStatusRequest.action", "OpsOfferStatusAction")
            .enumeration("MallCategory", MallCategory.class)
            .enumeration("MallSaleMode", MallSaleMode.class)
            .enumeration("MallExperienceBadge", MallExperienceBadge.class)
            .enumeration("MallMerchantKind", MallMerchantKind.class)
            .enumeration("MallPriceUnit", MallPriceUnit.class)
            .enumeration("OpsProductStatus", OpsProductStatus.class)
            .enumeration("OpsSkuStatus", OpsSkuStatus.class)
            .enumeration("OpsOfferStatus", OpsOfferStatus.class)
            .enumeration("OpsProductStatusAction", OpsProductStatusAction.class)
            .enumeration("OpsOfferStatusAction", OpsOfferStatusAction.class)
            .enumeration("OpsIntroBlockType", OpsIntroBlockType.class)
            .enumeration("OpsPublishBlocker", OpsPublishBlocker.class)
            .enumeration("OpsCatalogAction", OpsCatalogAction.class)
            .enumeration("OpsMerchantAccountAction", OpsMerchantAccountService.OpsMerchantAccountAction.class)
            .other("ApiError", "OpsCatalogErrorCode");

    @Test
    void contractMatchesTheControllers() {
        checker.verifyAll("1.4.0");
    }

    @Test
    void limitsAreTheOnesTheServicesEnforce() {
        Map<String, Object> detail = OpsContractChecker.map(OpsContractChecker.map(checker.schemas()
                .get("OpsProductDetail")).get("properties"));
        assertThat(OpsContractChecker.map(detail.get("sellingPoints")).get("maxItems"))
                .isEqualTo(MallCatalogOpsService.MAX_SELLING_POINTS);
        assertThat(OpsContractChecker.map(detail.get("specs")).get("maxItems")).isEqualTo(MallCatalogOpsService.MAX_SPECS);
        assertThat(OpsContractChecker.map(detail.get("gallery")).get("maxItems"))
                .isEqualTo(MallCatalogOpsService.MAX_GALLERY);
        assertThat(OpsContractChecker.map(detail.get("introBlocks")).get("maxItems"))
                .isEqualTo(MallCatalogOpsService.MAX_INTRO_BLOCKS);
        assertThat(OpsContractChecker.map(detail.get("skus")).get("maxItems")).isEqualTo(MallCatalogOpsService.MAX_SKUS);
        assertThat(checker.text())
                .contains("maxLength: " + MallCatalogOpsService.MAX_SELLING_POINT)
                .contains("maxLength: " + MallCatalogOpsService.MAX_NAME)
                .contains("maxLength: " + MallCatalogOpsService.MAX_SUMMARY)
                .contains("maxLength: " + MallCatalogOpsService.MAX_INTRO_TEXT)
                .contains("maximum: " + MallCatalogOpsService.MAX_STOCK)
                .contains("maximum: " + MallCatalogOpsService.MAX_PRICE_MINOR)
                .contains("maximum: " + MallCatalogOpsService.MAX_SHIPPING_FEE_MINOR)
                .contains("maximum: " + MallCatalogOpsService.MAX_PAGE_SIZE)
                .contains("maxLength: " + MallCatalogOpsService.MAX_KEYWORD)
                .contains("maxItems: " + MallCatalogOpsService.OPERATION_HISTORY_LIMIT)
                .contains("maxItems: " + OpsMerchantAccountService.OPERATION_HISTORY_LIMIT);
    }

    @Test
    void errorCodesAreTheOnesTheHandlerAnswers() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        Set<String> produced = new TreeSet<>();
        for (MallOpsException.Error error : MallOpsException.Error.values()) {
            ResponseEntity<ApiError> response = handler.handleMallOps(
                    new MallOpsException(error, List.of("NO_ACTIVE_SKU")), new MockHttpServletRequest());
            String code = response.getBody().code();
            if (code.startsWith("OPS_")) {
                produced.add(code);
            } else {
                assertThat(code).isEqualTo("COMMON_NOT_FOUND");
                assertThat(response.getStatusCode().value()).isEqualTo(404);
            }
        }
        for (OpsMerchantAccountException.Error error : OpsMerchantAccountException.Error.values()) {
            String code = handler.handleMerchantAccount(new OpsMerchantAccountException(error),
                    new MockHttpServletRequest()).getBody().code();
            if (code.startsWith("OPS_")) {
                produced.add(code);
            }
        }
        checker.verifyErrorCodes("OpsCatalogErrorCode", produced);
    }

    // 上架缺必填时 details.blockers 列出缺什么。
    @Test
    void incompleteProductsSayWhatIsMissing() {
        ResponseEntity<ApiError> response = new GlobalExceptionHandler().handleMallOps(
                new MallOpsException(MallOpsException.Error.PRODUCT_INCOMPLETE,
                        List.of("SUMMARY_MISSING", "NO_ACTIVE_OFFER")), new MockHttpServletRequest());

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().details()).isEqualTo(Map.of("blockers", List.of("SUMMARY_MISSING",
                "NO_ACTIVE_OFFER")));
    }
}
