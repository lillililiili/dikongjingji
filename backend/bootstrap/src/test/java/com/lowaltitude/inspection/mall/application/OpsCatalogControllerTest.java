package com.lowaltitude.inspection.mall.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsMediaView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductCreated;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductDetailView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductView;
import com.lowaltitude.inspection.ops.OpsTestSupport;
import com.lowaltitude.inspection.trade.application.OpsMerchantAccountController;
import com.lowaltitude.inspection.trade.application.OpsMerchantAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/** 运营端“商品与供给”和“收款账户”的控制器（S3-1）。 */
class OpsCatalogControllerTest {

    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000101");
    private static final UUID ORG = UUID.fromString("c1000000-0000-4000-8000-000000000006");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-23T02:00:00Z");
    private static final MallCatalogOpsService.Operator OPERATOR =
            new MallCatalogOpsService.Operator(OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME);

    private final MallCatalogOpsService catalog = mock(MallCatalogOpsService.class);
    private final OpsMerchantAccountService accounts = mock(OpsMerchantAccountService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(new OpsCatalogController(catalog, OpsTestSupport.actors()),
            new OpsMerchantAccountController(accounts, OpsTestSupport.actors()));

    private static OpsProductDetailView detail() {
        return new OpsProductDetailView(new OpsProductView(PRODUCT, MallCategory.ROBOT, "编程机器人", null, null,
                MallSaleMode.BUY, null, 0, OpsProductStatus.DRAFT, AT, AT, 0, null, null), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                List.of(OpsPublishBlocker.SUMMARY_MISSING, OpsPublishBlocker.NO_ACTIVE_SKU), List.of());
    }

    @Test
    void createAnswers201ThenReplays200AndNeedsTheIdempotencyKey() throws Exception {
        UUID key = UUID.randomUUID();
        when(catalog.create(key, "ROBOT", "编程机器人", "BUY", OPERATOR))
                .thenReturn(new OpsProductCreated(detail(), true), new OpsProductCreated(detail(), false));
        String body = "{\"category\":\"ROBOT\",\"name\":\"编程机器人\",\"saleMode\":\"BUY\"}";

        mockMvc.perform(post("/api/v1/ops/catalog/products").principal(OpsTestSupport.operator())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.product.status").value("DRAFT"))
                .andExpect(jsonPath("$.publishBlockers[1]").value("NO_ACTIVE_SKU"));
        mockMvc.perform(post("/api/v1/ops/catalog/products").principal(OpsTestSupport.operator())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/ops/catalog/products").principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyThePlatformOperatorGetsIn() throws Exception {
        mockMvc.perform(get("/api/v1/ops/catalog/products").principal(OpsTestSupport.support()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMON_FORBIDDEN"));
        mockMvc.perform(get("/api/v1/ops/merchant-accounts").principal(OpsTestSupport.support()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(catalog, accounts);
    }

    @Test
    void incompleteProductsAnswer422WithTheBlockers() throws Exception {
        when(catalog.changeStatus(PRODUCT, "PUBLISH", 0, OPERATOR)).thenThrow(new MallOpsException(
                MallOpsException.Error.PRODUCT_INCOMPLETE, List.of("NO_ACTIVE_OFFER")));

        mockMvc.perform(post("/api/v1/ops/catalog/products/{id}/status", PRODUCT).principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"PUBLISH\",\"version\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPS_PRODUCT_INCOMPLETE"))
                .andExpect(jsonPath("$.details.blockers[0]").value("NO_ACTIVE_OFFER"));
    }

    @Test
    void offersAndMediaPassTheOperator() throws Exception {
        UUID key = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        when(catalog.createOffer(eq(PRODUCT), eq(key), any(), eq(OPERATOR)))
                .thenReturn(new OpsProductCreated(detail(), true));
        when(catalog.uploadMedia(any(), eq(OPERATOR))).thenReturn(new OpsMediaView(UUID.randomUUID(),
                "http://127.0.0.1:18420/api/v1/mall/media/products/a.png", "image/png", 9));

        mockMvc.perform(post("/api/v1/ops/catalog/products/{id}/offers", PRODUCT).principal(OpsTestSupport.operator())
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":\"" + sku + "\",\"merchantId\":\"" + ORG + "\",\"merchantKind\":\"LOCAL_STORE\","
                                + "\"priceAmountMinor\":129900,\"priceUnit\":\"ITEM\",\"stockQuantity\":12,"
                                + "\"expressAvailable\":true,\"shippingFeeMinor\":0}"))
                .andExpect(status().isCreated());
        verify(catalog).createOffer(PRODUCT, key, new MallCatalogOpsService.OfferDraft(sku, ORG, "LOCAL_STORE", null,
                129900L, "ITEM", 12, true, 0L, null, null, null, null), OPERATOR);
        mockMvc.perform(multipart("/api/v1/ops/catalog/media").file(new MockMultipartFile("file", "a.png",
                        "image/png", new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0}))
                        .principal(OpsTestSupport.operator()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/png"));
    }

    @Test
    void merchantAccountsArePassedThroughAndPlaceholdersAnswer422() throws Exception {
        OpsMerchantAccountService.Operator operator = new OpsMerchantAccountService.Operator(
                OpsTestSupport.OPERATOR_ID, OpsTestSupport.OPERATOR_NAME);
        when(accounts.save(ORG, "1900000106", true, null, operator)).thenThrow(new com.lowaltitude.inspection.trade
                .application.OpsMerchantAccountException(com.lowaltitude.inspection.trade.application
                .OpsMerchantAccountException.Error.SUB_MCHID_PLACEHOLDER));

        mockMvc.perform(put("/api/v1/ops/merchant-accounts/{id}", ORG).principal(OpsTestSupport.operator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"subMchid\":\"1900000106\",\"active\":true}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("OPS_SUB_MCHID_PLACEHOLDER"));
        mockMvc.perform(get("/api/v1/ops/merchant-accounts").principal(OpsTestSupport.operator())
                        .param("registered", "false"))
                .andExpect(status().isOk());
        verify(accounts).accounts(null, false, 1, 20);
    }
}
