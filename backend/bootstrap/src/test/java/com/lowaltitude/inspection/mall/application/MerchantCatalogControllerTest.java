package com.lowaltitude.inspection.mall.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductCreated;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductDetailView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductView;
import com.lowaltitude.inspection.ops.OpsTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** 商家工作台“我的商品”的控制器（契约 openapi-merchant-catalog.yaml 1.0.0）。 */
class MerchantCatalogControllerTest {

    private static final UUID ORG = OpsTestSupport.PLATFORM_ORG;
    private static final UUID MEMBER = UUID.fromString("c1000000-0000-4000-8000-0000000000d1");
    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000101");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-24T02:00:00Z");
    private static final MallCatalogOpsService.Operator OPERATOR = new MallCatalogOpsService.Operator(MEMBER, "王芳");

    private final MerchantCatalogService service = mock(MerchantCatalogService.class);
    private final MockMvc mockMvc = OpsTestSupport.mockMvc(new MerchantCatalogController(service,
            subjectId -> MEMBER.equals(subjectId)
                    ? Optional.of(new IdentityProfile(subjectId, "王芳", "东营翼飞科技", "PROVIDER")) : Optional.empty()));

    private static OpsProductDetailView detail() {
        return new OpsProductDetailView(new OpsProductView(PRODUCT, MallCategory.ROBOT, "编程机器人", null, null,
                MallSaleMode.BUY, null, 0, OpsProductStatus.DRAFT, AT, AT, 0, ORG, "东营翼飞科技"), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(OpsPublishBlocker.SUMMARY_MISSING),
                List.of());
    }

    @Test
    void createsUnderTheTokenOrganizationAndShowsTheOwner() throws Exception {
        UUID key = UUID.randomUUID();
        when(service.create(ORG, key, "ROBOT", "编程机器人", "BUY", OPERATOR))
                .thenReturn(new OpsProductCreated(detail(), true));
        mockMvc.perform(post("/api/v1/provider/catalog/products")
                        .principal(OpsTestSupport.signedIn(MEMBER, "PROVIDER_OPERATOR"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"category\":\"ROBOT\",\"name\":\"编程机器人\",\"saleMode\":\"BUY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.product.ownerName").value("东营翼飞科技"));
    }

    @Test
    void offersIgnoreTheMerchantInTheBody() throws Exception {
        UUID key = UUID.randomUUID();
        UUID sku = UUID.randomUUID();
        when(service.createOffer(eq(ORG), eq(PRODUCT), eq(key), any(), eq(OPERATOR)))
                .thenReturn(new OpsProductCreated(detail(), true));
        mockMvc.perform(post("/api/v1/provider/catalog/products/{id}/offers", PRODUCT)
                        .principal(OpsTestSupport.signedIn(MEMBER, "PROVIDER_OPERATOR"))
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuId\":\"" + sku + "\",\"merchantId\":\"" + UUID.randomUUID()
                                + "\",\"merchantKind\":\"LOCAL_STORE\",\"priceAmountMinor\":129900,\"priceUnit\":\"ITEM\","
                                + "\"expressAvailable\":true,\"shippingFeeMinor\":0}"))
                .andExpect(status().isCreated());
        verify(service).createOffer(ORG, PRODUCT, key, new MallCatalogOpsService.OfferDraft(sku, null, "LOCAL_STORE",
                null, 129900L, "ITEM", null, true, 0L, null, null, null, null), OPERATOR);
    }

    @Test
    void unqualifiedMerchantsGet409OnPublish() throws Exception {
        when(service.changeStatus(ORG, PRODUCT, "PUBLISH", 0, OPERATOR))
                .thenThrow(new MallOpsException(MallOpsException.Error.MERCHANT_NOT_QUALIFIED));
        mockMvc.perform(post("/api/v1/provider/catalog/products/{id}/status", PRODUCT)
                        .principal(OpsTestSupport.signedIn(MEMBER, "PROVIDER_OPERATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"PUBLISH\",\"version\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPS_MERCHANT_NOT_QUALIFIED"));
    }

    @Test
    void platformOperatorsAreNotMerchants() throws Exception {
        mockMvc.perform(get("/api/v1/provider/catalog/products").principal(OpsTestSupport.operator()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
