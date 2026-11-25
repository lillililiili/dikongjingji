package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.mall.application.MallCatalogOpsService.OfferDraft;
import com.lowaltitude.inspection.mall.application.MallCatalogOpsService.Operator;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper.ProductRow;
import org.junit.jupiter.api.Test;

/** 商家工作台“我的商品”在运营端商品服务外面加的规则：只碰自己的、供给只能是自己、上架看资质、只删自己的草稿。 */
class MerchantCatalogServiceTest {

    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("c1000000-0000-4000-8000-000000000002");
    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000101");
    private static final UUID SKU = UUID.fromString("c3000000-0000-4000-8000-000000000101");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-24T02:00:00Z");
    private static final Operator OPERATOR = new Operator(UUID.randomUUID(), "王芳");

    private final MallCatalogOpsService catalog = mock(MallCatalogOpsService.class);
    private final MallCatalogOpsMapper mapper = mock(MallCatalogOpsMapper.class);
    private boolean reviewed = true;
    private final MerchantCatalogService service = new MerchantCatalogService(catalog, mapper,
            ids -> reviewed ? Set.copyOf(ids) : Set.of());

    private static ProductRow owned(UUID owner) {
        return new ProductRow(PRODUCT, "ROBOT", "编程机器人", null, null, "BUY", null, 0, "DRAFT", AT, AT, 0, null,
                owner, owner == null ? null : "示例商家");
    }

    @Test
    void listsAndCreatesOnlyUnderTheMerchant() {
        UUID key = UUID.randomUUID();
        service.products(MERCHANT, null, null, null, null, 1, 20);
        verify(catalog).products(null, null, null, null, MERCHANT, 1, 20);
        service.create(MERCHANT, key, "ROBOT", "编程机器人", "BUY", OPERATOR);
        verify(catalog).create(key, "ROBOT", "编程机器人", "BUY", OPERATOR, MERCHANT);
    }

    @Test
    void otherMerchantsAndPlatformProductsLookMissing() {
        when(mapper.findProduct(PRODUCT)).thenReturn(owned(OTHER));
        assertThatThrownBy(() -> service.product(MERCHANT, PRODUCT))
                .isInstanceOf(MallOpsException.class)
                .extracting(e -> ((MallOpsException) e).error())
                .isEqualTo(MallOpsException.Error.NOT_FOUND);

        when(mapper.findProduct(PRODUCT)).thenReturn(owned(null));
        assertThatThrownBy(() -> service.changeStatus(MERCHANT, PRODUCT, "UNPUBLISH", 0, OPERATOR))
                .isInstanceOf(MallOpsException.class);
        verify(catalog, never()).changeStatus(eq(PRODUCT), eq("UNPUBLISH"), eq(0), eq(OPERATOR));
    }

    @Test
    void deletesOnlyOwnProducts() {
        when(mapper.findProduct(PRODUCT)).thenReturn(owned(OTHER));
        assertThatThrownBy(() -> service.delete(MERCHANT, PRODUCT, 0, OPERATOR))
                .isInstanceOf(MallOpsException.class)
                .extracting(e -> ((MallOpsException) e).error())
                .isEqualTo(MallOpsException.Error.NOT_FOUND);
        verify(catalog, never()).deleteDraft(PRODUCT, 0, OPERATOR);

        when(mapper.findProduct(PRODUCT)).thenReturn(owned(MERCHANT));
        service.delete(MERCHANT, PRODUCT, 0, OPERATOR);
        verify(catalog).deleteDraft(PRODUCT, 0, OPERATOR);
    }

    @Test
    void offersAlwaysBelongToTheMerchant() {
        UUID key = UUID.randomUUID();
        when(mapper.findProduct(PRODUCT)).thenReturn(owned(MERCHANT));
        OfferDraft asked = new OfferDraft(SKU, OTHER, "LOCAL_STORE", "370500", 129900L, "ITEM", 3, true, 0L, null,
                null, null, null);
        service.createOffer(MERCHANT, PRODUCT, key, asked, OPERATOR);
        verify(catalog).createOffer(PRODUCT, key, new OfferDraft(SKU, MERCHANT, "LOCAL_STORE", "370500", 129900L,
                "ITEM", 3, true, 0L, null, null, null, null), OPERATOR);
    }

    @Test
    void publishingNeedsAReviewedQualificationButUnpublishingDoesNot() {
        when(mapper.findProduct(PRODUCT)).thenReturn(owned(MERCHANT));
        reviewed = false;
        assertThatThrownBy(() -> service.changeStatus(MERCHANT, PRODUCT, "PUBLISH", 0, OPERATOR))
                .isInstanceOf(MallOpsException.class)
                .extracting(e -> ((MallOpsException) e).error())
                .isEqualTo(MallOpsException.Error.MERCHANT_NOT_QUALIFIED);
        service.changeStatus(MERCHANT, PRODUCT, "UNPUBLISH", 0, OPERATOR);
        verify(catalog).changeStatus(PRODUCT, "UNPUBLISH", 0, OPERATOR);

        reviewed = true;
        service.changeStatus(MERCHANT, PRODUCT, "PUBLISH", 0, OPERATOR);
        verify(catalog).changeStatus(PRODUCT, "PUBLISH", 0, OPERATOR);
    }
}
