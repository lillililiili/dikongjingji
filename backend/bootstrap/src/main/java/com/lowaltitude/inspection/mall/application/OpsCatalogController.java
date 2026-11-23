package com.lowaltitude.inspection.mall.application;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsIntroBlockView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsMediaView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsPackItemView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductCreated;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductDetailView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductPageView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsSpecView;
import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 运营端“商品与供给”（S3-1，契约 contracts/openapi-ops-catalog.yaml 1.0.0）的商品、规格、供给和图片。只给平台运营；
 * 每个改动都由服务写一条操作记录（操作人取自令牌和身份资料）。收款账户在 trade 的 OpsMerchantAccountController。
 */
@RestController
public final class OpsCatalogController {

    private static final String PRODUCTS = "/api/v1/ops/catalog/products";
    private static final String PRODUCT = PRODUCTS + "/{productId}";

    private final MallCatalogOpsService service;
    private final OpsActors actors;

    public OpsCatalogController(MallCatalogOpsService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping(PRODUCTS)
    public OpsProductPageView products(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String saleMode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.products(keyword, category, saleMode, status, page, size);
    }

    /** 新建：第一次 201，同一个幂等键、同样内容重放 200。 */
    @PostMapping(PRODUCTS)
    public ResponseEntity<OpsProductDetailView> create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CreateProductRequest request
    ) {
        MallCatalogOpsService.Operator operator = operator(authentication);
        OpsProductCreated created = service.create(idempotencyKey, request.category(), request.name(),
                request.saleMode(), operator);
        return ResponseEntity.status(created.created() ? 201 : 200).body(created.detail());
    }

    @GetMapping(PRODUCT)
    public OpsProductDetailView product(Authentication authentication, @PathVariable UUID productId) {
        actors.operator(authentication);
        return service.product(productId);
    }

    @PutMapping(PRODUCT)
    public OpsProductDetailView update(
            Authentication authentication,
            @PathVariable UUID productId,
            @RequestBody UpdateProductRequest request
    ) {
        return service.update(productId, new MallCatalogOpsService.ProductDraft(request.category(), request.name(),
                request.summary(), request.coverUrl(), request.experienceBadge(), request.sortOrder(),
                request.sellingPoints(), request.specs(), request.packItems(), request.gallery(),
                request.introBlocks(), request.version()), operator(authentication));
    }

    @PostMapping(PRODUCT + "/status")
    public OpsProductDetailView changeStatus(
            Authentication authentication,
            @PathVariable UUID productId,
            @RequestBody StatusRequest request
    ) {
        return service.changeStatus(productId, request.action(), request.version(), operator(authentication));
    }

    @PostMapping(PRODUCT + "/skus")
    public ResponseEntity<OpsProductDetailView> createSku(
            Authentication authentication,
            @PathVariable UUID productId,
            @RequestBody CreateSkuRequest request
    ) {
        return ResponseEntity.status(201).body(service.createSku(productId, request.name(), request.sortOrder(),
                operator(authentication)));
    }

    @PutMapping(PRODUCT + "/skus/{skuId}")
    public OpsProductDetailView updateSku(
            Authentication authentication,
            @PathVariable UUID productId,
            @PathVariable UUID skuId,
            @RequestBody UpdateSkuRequest request
    ) {
        return service.updateSku(productId, skuId, request.name(), request.sortOrder(), request.status(),
                request.version(), operator(authentication));
    }

    @PostMapping(PRODUCT + "/offers")
    public ResponseEntity<OpsProductDetailView> createOffer(
            Authentication authentication,
            @PathVariable UUID productId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CreateOfferRequest request
    ) {
        MallCatalogOpsService.Operator operator = operator(authentication);
        OpsProductCreated created = service.createOffer(productId, idempotencyKey,
                new MallCatalogOpsService.OfferDraft(request.skuId(), request.merchantId(), request.merchantKind(),
                        request.cityCode(), request.priceAmountMinor(), request.priceUnit(), request.stockQuantity(),
                        request.expressAvailable(), request.shippingFeeMinor(), request.pickupPoint(),
                        request.deliveryNote(), request.shippingNote(), request.fulfilledFrom()), operator);
        return ResponseEntity.status(created.created() ? 201 : 200).body(created.detail());
    }

    @PutMapping(PRODUCT + "/offers/{offerId}")
    public OpsProductDetailView updateOffer(
            Authentication authentication,
            @PathVariable UUID productId,
            @PathVariable UUID offerId,
            @RequestBody UpdateOfferRequest request
    ) {
        return service.updateOffer(productId, offerId, new MallCatalogOpsService.OfferDraft(null, null,
                request.merchantKind(), request.cityCode(), request.priceAmountMinor(), request.priceUnit(),
                request.stockQuantity(), request.expressAvailable(), request.shippingFeeMinor(), request.pickupPoint(),
                request.deliveryNote(), request.shippingNote(), request.fulfilledFrom()), request.version(),
                operator(authentication));
    }

    @PostMapping(PRODUCT + "/offers/{offerId}/status")
    public OpsProductDetailView changeOfferStatus(
            Authentication authentication,
            @PathVariable UUID productId,
            @PathVariable UUID offerId,
            @RequestBody OfferStatusRequest request
    ) {
        return service.changeOfferStatus(productId, offerId, request.action(), request.version(),
                operator(authentication));
    }

    @PostMapping(path = "/api/v1/ops/catalog/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OpsMediaView> uploadMedia(
            Authentication authentication,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        MallCatalogOpsService.Operator operator = operator(authentication);
        if (file.isEmpty() || file.getSize() > MallCatalogOpsService.MAX_MEDIA_BYTES) {
            throw new IllegalArgumentException("file size is invalid");
        }
        return ResponseEntity.status(201).body(service.uploadMedia(file.getBytes(), operator));
    }

    private MallCatalogOpsService.Operator operator(Authentication authentication) {
        OpsActor actor = actors.operator(authentication);
        return new MallCatalogOpsService.Operator(actor.accountId(), actor.displayName());
    }

    record CreateProductRequest(String category, String name, String saleMode) {
    }

    /** 整份替换卖点、参数、包装清单、图集和图文；version 是详情里读到的。 */
    record UpdateProductRequest(String category, String name, String summary, String coverUrl, String experienceBadge,
                                Integer sortOrder, List<String> sellingPoints, List<OpsSpecView> specs,
                                List<OpsPackItemView> packItems, List<String> gallery,
                                List<OpsIntroBlockView> introBlocks, Integer version) {
    }

    /** action：PUBLISH / UNPUBLISH。 */
    record StatusRequest(String action, Integer version) {
    }

    record CreateSkuRequest(String name, Integer sortOrder) {
    }

    record UpdateSkuRequest(String name, Integer sortOrder, String status, Integer version) {
    }

    record CreateOfferRequest(UUID skuId, UUID merchantId, String merchantKind, String cityCode, Long priceAmountMinor,
                              String priceUnit, Integer stockQuantity, Boolean expressAvailable, Long shippingFeeMinor,
                              String pickupPoint, String deliveryNote, String shippingNote, String fulfilledFrom) {
    }

    record UpdateOfferRequest(String merchantKind, String cityCode, Long priceAmountMinor, String priceUnit,
                              Integer stockQuantity, Boolean expressAvailable, Long shippingFeeMinor,
                              String pickupPoint, String deliveryNote, String shippingNote, String fulfilledFrom,
                              Integer version) {
    }

    /** action：ACTIVATE / DEACTIVATE。 */
    record OfferStatusRequest(String action, Integer version) {
    }
}
