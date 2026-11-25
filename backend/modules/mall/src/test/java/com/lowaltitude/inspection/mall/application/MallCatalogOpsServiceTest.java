package com.lowaltitude.inspection.mall.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.mall.application.MallOpsException.Error;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsIntroBlockView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsPackItemView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsSpecView;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper.OfferFields;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper.OfferRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper.ProductRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper.SkuRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 运营端“商品与供给”的规则（S3-1）。mapper 是 Mockito 替身；SQL 本身在开发环境联调过。 */
class MallCatalogOpsServiceTest {

    private static final UUID PRODUCT = UUID.fromString("c2000000-0000-4000-8000-000000000101");
    private static final UUID SKU = UUID.fromString("c3000000-0000-4000-8000-000000000101");
    private static final UUID OFFER = UUID.fromString("c4000000-0000-4000-8000-000000000001");
    private static final UUID MERCHANT = UUID.fromString("c1000000-0000-4000-8000-000000000001");
    private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-23T02:00:00Z");
    private static final MallCatalogOpsService.Operator OPERATOR =
            new MallCatalogOpsService.Operator(UUID.randomUUID(), "平台运营（示例）");
    private static final String CONTROLLED = "http://127.0.0.1:18420/api/v1/mall/media/products/x.jpg";

    private final MallCatalogOpsMapper mapper = mock(MallCatalogOpsMapper.class);
    private boolean collecting = true;
    private final MallOpsPorts.MediaStore media = new MallOpsPorts.MediaStore() {
        @Override
        public void put(String key, byte[] content, String contentType) {
        }

        @Override
        public String publicUrl(String key) {
            return "http://127.0.0.1:18420/api/v1/mall/media/" + key;
        }

        @Override
        public boolean isControlled(String url) {
            return url.startsWith("http://127.0.0.1:18420/api/v1/mall/media/");
        }
    };
    private final java.util.Set<UUID> suspended = new java.util.HashSet<>();
    private final MallCatalogOpsService service = new MallCatalogOpsService(mapper,
            city -> "370500".equals(city) ? Optional.of("东营市") : Optional.empty(),
            new MallOpsPorts.Merchants() {
                @Override
                public Optional<String> activeProviderName(UUID organization) {
                    return MERCHANT.equals(organization) ? Optional.of("示例机器人商家") : Optional.empty();
                }

                @Override
                public boolean listingSuspended(UUID organization) {
                    return suspended.contains(organization);
                }
            },
            organization -> collecting, media, Clock.fixed(Instant.parse("2026-11-23T02:00:00Z"), ZoneOffset.UTC));

    private static ProductRow product(String saleMode, String status, String summary) {
        return new ProductRow(PRODUCT, "ROBOT", "编程机器人探索套装", summary, null, saleMode, null, 0, status, AT, AT, 3,
                null, null, null);
    }

    private static OfferRow offer(String status, Long price, String unit) {
        return new OfferRow(OFFER, SKU, "标准套装", MERCHANT, "示例机器人商家", "FACTORY_DIRECT", "370500", price, unit,
                12, true, 0, null, null, null, null, status, AT, 2, null);
    }

    private void given(ProductRow product, List<SkuRow> skus, List<OfferRow> offers) {
        when(mapper.lockProduct(PRODUCT)).thenReturn(PRODUCT);
        when(mapper.findProduct(PRODUCT)).thenReturn(product);
        when(mapper.findSkus(PRODUCT)).thenReturn(skus);
        when(mapper.findOffers(PRODUCT)).thenReturn(offers);
        when(mapper.findOffer(PRODUCT, OFFER)).thenReturn(offers.isEmpty() ? null : offers.getFirst());
    }

    private static SkuRow sku(String status) {
        return new SkuRow(SKU, PRODUCT, "标准套装", 10, status, 1);
    }

    private static MallCatalogOpsService.OfferDraft draft(Long price, String unit, boolean express, String pickup) {
        return new MallCatalogOpsService.OfferDraft(SKU, MERCHANT, "FACTORY_DIRECT", "370500", price, unit, 12, express,
                0L, pickup, null, null, null);
    }

