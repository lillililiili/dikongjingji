package com.lowaltitude.inspection.mall.application;

import java.io.IOException;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsMediaView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductCreated;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductDetailView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductPageView;
import com.lowaltitude.inspection.mall.application.OpsCatalogController.CreateOfferRequest;
import com.lowaltitude.inspection.mall.application.OpsCatalogController.CreateProductRequest;
import com.lowaltitude.inspection.mall.application.OpsCatalogController.CreateSkuRequest;
import com.lowaltitude.inspection.mall.application.OpsCatalogController.StatusRequest;
import com.lowaltitude.inspection.mall.application.OpsCatalogController.UpdateOfferRequest;
import com.lowaltitude.inspection.mall.application.OpsCatalogController.UpdateProductRequest;
import com.lowaltitude.inspection.mall.application.OpsCatalogController.UpdateSkuRequest;
import com.lowaltitude.inspection.trade.application.TradeAftersaleSubjects;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 商家工作台“我的商品”（契约 contracts/openapi-merchant-catalog.yaml 1.1.0）。接口形状和运营端“商品与供给”一样，
 * 请求体直接复用 {@link OpsCatalogController} 的；要 PROVIDER_OPERATOR，机构只从令牌里取，别家的商品一律 404。
 */
@RestController
public final class MerchantCatalogController {

    private static final String PRODUCTS = "/api/v1/provider/catalog/products";
    private static final String PRODUCT = PRODUCTS + "/{productId}";

    private final MerchantCatalogService service;
    private final IdentityProfilePort profiles;

    public MerchantCatalogController(MerchantCatalogService service, IdentityProfilePort profiles) {
        this.service = service;
        this.profiles = profiles;
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
        return service.products(merchant(authentication).organizationId(), keyword, category, saleMode, status,
                page, size);
    }

