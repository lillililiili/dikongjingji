package com.lowaltitude.inspection.mall.application;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MallCatalogControllerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("c2000000-0000-4000-8000-000000000101");

    private final MallCatalogReadService service = mock(MallCatalogReadService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new MallCatalogController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void listPassesEveryParameterAndReturnsTheBarePage() throws Exception {
        when(service.listProducts(new MallProductSearch(
                "370500", "ROBOT", "编程", List.of("BUY", "RENT"), List.of("ONLINE_EXPERIENCE"),
                List.of("LOCAL_STORE"), 10000L, 500000L, "PRICE_ASC", 2, 10))).thenReturn(new MallProductPageView(
                2, 10, 11, false,
                List.of(new MallProductCardView(
                        PRODUCT_ID, MallCategory.ROBOT, "编程机器人探索套装", null, MallSaleMode.BUY,
                        MallExperienceBadge.ONLINE_EXPERIENCE,
                        new MallListPriceView(129900L, MallPriceUnit.ITEM, true), 3))));

        mockMvc.perform(get("/api/v1/mall/products")
                        .param("cityCode", " 370500 ")
                        .param("category", "ROBOT")
                        .param("keyword", "编程")
                        .param("saleModes", "BUY", "RENT")
                        .param("experienceBadges", "ONLINE_EXPERIENCE")
                        .param("merchantKinds", "LOCAL_STORE")
                        .param("minPriceMinor", "10000")
                        .param("maxPriceMinor", "500000")
                        .param("sort", "PRICE_ASC")
                        .param("page", "2")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(11))
                .andExpect(jsonPath("$.items[0].productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.items[0].saleMode").value("BUY"))
                .andExpect(jsonPath("$.items[0].experienceBadge").value("ONLINE_EXPERIENCE"))
                .andExpect(jsonPath("$.items[0].coverImage").value(nullValue()))
                .andExpect(jsonPath("$.items[0].price.fromAmountMinor").value(129900))
                .andExpect(jsonPath("$.items[0].price.unit").value("ITEM"))
                .andExpect(jsonPath("$.items[0].price.multiplePrices").value(true))
                .andExpect(jsonPath("$.items[0].sellerCount").value(3));
    }

    @Test
    void detailReturnsOffersWithTheirMerchant() throws Exception {
        when(service.findProduct(PRODUCT_ID, "370500")).thenReturn(new MallProductDetailView(
                PRODUCT_ID, MallCategory.ROBOT, "编程机器人探索套装", null, null, MallSaleMode.INQUIRY, null,
                List.of(new MallOfferView(
                        UUID.randomUUID(), UUID.randomUUID(), "标准版",
                        new MallMerchantView(UUID.randomUUID(), "示例机器人商家", MallMerchantKind.FACTORY_DIRECT),
                        null, "CNY", MallPriceUnit.ITEM, "按配置报价", null, null, true)),
                List.of(new MallSpecView("适用年龄", "8 岁以上")),
                List.of(new MallPackItemView("机器人主机", 1))));

        mockMvc.perform(get("/api/v1/mall/products/{productId}", PRODUCT_ID).param("cityCode", "370500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.experienceBadge").value(nullValue()))
                .andExpect(jsonPath("$.offers[0].merchant.kind").value("FACTORY_DIRECT"))
                .andExpect(jsonPath("$.offers[0].amountMinor").value(nullValue()))
                .andExpect(jsonPath("$.offers[0].currency").value("CNY"))
                .andExpect(jsonPath("$.offers[0].onlinePayment").value(true))
                .andExpect(jsonPath("$.offers[0].length()").value(11))
                .andExpect(jsonPath("$.specs[0].label").value("适用年龄"))
                .andExpect(jsonPath("$.packItems[0].quantity").value(1));
    }

    @Test
    void badArgumentsAreBadRequestsAndUnknownProductsAreNotFound() throws Exception {
        when(service.listProducts(new MallProductSearch(
                null, null, null, null, null, null, null, null, null, 1, 20)))
                .thenThrow(new IllegalArgumentException("city code is invalid"));
        when(service.findProduct(PRODUCT_ID, "370500")).thenThrow(new MallProductNotFoundException());

        mockMvc.perform(get("/api/v1/mall/products"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/mall/products").param("cityCode", "370500").param("page", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/mall/products/not-a-uuid").param("cityCode", "370500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_INVALID_ARGUMENT"));
        mockMvc.perform(get("/api/v1/mall/products/{productId}", PRODUCT_ID).param("cityCode", "370500"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMON_NOT_FOUND"));
    }
}
