package com.lowaltitude.inspection.mall.application;

import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.mall.application.MallCatalogOpsService.OfferDraft;
import com.lowaltitude.inspection.mall.application.MallCatalogOpsService.Operator;
import com.lowaltitude.inspection.mall.application.MallCatalogOpsService.ProductDraft;
import com.lowaltitude.inspection.mall.application.MallOpsException.Error;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsMediaView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductCreated;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductDetailView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductPageView;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper.ProductRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 商家工作台“我的商品”（契约 contracts/openapi-merchant-catalog.yaml 1.1.0，迁移 V006、V007）：商家自己建商品、改资料、
 * 管规格和供给、上下架。规则和运营端的 {@link MallCatalogOpsService} 完全一样（这里只是套一层），另加四条：
 * <ul>
 *   <li>只能看、改自己建的商品（owner_organization_id 是本机构）；别家的和平台建的一律当作不存在。</li>
 *   <li>供给的商家只能是自己：建供给时不认请求里的 merchantId，一律记本机构。</li>
 *   <li>上架前机构的资质要审核通过（身份模块的 qualification_reviewed_at），否则 409 OPS_MERCHANT_NOT_QUALIFIED；
 *       下架、建草稿、改资料不受此限。</li>
 *   <li>可以删除自己的草稿（从没上架过的）；运营端没有删除。</li>
 * </ul>
 * 操作记录里的操作人是商家自己的账号；平台运营在“商品与供给”里能看到这些商品并下架。机构只从令牌里取，由控制器传入。
 */
public class MerchantCatalogService {

    private final MallCatalogOpsService catalog;
    private final MallCatalogOpsMapper mapper;
    private final MallInquiryPorts.MerchantQualifications qualifications;

    public MerchantCatalogService(MallCatalogOpsService catalog, MallCatalogOpsMapper mapper,
                                  MallInquiryPorts.MerchantQualifications qualifications) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.qualifications = qualifications;
    }

    @Transactional(readOnly = true)
    public OpsProductPageView products(UUID merchantId, String keyword, String category, String saleMode,
                                       String status, int page, int size) {
        return catalog.products(keyword, category, saleMode, status, requireMerchant(merchantId), page, size);
    }

    @Transactional
    public OpsProductCreated create(UUID merchantId, UUID idempotencyKey, String category, String name,
                                    String saleMode, Operator operator) {
        return catalog.create(idempotencyKey, category, name, saleMode, operator, requireMerchant(merchantId));
    }

    @Transactional(readOnly = true)
    public OpsProductDetailView product(UUID merchantId, UUID productId) {
        return catalog.product(requireOwned(merchantId, productId));
    }

    @Transactional
    public OpsProductDetailView update(UUID merchantId, UUID productId, ProductDraft draft, Operator operator) {
        return catalog.update(requireOwned(merchantId, productId), draft, operator);
    }

    /** 上架要资质审核通过；下架随时可以。 */
    @Transactional
    public OpsProductDetailView changeStatus(UUID merchantId, UUID productId, String action, Integer version,
                                             Operator operator) {
        UUID id = requireOwned(merchantId, productId);
        if ("PUBLISH".equals(action) && !qualifications.reviewed(Set.of(merchantId)).contains(merchantId)) {
            throw new MallOpsException(Error.MERCHANT_NOT_QUALIFIED);
        }
        return catalog.changeStatus(id, action, version, operator);
    }

    /** 删除自己的草稿；上架过的只能下架。 */
    @Transactional
    public void delete(UUID merchantId, UUID productId, Integer version, Operator operator) {
        catalog.deleteDraft(requireOwned(merchantId, productId), version, operator);
    }

    @Transactional
    public OpsProductDetailView createSku(UUID merchantId, UUID productId, String name, Integer sortOrder,
                                          Operator operator) {
        return catalog.createSku(requireOwned(merchantId, productId), name, sortOrder, operator);
    }

    @Transactional
    public OpsProductDetailView updateSku(UUID merchantId, UUID productId, UUID skuId, String name,
                                          Integer sortOrder, String status, Integer version, Operator operator) {
        return catalog.updateSku(requireOwned(merchantId, productId), skuId, name, sortOrder, status, version,
                operator);
    }

    /** 供给的商家一律是本机构，不认请求里填的。 */
    @Transactional
    public OpsProductCreated createOffer(UUID merchantId, UUID productId, UUID idempotencyKey, OfferDraft draft,
                                         Operator operator) {
        UUID id = requireOwned(merchantId, productId);
        if (draft == null) {
            throw new IllegalArgumentException("offer is required");
        }
        OfferDraft own = new OfferDraft(draft.skuId(), merchantId, draft.merchantKind(), draft.cityCode(),
                draft.priceAmountMinor(), draft.priceUnit(), draft.stockQuantity(), draft.expressAvailable(),
                draft.shippingFeeMinor(), draft.pickupPoint(), draft.deliveryNote(), draft.shippingNote(),
                draft.fulfilledFrom());
        return catalog.createOffer(id, idempotencyKey, own, operator);
    }

    @Transactional
    public OpsProductDetailView updateOffer(UUID merchantId, UUID productId, UUID offerId, OfferDraft draft,
                                            Integer version, Operator operator) {
        return catalog.updateOffer(requireOwned(merchantId, productId), offerId, draft, version, operator);
    }

    @Transactional
    public OpsProductDetailView changeOfferStatus(UUID merchantId, UUID productId, UUID offerId, String action,
                                                  Integer version, Operator operator) {
        return catalog.changeOfferStatus(requireOwned(merchantId, productId), offerId, action, version, operator);
    }

    @Transactional
    public OpsMediaView uploadMedia(UUID merchantId, byte[] content, Operator operator) {
        requireMerchant(merchantId);
        return catalog.uploadMedia(content, operator);
    }

    private static UUID requireMerchant(UUID merchantId) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchant is required");
        }
        return merchantId;
    }

    /** 不是自己建的商品（含平台建的、别家的、已删除的）一律当作不存在。 */
    private UUID requireOwned(UUID merchantId, UUID productId) {
        requireMerchant(merchantId);
        ProductRow row = productId == null ? null : mapper.findProduct(productId);
        if (row == null || !merchantId.equals(row.ownerOrganizationId())) {
            throw new MallOpsException(Error.NOT_FOUND);
        }
        return row.productId();
    }
}