    /** 新建：第一次 201，同一个幂等键、同样内容重放 200。 */
    @PostMapping(PRODUCTS)
    public ResponseEntity<OpsProductDetailView> create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CreateProductRequest request
    ) {
        Merchant merchant = merchant(authentication);
        OpsProductCreated created = service.create(merchant.organizationId(), idempotencyKey, request.category(),
                request.name(), request.saleMode(), merchant.operator());
        return ResponseEntity.status(created.created() ? 201 : 200).body(created.detail());
    }

    @GetMapping(PRODUCT)
    public OpsProductDetailView product(Authentication authentication, @PathVariable UUID productId) {
        return service.product(merchant(authentication).organizationId(), productId);
    }

    @PutMapping(PRODUCT)
    public OpsProductDetailView update(
            Authentication authentication,
            @PathVariable UUID productId,
            @RequestBody UpdateProductRequest request
    ) {
        Merchant merchant = merchant(authentication);
        return service.update(merchant.organizationId(), productId, new MallCatalogOpsService.ProductDraft(
                request.category(), request.name(), request.summary(), request.coverUrl(), request.experienceBadge(),
                request.sortOrder(), request.sellingPoints(), request.specs(), request.packItems(), request.gallery(),
                request.introBlocks(), request.version()), merchant.operator());
    }

    /** 删除草稿（从没上架过的）；要带读到的版本号。 */
    @DeleteMapping(PRODUCT)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            Authentication authentication,
            @PathVariable UUID productId,
            @RequestParam Integer version
    ) {
        Merchant merchant = merchant(authentication);
        service.delete(merchant.organizationId(), productId, version, merchant.operator());
    }

    @PostMapping(PRODUCT + "/status")
    public OpsProductDetailView changeStatus(
            Authentication authentication,
            @PathVariable UUID productId,
            @RequestBody StatusRequest request
    ) {
        Merchant merchant = merchant(authentication);
        return service.changeStatus(merchant.organizationId(), productId, request.action(), request.version(),
                merchant.operator());
    }

    @PostMapping(PRODUCT + "/skus")
    public ResponseEntity<OpsProductDetailView> createSku(
            Authentication authentication,
            @PathVariable UUID productId,
            @RequestBody CreateSkuRequest request
    ) {
        Merchant merchant = merchant(authentication);
        return ResponseEntity.status(201).body(service.createSku(merchant.organizationId(), productId,
                request.name(), request.sortOrder(), merchant.operator()));
    }

    @PutMapping(PRODUCT + "/skus/{skuId}")
    public OpsProductDetailView updateSku(
            Authentication authentication,
            @PathVariable UUID productId,
            @PathVariable UUID skuId,
            @RequestBody UpdateSkuRequest request
    ) {
        Merchant merchant = merchant(authentication);
        return service.updateSku(merchant.organizationId(), productId, skuId, request.name(), request.sortOrder(),
                request.status(), request.version(), merchant.operator());
    }

    /** 供给的商家一律是本机构；请求里的 merchantId 可以不填，填了也不认。 */
    @PostMapping(PRODUCT + "/offers")
    public ResponseEntity<OpsProductDetailView> createOffer(
            Authentication authentication,
            @PathVariable UUID productId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody CreateOfferRequest request
    ) {
        Merchant merchant = merchant(authentication);
        OpsProductCreated created = service.createOffer(merchant.organizationId(), productId, idempotencyKey,
                new MallCatalogOpsService.OfferDraft(request.skuId(), null, request.merchantKind(),
                        request.cityCode(), request.priceAmountMinor(), request.priceUnit(), request.stockQuantity(),
                        request.expressAvailable(), request.shippingFeeMinor(), request.pickupPoint(),
                        request.deliveryNote(), request.shippingNote(), request.fulfilledFrom()), merchant.operator());
        return ResponseEntity.status(created.created() ? 201 : 200).body(created.detail());
    }

    @PutMapping(PRODUCT + "/offers/{offerId}")
    public OpsProductDetailView updateOffer(
            Authentication authentication,
            @PathVariable UUID productId,
            @PathVariable UUID offerId,
            @RequestBody UpdateOfferRequest request
    ) {
        Merchant merchant = merchant(authentication);
        return service.updateOffer(merchant.organizationId(), productId, offerId,
                new MallCatalogOpsService.OfferDraft(null, null, request.merchantKind(), request.cityCode(),
                        request.priceAmountMinor(), request.priceUnit(), request.stockQuantity(),
                        request.expressAvailable(), request.shippingFeeMinor(), request.pickupPoint(),
                        request.deliveryNote(), request.shippingNote(), request.fulfilledFrom()),
                request.version(), merchant.operator());
    }

    @PostMapping(PRODUCT + "/offers/{offerId}/status")
    public OpsProductDetailView changeOfferStatus(
            Authentication authentication,
            @PathVariable UUID productId,
            @PathVariable UUID offerId,
            @RequestBody StatusRequest request
    ) {
        Merchant merchant = merchant(authentication);
        return service.changeOfferStatus(merchant.organizationId(), productId, offerId, request.action(),
                request.version(), merchant.operator());
    }

    @PostMapping(path = "/api/v1/provider/catalog/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OpsMediaView> uploadMedia(
            Authentication authentication,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        Merchant merchant = merchant(authentication);
        if (file.isEmpty() || file.getSize() > MallCatalogOpsService.MAX_MEDIA_BYTES) {
            throw new IllegalArgumentException("file size is invalid");
        }
        return ResponseEntity.status(201).body(service.uploadMedia(merchant.organizationId(), file.getBytes(),
                merchant.operator()));
    }

    private Merchant merchant(Authentication authentication) {
        TradeAftersaleSubjects.Subject subject = TradeAftersaleSubjects.providerOperator(authentication);
        String name = profiles.findProfile(subject.subjectId())
                .map(IdentityProfile::displayName)
                .filter(displayName -> !displayName.isBlank())
                .orElseThrow(() -> new AccessDeniedException("operator account is not active"));
        return new Merchant(subject.organizationId(), new MallCatalogOpsService.Operator(subject.subjectId(), name));
    }

    private record Merchant(UUID organizationId, MallCatalogOpsService.Operator operator) {
    }
}