    private static void assertRejected(Runnable call, Error error) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(MallOpsException.class,
                rejected -> assertThat(rejected.error()).isEqualTo(error));
    }

    // ---- 上下架 ----

    @Test
    void publishingNeedsASummaryAnActiveSpecAndAnActiveOffer() {
        given(product("BUY", "DRAFT", null), List.of(sku("DISABLED")), List.of(offer("ACTIVE", 129900L, "ITEM")));

        assertThatThrownBy(() -> service.changeStatus(PRODUCT, "PUBLISH", 3, OPERATOR))
                .isInstanceOfSatisfying(MallOpsException.class, rejected -> {
                    assertThat(rejected.error()).isEqualTo(Error.PRODUCT_INCOMPLETE);
                    // 规格停用了，它下面的供给也不算。
                    assertThat(rejected.blockers()).containsExactly("SUMMARY_MISSING", "NO_ACTIVE_SKU", "NO_ACTIVE_OFFER");
                });
        verify(mapper, never()).updateProductStatus(any(), anyString(), anyInt(), any());
    }

    @Test
    void publishAndUnpublishAreRecordedAndRepeatsAreRefused() {
        given(product("BUY", "DRAFT", "一句话"), List.of(sku("ACTIVE")), List.of(offer("ACTIVE", 129900L, "ITEM")));
        when(mapper.updateProductStatus(PRODUCT, "PUBLISHED", 3, AT)).thenReturn(1);

        service.changeStatus(PRODUCT, "PUBLISH", 3, OPERATOR);

        verify(mapper).insertOperation(eq(PRODUCT), eq("PRODUCT_PUBLISH"), anyString(), eq(OPERATOR.accountId()),
                eq("平台运营（示例）"), eq(AT));
        assertRejected(() -> service.changeStatus(PRODUCT, "PUBLISH", 2, OPERATOR), Error.VERSION_CONFLICT);
        assertRejected(() -> service.changeStatus(PRODUCT, "UNPUBLISH", 3, OPERATOR), Error.STATUS_UNCHANGED);
    }

    // 身份 V013：平台运营代上架也一样，资质没审核通过的商家不能上架；别家合格的商家不受牵连。
    @Test
    void onlyDraftsCanBeDeletedAndTheDeletionIsRecorded() {
        given(product("RENT", "DRAFT", null), List.of(), List.of());
        when(mapper.deleteDraft(PRODUCT, 3, AT)).thenReturn(1);
        assertRejected(() -> service.deleteDraft(PRODUCT, 2, OPERATOR), Error.VERSION_CONFLICT);
        service.deleteDraft(PRODUCT, 3, OPERATOR);
        verify(mapper).insertOperation(eq(PRODUCT), eq("PRODUCT_DELETE"), eq("删除草稿“编程机器人探索套装”"),
                eq(OPERATOR.accountId()), anyString(), eq(AT));

        for (String status : List.of("PUBLISHED", "OFFLINE")) {
            given(product("RENT", status, "一句话"), List.of(), List.of());
            assertRejected(() -> service.deleteDraft(PRODUCT, 3, OPERATOR), Error.PRODUCT_NOT_DELETABLE);
        }
        verify(mapper).deleteDraft(PRODUCT, 3, AT);

        when(mapper.lockProduct(PRODUCT)).thenReturn(null);
        assertRejected(() -> service.deleteDraft(PRODUCT, 3, OPERATOR), Error.NOT_FOUND);
    }

    @Test
    void aProductNeedsAtLeastOneSellerWhoseQualificationIsNotPending() {
        suspended.add(MERCHANT);
        given(product("BUY", "DRAFT", "一句话"), List.of(sku("ACTIVE")), List.of(offer("ACTIVE", 129900L, "ITEM")));
        when(mapper.updateProductStatus(PRODUCT, "PUBLISHED", 3, AT)).thenReturn(1);

        assertRejected(() -> service.changeStatus(PRODUCT, "PUBLISH", 3, OPERATOR), Error.MERCHANT_NOT_QUALIFIED);
        verify(mapper, never()).updateProductStatus(any(), anyString(), anyInt(), any());

        OfferRow other = new OfferRow(UUID.randomUUID(), SKU, "标准套装", UUID.randomUUID(), "另一家", "FACTORY_DIRECT",
                "370500", 129900L, "ITEM", 12, true, 0, null, null, null, null, "ACTIVE", AT, 2, null);
        given(product("BUY", "DRAFT", "一句话"), List.of(sku("ACTIVE")),
                List.of(offer("ACTIVE", 129900L, "ITEM"), other));
        service.changeStatus(PRODUCT, "PUBLISH", 3, OPERATOR);
        verify(mapper).updateProductStatus(PRODUCT, "PUBLISHED", 3, AT);
    }

    // 身份 V013：商品已上架时，被挂起的商家不能再启用供给；草稿里可以先准备。
    @Test
    void aSuspendedSellerCannotActivateAnOfferOfAPublishedProduct() {
        suspended.add(MERCHANT);
        given(product("BUY", "PUBLISHED", "一句话"), List.of(sku("ACTIVE")), List.of(offer("DISABLED", 129900L, "ITEM")));
        when(mapper.updateOfferStatus(OFFER, "ACTIVE", 2, AT)).thenReturn(1);

        assertRejected(() -> service.changeOfferStatus(PRODUCT, OFFER, "ACTIVATE", 2, OPERATOR),
                Error.MERCHANT_NOT_QUALIFIED);

        given(product("BUY", "DRAFT", "一句话"), List.of(sku("ACTIVE")), List.of(offer("DISABLED", 129900L, "ITEM")));
        service.changeOfferStatus(PRODUCT, OFFER, "ACTIVATE", 2, OPERATOR);
        verify(mapper).updateOfferStatus(OFFER, "ACTIVE", 2, AT);
    }

    // ---- 供给 ----

    @Test
    void offersFollowTheSaleModeAndNeedAPickupPointWithoutExpress() {
        given(product("BUY", "DRAFT", null), List.of(sku("ACTIVE")), List.of());
        UUID key = UUID.randomUUID();

        assertThatThrownBy(() -> service.createOffer(PRODUCT, key, draft(100L, "DAY", true, null), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createOffer(PRODUCT, key, draft(100L, "ITEM", false, " "), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        assertRejected(() -> service.createOffer(PRODUCT, key, new MallCatalogOpsService.OfferDraft(SKU, MERCHANT,
                "FACTORY_DIRECT", "999999", 100L, "ITEM", null, true, 0L, null, null, null, null), OPERATOR),
                Error.CITY_UNKNOWN);
        assertRejected(() -> service.createOffer(PRODUCT, key, new MallCatalogOpsService.OfferDraft(SKU,
                UUID.randomUUID(), "FACTORY_DIRECT", null, 100L, "ITEM", null, true, 0L, null, null, null, null),
                OPERATOR), Error.MERCHANT_UNKNOWN);

        given(product("INQUIRY", "DRAFT", null), List.of(sku("ACTIVE")), List.of());
        assertThatThrownBy(() -> service.createOffer(PRODUCT, key, draft(100L, "ITEM", true, null), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        given(product("RENT", "DRAFT", null), List.of(sku("ACTIVE")), List.of());
        assertThatThrownBy(() -> service.createOffer(PRODUCT, key, draft(100L, "ITEM", true, null), OPERATOR))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mapper, never()).insertOffer(any(), any(), any(), any(), any());
    }

    @Test
    void aNewOfferStartsDisabledAndADuplicateIsRefused() {
        given(product("BUY", "DRAFT", null), List.of(sku("ACTIVE")), List.of());
        UUID key = UUID.randomUUID();
        when(mapper.countSameOffer(SKU, MERCHANT, "370500", null)).thenReturn(0, 1);
        when(mapper.insertOffer(eq(SKU), eq(MERCHANT), any(), eq(key), eq(AT))).thenReturn(OFFER);

        assertThat(service.createOffer(PRODUCT, key, draft(129900L, "ITEM", true, null), OPERATOR).created()).isTrue();
        assertRejected(() -> service.createOffer(PRODUCT, UUID.randomUUID(), draft(129900L, "ITEM", true, null),
                OPERATOR), Error.OFFER_DUPLICATE);
        ArgumentCaptor<OfferFields> fields = ArgumentCaptor.forClass(OfferFields.class);
        verify(mapper).insertOffer(eq(SKU), eq(MERCHANT), fields.capture(), eq(key), eq(AT));
        assertThat(fields.getValue().stockQuantity()).isEqualTo(12);
        verify(mapper).insertOperation(eq(PRODUCT), eq("OFFER_CREATE"), anyString(), any(), any(), eq(AT));
    }

    @Test
    void activatingNeedsAnActiveSpecAPriceAndForPricedPurchasesACollectingMerchant() {
        given(product("BUY", "DRAFT", null), List.of(sku("ACTIVE")), List.of(offer("DISABLED", null, "ITEM")));
        assertRejected(() -> service.changeOfferStatus(PRODUCT, OFFER, "ACTIVATE", 2, OPERATOR), Error.OFFER_INCOMPLETE);

        given(product("BUY", "DRAFT", null), List.of(sku("DISABLED")), List.of(offer("DISABLED", 100L, "ITEM")));
        assertRejected(() -> service.changeOfferStatus(PRODUCT, OFFER, "ACTIVATE", 2, OPERATOR), Error.OFFER_INCOMPLETE);

        collecting = false;
        given(product("BUY", "DRAFT", null), List.of(sku("ACTIVE")), List.of(offer("DISABLED", 100L, "ITEM")));
        assertRejected(() -> service.changeOfferStatus(PRODUCT, OFFER, "ACTIVATE", 2, OPERATOR),
                Error.MERCHANT_NOT_COLLECTING);

        // 租赁可以先上（App 上显示“暂未开通在线支付”）；询价不标价。
        given(product("RENT", "DRAFT", null), List.of(sku("ACTIVE")), List.of(offer("DISABLED", 38000L, "DAY")));
        when(mapper.updateOfferStatus(OFFER, "ACTIVE", 2, AT)).thenReturn(1);
        service.changeOfferStatus(PRODUCT, OFFER, "ACTIVATE", 2, OPERATOR);
        verify(mapper).insertOperation(eq(PRODUCT), eq("OFFER_ACTIVATE"), anyString(), any(), any(), eq(AT));
    }

    @Test
    void aPriceChangeIsRecordedFromAndTo() {
        given(product("BUY", "PUBLISHED", "一句话"), List.of(sku("ACTIVE")), List.of(offer("ACTIVE", 129900L, "ITEM")));
        when(mapper.countSameOffer(SKU, MERCHANT, "370500", OFFER)).thenReturn(0);
        when(mapper.updateOffer(eq(OFFER), any(), eq(2), eq(AT))).thenReturn(1);

        service.updateOffer(PRODUCT, OFFER, draft(119950L, "ITEM", true, null), 2, OPERATOR);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(mapper).insertOperation(eq(PRODUCT), eq("OFFER_PRICE_CHANGE"), detail.capture(), any(), any(), eq(AT));
        assertThat(detail.getValue()).contains("¥1299 → ¥1199.50");
    }

    // ---- 资料 ----

    @Test
    void productDetailsAreValidatedAndImagesMustBeControlled() {
        given(product("BUY", "DRAFT", null), List.of(), List.of());
        MallCatalogOpsService.ProductDraft good = new MallCatalogOpsService.ProductDraft("ROBOT", "编程机器人", "一句话",
                CONTROLLED, "ONLINE_EXPERIENCE", 10, List.of("图形化编程", "避障练习"),
                List.of(new OpsSpecView("续航", "90 分钟")), List.of(new OpsPackItemView("主机", 1)),
                List.of("https://cdn.example.com/a.jpg"),
                List.of(new OpsIntroBlockView(OpsIntroBlockType.TEXT, "第一段\n第二行", null),
                        new OpsIntroBlockView(OpsIntroBlockType.IMAGE, null, CONTROLLED)), 3);
        when(mapper.updateProduct(PRODUCT, "ROBOT", "编程机器人", "一句话", CONTROLLED, "ONLINE_EXPERIENCE", 10, 3, AT))
                .thenReturn(1);

        service.update(PRODUCT, good, OPERATOR);

        verify(mapper).insertSellingPoint(PRODUCT, "图形化编程", 10, AT);
        verify(mapper).insertIntroBlock(PRODUCT, "IMAGE", null, CONTROLLED, 20, AT);
        assertRejected(() -> service.update(PRODUCT, new MallCatalogOpsService.ProductDraft("ROBOT", "编程机器人", null,
                "http://evil.example.com/a.jpg", null, null, List.of(), List.of(), List.of(), List.of(), List.of(), 3),
                OPERATOR), Error.MEDIA_URL_UNCONTROLLED);
        for (List<String> points : List.of(List.of("a", "a"), List.of("一二三四五六七八九十十一十二"),
                List.of("1", "2", "3", "4", "5", "6"))) {
            assertThatThrownBy(() -> MallCatalogOpsService.sellingPoints(points))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void imagesAreRecognisedByTheirBytes() {
        assertThat(MallCatalogOpsService.sniffImage(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0})).isEqualTo(
                "image/jpeg");
        assertThat(MallCatalogOpsService.sniffImage("RIFF0000WEBPVP8 ".getBytes())).isEqualTo("image/webp");
        assertThatThrownBy(() -> MallCatalogOpsService.sniffImage("<svg></svg>".getBytes()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void creatingIsIdempotentPerKey() {
        UUID key = UUID.randomUUID();
        when(mapper.findProductByCreateKey(key)).thenReturn(null, PRODUCT, PRODUCT);
        when(mapper.insertProduct("ROBOT", "编程机器人", "BUY", key, null, AT)).thenReturn(PRODUCT);
        when(mapper.findProduct(PRODUCT)).thenReturn(new ProductRow(PRODUCT, "ROBOT", "编程机器人", null, null, "BUY",
                null, 0, "DRAFT", AT, AT, 0, key, null, null));

        assertThat(service.create(key, "ROBOT", "编程机器人", "BUY", OPERATOR).created()).isTrue();
        assertThat(service.create(key, "ROBOT", "编程机器人", "BUY", OPERATOR).created()).isFalse();
        assertRejected(() -> service.create(key, "ROBOT", "别的名字", "BUY", OPERATOR), Error.IDEMPOTENCY_CONFLICT);
        verify(mapper).insertOperation(eq(PRODUCT), eq("PRODUCT_CREATE"), anyString(), any(), any(), eq(AT));
    }

    @Test
    void importKeepsAnExistingOfferOfTheSameSpecMerchantAndCity() {
        UUID existing = UUID.randomUUID();
        UUID proposed = UUID.randomUUID();
        when(mapper.productExists(PRODUCT)).thenReturn(true);
        when(mapper.skuExists(SKU)).thenReturn(false);
        when(mapper.findSameOffer(SKU, MERCHANT, "370500")).thenReturn(existing);

        List<MallCatalogImport.Written> written = service.importCatalog(List.of(new MallCatalogImport.Product(PRODUCT,
                "P1", "ROBOT", "机器人", "一句话", null, "BUY", null, 0, "DRAFT", List.of("卖点"), null, null,
                List.of(new MallCatalogImport.Sku(SKU, "P1-A", "标准版", 10, List.of(new MallCatalogImport.Offer(
                        proposed, "P1-A|SHOP|370500", MERCHANT, "LOCAL_STORE", "370500", 100L, "ITEM", 3, true, 0, null,
                        null, null, null, "ACTIVE")))))), "批次 abcd1234", OPERATOR);

        assertThat(written).extracting(MallCatalogImport.Written::kind, MallCatalogImport.Written::created)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(MallCatalogImport.Kind.PRODUCT, false),
                        org.assertj.core.groups.Tuple.tuple(MallCatalogImport.Kind.SKU, true),
                        org.assertj.core.groups.Tuple.tuple(MallCatalogImport.Kind.OFFER, false));
        assertThat(written.get(2).targetId()).isEqualTo(existing);
        verify(mapper).upsertOffer(eq(existing), eq(SKU), eq(MERCHANT), any(), eq("ACTIVE"), eq(AT));
        // 没带参数表、包装清单时原有的不动。
        verify(mapper, never()).disableSpecs(any(), any());
        verify(mapper, never()).disablePackItems(any(), any());
        verify(mapper).insertOperation(eq(PRODUCT), eq("IMPORT"), anyString(), any(), any(), eq(AT));
        verify(mapper, never()).insertOffer(any(), any(), any(), isNull(), any());
    }
}
