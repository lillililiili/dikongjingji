package com.lowaltitude.inspection.mall.application;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.mall.application.MallOpsException.Error;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsCatalogOperationView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsIntroBlockView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsMediaView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsOfferView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsPackItemView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductCreated;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductDetailView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductPageView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductSummaryView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsProductView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsSkuView;
import com.lowaltitude.inspection.mall.application.MallOpsViews.OpsSpecView;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper.OfferFields;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper.OfferRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper.ProductRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogOpsMapper.SkuRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“商品与供给”（S3-1，契约 contracts/openapi-ops-catalog.yaml 1.0.0，迁移 V004）和批量导入的商品写入。
 *
 * <ul>
 *   <li>商品：新建是草稿（同一个幂等键只建一个）；资料整份替换卖点、参数、包装清单、图集、图文；交易方式建好后不改。</li>
 *   <li>上架前检查必填（一句话介绍、启用的规格、启用的供给），缺了拒绝并说明缺什么。</li>
 *   <li>供给 = 商家 × 规格 × 城市 × 价格 × 库存 × 配送；新建是停用的；启用前检查规格启用、购买和租赁有价格、标价购买的
 *       商家有有效收款账户（交易模块）。价格单位随交易方式：购买、询价按件，租赁按天或半天；询价不标价。</li>
 *   <li>图片地址只收受控地址（对象存储返回的）或带主机名的 https 地址。</li>
 *   <li>每一步都写一条操作记录；改价单独记“从多少改成多少”，上下架、启停供给各记一条。</li>
 * </ul>
 * 只有平台运营能调，角色由控制器检查。
 */
public class MallCatalogOpsService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_KEYWORD = 40;
    public static final int MAX_NAME = 160;
    public static final int MAX_SUMMARY = 300;
    public static final int MAX_SELLING_POINTS = 5;
    public static final int MAX_SELLING_POINT = 12;
    public static final int MAX_SPECS = 30;
    public static final int MAX_SPEC_LABEL = 40;
    public static final int MAX_SPEC_VALUE = 200;
    public static final int MAX_PACK_ITEMS = 30;
    public static final int MAX_PACK_NAME = 80;
    public static final int MAX_PACK_QUANTITY = 999;
    public static final int MAX_GALLERY = 9;
    public static final int MAX_INTRO_BLOCKS = 30;
    public static final int MAX_INTRO_TEXT = 1000;
    public static final int MAX_URL = 500;
    public static final int MAX_SORT_ORDER = 9999;
    public static final int MAX_SKUS = 20;
    public static final int MAX_SKU_NAME = 80;
    public static final long MAX_PRICE_MINOR = 10_000_000_000L;
    public static final long MAX_SHIPPING_FEE_MINOR = 10_000_000L;
    public static final int MAX_STOCK = 999_999;
    public static final int MAX_PICKUP_POINT = 120;
    public static final int MAX_NOTE = 200;
    public static final int MAX_FULFILLED_FROM = 120;
    public static final long MAX_MEDIA_BYTES = 5L * 1024 * 1024;
    public static final int OPERATION_HISTORY_LIMIT = 50;
    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");

    private final MallCatalogOpsMapper mapper;
    private final MallOpsPorts.Cities cities;
    private final MallOpsPorts.Merchants merchants;
    private final MallInquiryPorts.MerchantPayments payments;
    private final MallOpsPorts.MediaStore media;
    private final Clock clock;

    public MallCatalogOpsService(MallCatalogOpsMapper mapper, MallOpsPorts.Cities cities,
                                 MallOpsPorts.Merchants merchants, MallInquiryPorts.MerchantPayments payments,
                                 MallOpsPorts.MediaStore media, Clock clock) {
        this.mapper = mapper;
        this.cities = cities;
        this.merchants = merchants;
        this.payments = payments;
        this.media = media;
        this.clock = clock;
    }

    // ---- 商品 ----

    @Transactional(readOnly = true)
    public OpsProductPageView products(String keyword, String categoryCode, String saleModeCode, String statusCode,
                                       int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        String pattern = keywordPattern(keyword);
        String category = blank(categoryCode) ? null : parse(MallCategory.class, categoryCode, "category").name();
        String saleMode = blank(saleModeCode) ? null : parse(MallSaleMode.class, saleModeCode, "sale mode").name();
        String status = blank(statusCode) ? null : parse(OpsProductStatus.class, statusCode, "status").name();
        long total = mapper.countProducts(pattern, category, saleMode, status);
        List<OpsProductSummaryView> items = mapper.findProducts(pattern, category, saleMode, status, size,
                        Math.multiplyExact(page - 1, size)).stream()
                .map(row -> new OpsProductSummaryView(row.productId(), MallCategory.valueOf(row.category()),
                        row.name(), MallSaleMode.valueOf(row.saleMode()), OpsProductStatus.valueOf(row.status()),
                        row.coverUrl(), row.skuCount(), row.activeOfferCount(), row.updatedAt(), row.version()))
                .toList();
        return new OpsProductPageView(page, size, total, (long) page * size < total, items);
    }

    /** 新建草稿商品。同一个幂等键、同样内容再提交，返回第一次建的（created 为 false）；内容不同时拒绝。 */
    @Transactional
    public OpsProductCreated create(UUID idempotencyKey, String categoryCode, String name, String saleModeCode,
                                    Operator operator) {
        requireOperator(operator);
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        MallCategory category = parse(MallCategory.class, categoryCode, "category");
        MallSaleMode saleMode = parse(MallSaleMode.class, saleModeCode, "sale mode");
        String productName = text(name, MAX_NAME, "name");
        UUID existing = mapper.findProductByCreateKey(idempotencyKey);
        if (existing != null) {
            ProductRow row = mapper.findProduct(existing);
            if (row == null || !row.category().equals(category.name()) || !row.saleMode().equals(saleMode.name())
                    || !row.name().equals(productName)) {
                throw new MallOpsException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new OpsProductCreated(detail(existing), false);
        }
        OffsetDateTime at = now();
        UUID id = mapper.insertProduct(category.name(), productName, saleMode.name(), idempotencyKey, at);
        if (id == null) {
            // 同一个键的另一次提交刚刚写进去了。
            return create(idempotencyKey, categoryCode, name, saleModeCode, operator);
        }
        record(id, OpsCatalogAction.PRODUCT_CREATE, "新建" + saleModeLabel(saleMode) + "商品“" + productName
                + "”（草稿）", operator, at);
        return new OpsProductCreated(detail(id), true);
    }

    @Transactional(readOnly = true)
    public OpsProductDetailView product(UUID productId) {
        return detail(requireProduct(productId).productId());
    }

    /** 改资料：整份替换卖点、参数、包装清单、图集和图文。要带详情里读到的版本号。 */
    @Transactional
    public OpsProductDetailView update(UUID productId, ProductDraft draft, Operator operator) {
        requireOperator(operator);
        if (draft == null || draft.version() == null) {
            throw new IllegalArgumentException("product draft and version are required");
        }
        MallCategory category = parse(MallCategory.class, draft.category(), "category");
        String name = text(draft.name(), MAX_NAME, "name");
        String summary = optionalText(draft.summary(), MAX_SUMMARY, "summary");
        String coverUrl = optionalUrl(draft.coverUrl());
        MallExperienceBadge badge = blank(draft.experienceBadge())
                ? null : parse(MallExperienceBadge.class, draft.experienceBadge(), "experience badge");
        int sortOrder = sortOrder(draft.sortOrder());
        List<String> sellingPoints = sellingPoints(draft.sellingPoints());
        List<OpsSpecView> specs = specs(draft.specs());
        List<OpsPackItemView> packItems = packItems(draft.packItems());
        List<String> gallery = gallery(draft.gallery());
        List<OpsIntroBlockView> introBlocks = introBlocks(draft.introBlocks());

        UUID id = lock(productId);
        OffsetDateTime at = now();
        if (mapper.updateProduct(id, category.name(), name, summary, coverUrl, badge == null ? null : badge.name(),
                sortOrder, draft.version(), at) != 1) {
            throw new MallOpsException(Error.VERSION_CONFLICT);
        }
        replaceSellingPoints(id, sellingPoints, at);
        replaceSpecs(id, specs, at);
        replacePackItems(id, packItems, at);
        mapper.deleteGallery(id);
        for (int i = 0; i < gallery.size(); i++) {
            mapper.insertGalleryImage(id, gallery.get(i), (i + 1) * 10, at);
        }
        mapper.deleteIntroBlocks(id);
        for (int i = 0; i < introBlocks.size(); i++) {
            OpsIntroBlockView block = introBlocks.get(i);
            mapper.insertIntroBlock(id, block.type().name(), block.text(), block.imageUrl(), (i + 1) * 10, at);
        }
        record(id, OpsCatalogAction.PRODUCT_UPDATE, "修改商品资料：“" + name + "”，卖点 " + sellingPoints.size()
                + " 个、参数 " + specs.size() + " 项、包装清单 " + packItems.size() + " 项、图集 " + gallery.size()
                + " 张、图文 " + introBlocks.size() + " 段", operator, at);
        return detail(id);
    }

    /** 上架（检查必填）或下架。 */
    @Transactional
    public OpsProductDetailView changeStatus(UUID productId, String actionCode, Integer version, Operator operator) {
        requireOperator(operator);
        OpsProductStatusAction action = parse(OpsProductStatusAction.class, actionCode, "action");
        if (version == null) {
            throw new IllegalArgumentException("version is required");
        }
        UUID id = lock(productId);
        ProductRow row = mapper.findProduct(id);
        if (row.version() != version) {
            throw new MallOpsException(Error.VERSION_CONFLICT);
        }
        OffsetDateTime at = now();
        if (action == OpsProductStatusAction.PUBLISH) {
            if (OpsProductStatus.PUBLISHED.name().equals(row.status())) {
                throw new MallOpsException(Error.STATUS_UNCHANGED);
            }
            List<OpsPublishBlocker> blockers = publishBlockers(row, mapper.findSkus(id), mapper.findOffers(id));
            if (!blockers.isEmpty()) {
                throw new MallOpsException(Error.PRODUCT_INCOMPLETE, blockers.stream().map(Enum::name).toList());
            }
            if (mapper.updateProductStatus(id, OpsProductStatus.PUBLISHED.name(), version, at) != 1) {
                throw new MallOpsException(Error.VERSION_CONFLICT);
            }
            record(id, OpsCatalogAction.PRODUCT_PUBLISH, "上架商品“" + row.name() + "”", operator, at);
        } else {
            if (!OpsProductStatus.PUBLISHED.name().equals(row.status())) {
                throw new MallOpsException(Error.STATUS_UNCHANGED);
            }
            if (mapper.updateProductStatus(id, OpsProductStatus.OFFLINE.name(), version, at) != 1) {
                throw new MallOpsException(Error.VERSION_CONFLICT);
            }
            record(id, OpsCatalogAction.PRODUCT_UNPUBLISH, "下架商品“" + row.name() + "”", operator, at);
        }
        return detail(id);
    }

    // ---- 规格 ----

    @Transactional
    public OpsProductDetailView createSku(UUID productId, String name, Integer sortOrder, Operator operator) {
        requireOperator(operator);
        String skuName = text(name, MAX_SKU_NAME, "sku name");
        int order = sortOrder(sortOrder);
        UUID id = lock(productId);
        if (mapper.findSkus(id).size() >= MAX_SKUS) {
            throw new MallOpsException(Error.SKU_LIMIT);
        }
        OffsetDateTime at = now();
        mapper.insertSku(id, skuName, order, at);
        mapper.touchProduct(id, at);
        record(id, OpsCatalogAction.SKU_CREATE, "新增规格“" + skuName + "”", operator, at);
        return detail(id);
    }

    @Transactional
    public OpsProductDetailView updateSku(UUID productId, UUID skuId, String name, Integer sortOrder,
                                          String statusCode, Integer version, Operator operator) {
        requireOperator(operator);
        String skuName = text(name, MAX_SKU_NAME, "sku name");
        int order = sortOrder(sortOrder);
        OpsSkuStatus status = parse(OpsSkuStatus.class, statusCode, "status");
        if (skuId == null || version == null) {
            throw new IllegalArgumentException("sku and version are required");
        }
        UUID id = lock(productId);
        SkuRow current = mapper.findSkus(id).stream().filter(sku -> sku.skuId().equals(skuId)).findFirst()
                .orElseThrow(() -> new MallOpsException(Error.NOT_FOUND));
        OffsetDateTime at = now();
        if (current.version() != version
                || mapper.updateSku(id, skuId, skuName, order, status.name(), version, at) != 1) {
            throw new MallOpsException(Error.VERSION_CONFLICT);
        }
        mapper.touchProduct(id, at);
        List<String> changes = new ArrayList<>();
        if (!current.name().equals(skuName)) {
            changes.add("改名为“" + skuName + "”");
        }
        if (current.sortOrder() != order) {
            changes.add("排序 " + current.sortOrder() + " → " + order);
        }
        if (!current.status().equals(status.name())) {
            changes.add(status == OpsSkuStatus.ACTIVE ? "启用" : "停用");
        }
        record(id, OpsCatalogAction.SKU_UPDATE, "规格“" + current.name() + "”："
                + (changes.isEmpty() ? "没有改动" : String.join("，", changes)), operator, at);
        return detail(id);
    }

    // ---- 供给 ----

    /** 加一条供给（停用）。同一个幂等键、同样内容再提交，返回第一次的结果（created 为 false）。 */
    @Transactional
    public OpsProductCreated createOffer(UUID productId, UUID idempotencyKey, OfferDraft draft, Operator operator) {
        requireOperator(operator);
        if (idempotencyKey == null || draft == null || draft.skuId() == null || draft.merchantId() == null) {
            throw new IllegalArgumentException("offer is invalid");
        }
        UUID id = lock(productId);
        ProductRow product = mapper.findProduct(id);
        OfferFields fields = offerFields(MallSaleMode.valueOf(product.saleMode()), draft);
        UUID existing = mapper.findOfferByCreateKey(idempotencyKey);
        if (existing != null) {
            OfferRow row = mapper.findOffer(id, existing);
            if (row == null || !row.skuId().equals(draft.skuId()) || !row.merchantId().equals(draft.merchantId())
                    || !Objects.equals(row.cityCode(), fields.cityCode())
                    || !Objects.equals(row.priceAmountMinor(), fields.priceAmountMinor())) {
                throw new MallOpsException(Error.IDEMPOTENCY_CONFLICT);
            }
            return new OpsProductCreated(detail(id), false);
        }
        SkuRow sku = mapper.findSkus(id).stream().filter(candidate -> candidate.skuId().equals(draft.skuId()))
                .findFirst().orElseThrow(() -> new MallOpsException(Error.NOT_FOUND));
        String merchantName = merchants.activeProviderName(draft.merchantId())
                .orElseThrow(() -> new MallOpsException(Error.MERCHANT_UNKNOWN));
        if (mapper.countSameOffer(sku.skuId(), draft.merchantId(), fields.cityCode(), null) > 0) {
            throw new MallOpsException(Error.OFFER_DUPLICATE);
        }
        OffsetDateTime at = now();
        UUID offerId = mapper.insertOffer(sku.skuId(), draft.merchantId(), fields, idempotencyKey, at);
        if (offerId == null) {
            return createOffer(productId, idempotencyKey, draft, operator);
        }
        mapper.touchProduct(id, at);
        record(id, OpsCatalogAction.OFFER_CREATE, "新增供给：" + merchantName + " · 规格“" + sku.name() + "” · "
                + cityLabel(fields.cityCode()) + " · " + priceLabel(fields.priceAmountMinor(), fields.priceUnit())
                + " · 库存 " + stockLabel(fields.stockQuantity()) + "（停用）", operator, at);
        return new OpsProductCreated(detail(id), true);
    }

    /** 改供给（规格和商家不改）。改价单独留痕；正在启用的供给改完仍要满足启用条件。 */
    @Transactional
    public OpsProductDetailView updateOffer(UUID productId, UUID offerId, OfferDraft draft, Integer version,
                                            Operator operator) {
        requireOperator(operator);
        if (offerId == null || draft == null || version == null) {
            throw new IllegalArgumentException("offer is invalid");
        }
        UUID id = lock(productId);
        ProductRow product = mapper.findProduct(id);
        MallSaleMode saleMode = MallSaleMode.valueOf(product.saleMode());
        OfferFields fields = offerFields(saleMode, draft);
        OfferRow current = mapper.findOffer(id, offerId);
        if (current == null) {
            throw new MallOpsException(Error.NOT_FOUND);
        }
        if (current.version() != version) {
            throw new MallOpsException(Error.VERSION_CONFLICT);
        }
        if (mapper.countSameOffer(current.skuId(), current.merchantId(), fields.cityCode(), offerId) > 0) {
            throw new MallOpsException(Error.OFFER_DUPLICATE);
        }
        if (OpsOfferStatus.ACTIVE.name().equals(current.status())) {
            requireActivatable(saleMode, skuStatus(id, current.skuId()), fields.priceAmountMinor(),
                    current.merchantId());
        }
        OffsetDateTime at = now();
        if (mapper.updateOffer(offerId, fields, version, at) != 1) {
            throw new MallOpsException(Error.VERSION_CONFLICT);
        }
        mapper.touchProduct(id, at);
        String subject = current.merchantName() + " · 规格“" + current.skuName() + "”";
        if (!Objects.equals(current.priceAmountMinor(), fields.priceAmountMinor())
                || !current.priceUnit().equals(fields.priceUnit())) {
            record(id, OpsCatalogAction.OFFER_PRICE_CHANGE, "改价：" + subject + " "
                    + priceLabel(current.priceAmountMinor(), current.priceUnit()) + " → "
                    + priceLabel(fields.priceAmountMinor(), fields.priceUnit()), operator, at);
        }
        List<String> changes = new ArrayList<>();
        if (!Objects.equals(current.cityCode(), fields.cityCode())) {
            changes.add("城市 " + cityLabel(current.cityCode()) + " → " + cityLabel(fields.cityCode()));
        }
        if (!Objects.equals(current.stockQuantity(), fields.stockQuantity())) {
            changes.add("库存 " + stockLabel(current.stockQuantity()) + " → " + stockLabel(fields.stockQuantity()));
        }
        if (current.expressAvailable() != fields.expressAvailable()
                || current.shippingFeeMinor() != fields.shippingFeeMinor()
                || !Objects.equals(current.pickupPoint(), fields.pickupPoint())) {
            changes.add("配送 " + deliveryLabel(fields));
        }
        if (!current.merchantKind().equals(fields.merchantKind())
                || !Objects.equals(current.deliveryNote(), fields.deliveryNote())
                || !Objects.equals(current.shippingNote(), fields.shippingNote())
                || !Objects.equals(current.fulfilledFrom(), fields.fulfilledFrom())) {
            changes.add("商家类型与说明");
        }
        if (!changes.isEmpty()) {
            record(id, OpsCatalogAction.OFFER_UPDATE, "修改供给：" + subject + "，" + String.join("，", changes),
                    operator, at);
        }
        return detail(id);
    }

    /** 启用（检查规格、价格、收款账户）或停用一条供给。 */
    @Transactional
    public OpsProductDetailView changeOfferStatus(UUID productId, UUID offerId, String actionCode, Integer version,
                                                  Operator operator) {
        requireOperator(operator);
        OpsOfferStatusAction action = parse(OpsOfferStatusAction.class, actionCode, "action");
        if (offerId == null || version == null) {
            throw new IllegalArgumentException("offer and version are required");
        }
        UUID id = lock(productId);
        ProductRow product = mapper.findProduct(id);
        OfferRow current = mapper.findOffer(id, offerId);
        if (current == null) {
            throw new MallOpsException(Error.NOT_FOUND);
        }
        if (current.version() != version) {
            throw new MallOpsException(Error.VERSION_CONFLICT);
        }
        OpsOfferStatus target = action == OpsOfferStatusAction.ACTIVATE ? OpsOfferStatus.ACTIVE
                : OpsOfferStatus.DISABLED;
        if (target.name().equals(current.status())) {
            throw new MallOpsException(Error.STATUS_UNCHANGED);
        }
        if (target == OpsOfferStatus.ACTIVE) {
            requireActivatable(MallSaleMode.valueOf(product.saleMode()), skuStatus(id, current.skuId()),
                    current.priceAmountMinor(), current.merchantId());
        }
        OffsetDateTime at = now();
        if (mapper.updateOfferStatus(offerId, target.name(), version, at) != 1) {
            throw new MallOpsException(Error.VERSION_CONFLICT);
        }
        mapper.touchProduct(id, at);
        String subject = current.merchantName() + " · 规格“" + current.skuName() + "” · " + cityLabel(current.cityCode())
                + " · " + priceLabel(current.priceAmountMinor(), current.priceUnit());
        record(id, target == OpsOfferStatus.ACTIVE ? OpsCatalogAction.OFFER_ACTIVATE : OpsCatalogAction.OFFER_DEACTIVATE,
                (target == OpsOfferStatus.ACTIVE ? "启用供给：" : "停用供给：") + subject, operator, at);
        return detail(id);
    }

    // ---- 图片 ----

    /** 上传一张商品图片（JPEG、PNG、WebP，≤5 MB）。类型按文件头判断，不信客户端给的类型。 */
    @Transactional
    public OpsMediaView uploadMedia(byte[] content, Operator operator) {
        requireOperator(operator);
        if (content == null || content.length == 0 || content.length > MAX_MEDIA_BYTES) {
            throw new IllegalArgumentException("file size is invalid");
        }
        String contentType = sniffImage(content);
        UUID mediaId = UUID.randomUUID();
        String key = "products/" + mediaId + switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
        try {
            media.put(key, content, contentType);
        } catch (MallOpsPorts.MediaStoreUnavailableException unavailable) {
            throw new MallOpsException(Error.FILE_STORE_UNAVAILABLE);
        }
        String url = media.publicUrl(key);
        mapper.insertMedia(mediaId, key, contentType, content.length, sha256(content), url, operator.accountId(),
                now());
        return new OpsMediaView(mediaId, url, contentType, content.length);
    }

    // ---- 批量导入（S2-3）：导入工具校验完后，在它的事务里调 ----

    /**
     * 写一批商品（opsimport 的 MALL 类）。商品、规格、供给按给的 ID 新建或更新；同一个规格、商家、城市已经有供给时沿用库里
     * 那一条。每个商品记一条 IMPORT 操作记录。返回每个对象最终的 ID 和是新建还是更新。
     */
    @Transactional
    public List<MallCatalogImport.Written> importCatalog(List<MallCatalogImport.Product> products, String batchLabel,
                                                         Operator operator) {
        requireOperator(operator);
        OffsetDateTime at = now();
        List<MallCatalogImport.Written> written = new ArrayList<>();
        for (MallCatalogImport.Product product : products) {
            boolean productCreated = !mapper.productExists(product.productId());
            mapper.upsertProduct(product.productId(), product.category(), product.name(), blankToNull(product.summary()),
                    blankToNull(product.coverUrl()), product.saleMode(), blankToNull(product.experienceBadge()),
                    product.sortOrder(), product.status(), at);
            written.add(new MallCatalogImport.Written(MallCatalogImport.Kind.PRODUCT, product.reference(),
                    product.productId(), productCreated));
            if (product.sellingPoints() != null) {
                replaceSellingPoints(product.productId(), product.sellingPoints(), at);
            }
            if (product.specs() != null) {
                replaceSpecs(product.productId(), product.specs().stream()
                        .sorted(java.util.Comparator.comparingInt(MallCatalogImport.Spec::sortOrder))
                        .map(spec -> new OpsSpecView(spec.label(), spec.value())).toList(), at);
            }
            if (product.packItems() != null) {
                replacePackItems(product.productId(), product.packItems().stream()
                        .sorted(java.util.Comparator.comparingInt(MallCatalogImport.PackItem::sortOrder))
                        .map(item -> new OpsPackItemView(item.name(), item.quantity())).toList(), at);
            }
            int offerCount = 0;
            for (MallCatalogImport.Sku sku : product.skus()) {
                boolean skuCreated = !mapper.skuExists(sku.skuId());
                mapper.upsertSku(sku.skuId(), product.productId(), sku.name(), sku.sortOrder(), at);
                written.add(new MallCatalogImport.Written(MallCatalogImport.Kind.SKU, sku.reference(), sku.skuId(),
                        skuCreated));
                for (MallCatalogImport.Offer offer : sku.offers()) {
                    UUID same = mapper.findSameOffer(sku.skuId(), offer.merchantId(), blankToNull(offer.cityCode()));
                    UUID offerId = same != null ? same : offer.offerId();
                    boolean offerCreated = same == null && !mapper.offerExists(offerId);
                    mapper.upsertOffer(offerId, sku.skuId(), offer.merchantId(), new OfferFields(offer.merchantKind(),
                                    blankToNull(offer.cityCode()), offer.priceAmountMinor(), offer.priceUnit(),
                                    offer.stockQuantity(), offer.expressAvailable(), offer.shippingFeeMinor(),
                                    blankToNull(offer.pickupPoint()), blankToNull(offer.deliveryNote()),
                                    blankToNull(offer.shippingNote()), blankToNull(offer.fulfilledFrom())),
                            offer.status(), at);
                    written.add(new MallCatalogImport.Written(MallCatalogImport.Kind.OFFER, offer.reference(), offerId,
                            offerCreated));
                    offerCount++;
                }
            }
            record(product.productId(), OpsCatalogAction.IMPORT, "批量导入（" + batchLabel + "）"
                    + (productCreated ? "新建" : "更新") + "商品“" + product.name() + "”，规格 " + product.skus().size()
                    + " 个、供给 " + offerCount + " 条，状态 " + product.status(), operator, at);
        }
        return written;
    }

    // ---- 内部 ----

    private OpsProductDetailView detail(UUID id) {
        ProductRow row = mapper.findProduct(id);
        if (row == null) {
            throw new MallOpsException(Error.NOT_FOUND);
        }
        List<SkuRow> skus = mapper.findSkus(id);
        List<OfferRow> offers = mapper.findOffers(id);
        Map<UUID, Boolean> collecting = new HashMap<>();
        Map<String, Optional<String>> cityNames = new HashMap<>();
        List<OpsOfferView> offerViews = offers.stream()
                .map(offer -> new OpsOfferView(offer.offerId(), offer.skuId(), offer.skuName(), offer.merchantId(),
                        offer.merchantName(), MallMerchantKind.valueOf(offer.merchantKind()), offer.cityCode(),
                        offer.cityCode() == null ? null
                                : cityNames.computeIfAbsent(offer.cityCode(), cities::cityName).orElse(null),
                        offer.priceAmountMinor(), MallPriceUnit.valueOf(offer.priceUnit()), offer.stockQuantity(),
                        offer.expressAvailable(), offer.shippingFeeMinor(), offer.pickupPoint(), offer.deliveryNote(),
                        offer.shippingNote(), offer.fulfilledFrom(), OpsOfferStatus.valueOf(offer.status()),
                        collecting.computeIfAbsent(offer.merchantId(), payments::canCollect), offer.updatedAt(),
                        offer.version()))
                .toList();
        List<OpsCatalogOperationView> operations = mapper.findOperations(id, OPERATION_HISTORY_LIMIT).stream()
                .map(operation -> new OpsCatalogOperationView(operation.id(),
                        OpsCatalogAction.valueOf(operation.action()), operation.detail(), operation.operatorName(),
                        operation.occurredAt()))
                .toList();
        return new OpsProductDetailView(
                new OpsProductView(row.productId(), MallCategory.valueOf(row.category()), row.name(), row.summary(),
                        row.coverUrl(), MallSaleMode.valueOf(row.saleMode()),
                        row.experienceBadge() == null ? null : MallExperienceBadge.valueOf(row.experienceBadge()),
                        row.sortOrder(), productStatus(row.status()), row.createdAt(), row.updatedAt(),
                        row.version()),
                mapper.findSellingPoints(id),
                mapper.findSpecs(id).stream().map(spec -> new OpsSpecView(spec.label(), spec.value())).toList(),
                mapper.findPackItems(id).stream().map(item -> new OpsPackItemView(item.name(), item.quantity()))
                        .toList(),
                mapper.findGallery(id),
                mapper.findIntroBlocks(id).stream()
                        .map(block -> new OpsIntroBlockView(OpsIntroBlockType.valueOf(block.blockType()), block.text(),
                                block.imageUrl()))
                        .toList(),
                skus.stream().map(sku -> new OpsSkuView(sku.skuId(), sku.name(), sku.sortOrder(),
                        OpsSkuStatus.valueOf(sku.status()), sku.version())).toList(),
                offerViews,
                publishBlockers(row, skus, offers),
                operations);
    }

    static List<OpsPublishBlocker> publishBlockers(ProductRow row, List<SkuRow> skus, List<OfferRow> offers) {
        Set<OpsPublishBlocker> blockers = EnumSet.noneOf(OpsPublishBlocker.class);
        if (blank(row.summary())) {
            blockers.add(OpsPublishBlocker.SUMMARY_MISSING);
        }
        Set<UUID> activeSkus = new java.util.HashSet<>();
        for (SkuRow sku : skus) {
            if (OpsSkuStatus.ACTIVE.name().equals(sku.status())) {
                activeSkus.add(sku.skuId());
            }
        }
        if (activeSkus.isEmpty()) {
            blockers.add(OpsPublishBlocker.NO_ACTIVE_SKU);
        }
        boolean activeOffer = offers.stream().anyMatch(offer -> OpsOfferStatus.ACTIVE.name().equals(offer.status())
                && activeSkus.contains(offer.skuId()));
        if (!activeOffer) {
            blockers.add(OpsPublishBlocker.NO_ACTIVE_OFFER);
        }
        return List.copyOf(blockers);
    }

    /** 启用（或保持启用）一条供给的条件。 */
    private void requireActivatable(MallSaleMode saleMode, String skuStatus, Long priceAmountMinor, UUID merchantId) {
        if (!OpsSkuStatus.ACTIVE.name().equals(skuStatus)) {
            throw new MallOpsException(Error.OFFER_INCOMPLETE);
        }
        if (saleMode != MallSaleMode.INQUIRY && priceAmountMinor == null) {
            throw new MallOpsException(Error.OFFER_INCOMPLETE);
        }
        if (saleMode == MallSaleMode.BUY && !payments.canCollect(merchantId)) {
            throw new MallOpsException(Error.MERCHANT_NOT_COLLECTING);
        }
    }

    private String skuStatus(UUID productId, UUID skuId) {
        return mapper.findSkus(productId).stream().filter(sku -> sku.skuId().equals(skuId)).map(SkuRow::status)
                .findFirst().orElse(OpsSkuStatus.DISABLED.name());
    }

    /** 供给的字段校验：价格单位随交易方式，询价不标价，不能快递时要有自提点，城市要在目录里。 */
    private OfferFields offerFields(MallSaleMode saleMode, OfferDraft draft) {
        MallMerchantKind kind = parse(MallMerchantKind.class, draft.merchantKind(), "merchant kind");
        MallPriceUnit unit = parse(MallPriceUnit.class, draft.priceUnit(), "price unit");
        boolean unitFits = switch (saleMode) {
            case BUY, INQUIRY -> unit == MallPriceUnit.ITEM;
            case RENT -> unit == MallPriceUnit.DAY || unit == MallPriceUnit.HALF_DAY;
        };
        if (!unitFits) {
            throw new IllegalArgumentException("price unit does not fit the sale mode");
        }
        Long price = draft.priceAmountMinor();
        if (price != null && (price < 0 || price > MAX_PRICE_MINOR)) {
            throw new IllegalArgumentException("price is invalid");
        }
        if (saleMode == MallSaleMode.INQUIRY && price != null) {
            throw new IllegalArgumentException("an inquiry offer has no price");
        }
        Integer stock = draft.stockQuantity();
        if (stock != null && (stock < 0 || stock > MAX_STOCK)) {
            throw new IllegalArgumentException("stock is invalid");
        }
        if (draft.expressAvailable() == null || draft.shippingFeeMinor() == null
                || draft.shippingFeeMinor() < 0 || draft.shippingFeeMinor() > MAX_SHIPPING_FEE_MINOR) {
            throw new IllegalArgumentException("delivery is invalid");
        }
        String pickup = optionalText(draft.pickupPoint(), MAX_PICKUP_POINT, "pickup point");
        if (!draft.expressAvailable() && pickup == null) {
            throw new IllegalArgumentException("pickup point is required without express");
        }
        String city = blank(draft.cityCode()) ? null : draft.cityCode().strip();
        if (city != null) {
            if (!CITY_CODE.matcher(city).matches()) {
                throw new IllegalArgumentException("city code is invalid");
            }
            if (cities.cityName(city).isEmpty()) {
                throw new MallOpsException(Error.CITY_UNKNOWN);
            }
        }
        return new OfferFields(kind.name(), city, price, unit.name(), stock, draft.expressAvailable(),
                draft.shippingFeeMinor(), pickup, optionalText(draft.deliveryNote(), MAX_NOTE, "delivery note"),
                optionalText(draft.shippingNote(), MAX_NOTE, "shipping note"),
                optionalText(draft.fulfilledFrom(), MAX_FULFILLED_FROM, "fulfilled from"));
    }

    private void replaceSellingPoints(UUID productId, List<String> sellingPoints, OffsetDateTime at) {
        mapper.deleteSellingPoints(productId);
        for (int i = 0; i < sellingPoints.size(); i++) {
            mapper.insertSellingPoint(productId, sellingPoints.get(i), (i + 1) * 10, at);
        }
    }

    private void replaceSpecs(UUID productId, List<OpsSpecView> specs, OffsetDateTime at) {
        mapper.disableSpecs(productId, at);
        for (int i = 0; i < specs.size(); i++) {
            mapper.insertSpec(productId, specs.get(i).label(), specs.get(i).value(), (i + 1) * 10, at);
        }
    }

    private void replacePackItems(UUID productId, List<OpsPackItemView> items, OffsetDateTime at) {
        mapper.disablePackItems(productId, at);
        for (int i = 0; i < items.size(); i++) {
            mapper.insertPackItem(productId, items.get(i).name(), items.get(i).quantity(), (i + 1) * 10, at);
        }
    }

    static List<String> sellingPoints(List<String> values) {
        if (values == null || values.size() > MAX_SELLING_POINTS) {
            throw new IllegalArgumentException("selling points are invalid");
        }
        Set<String> cleaned = new LinkedHashSet<>();
        for (String value : values) {
            cleaned.add(text(value, MAX_SELLING_POINT, "selling point"));
        }
        if (cleaned.size() != values.size()) {
            throw new IllegalArgumentException("selling points repeat");
        }
        return List.copyOf(cleaned);
    }

    private static List<OpsSpecView> specs(List<OpsSpecView> values) {
        if (values == null || values.size() > MAX_SPECS) {
            throw new IllegalArgumentException("specs are invalid");
        }
        return values.stream().map(spec -> {
            if (spec == null) {
                throw new IllegalArgumentException("spec is invalid");
            }
            return new OpsSpecView(text(spec.label(), MAX_SPEC_LABEL, "spec label"),
                    text(spec.value(), MAX_SPEC_VALUE, "spec value"));
        }).toList();
    }

    private static List<OpsPackItemView> packItems(List<OpsPackItemView> values) {
        if (values == null || values.size() > MAX_PACK_ITEMS) {
            throw new IllegalArgumentException("pack items are invalid");
        }
        return values.stream().map(item -> {
            if (item == null || item.quantity() < 1 || item.quantity() > MAX_PACK_QUANTITY) {
                throw new IllegalArgumentException("pack item is invalid");
            }
            return new OpsPackItemView(text(item.name(), MAX_PACK_NAME, "pack item"), item.quantity());
        }).toList();
    }

    private List<String> gallery(List<String> values) {
        if (values == null || values.size() > MAX_GALLERY) {
            throw new IllegalArgumentException("gallery is invalid");
        }
        return values.stream().map(this::requiredUrl).toList();
    }

    private List<OpsIntroBlockView> introBlocks(List<OpsIntroBlockView> values) {
        if (values == null || values.size() > MAX_INTRO_BLOCKS) {
            throw new IllegalArgumentException("intro blocks are invalid");
        }
        return values.stream().map(block -> {
            if (block == null || block.type() == null) {
                throw new IllegalArgumentException("intro block is invalid");
            }
            if (block.type() == OpsIntroBlockType.TEXT) {
                if (!blank(block.imageUrl())) {
                    throw new IllegalArgumentException("a text block has no image");
                }
                return new OpsIntroBlockView(OpsIntroBlockType.TEXT, multiline(block.text(), MAX_INTRO_TEXT, true),
                        null);
            }
            return new OpsIntroBlockView(OpsIntroBlockType.IMAGE, multiline(block.text(), MAX_INTRO_TEXT, false),
                    requiredUrl(block.imageUrl()));
        }).toList();
    }

    private String optionalUrl(String value) {
        return blank(value) ? null : requiredUrl(value);
    }

    /** 受控地址：对象存储给出的地址，或带主机名的 https 地址。 */
    private String requiredUrl(String value) {
        String url = value == null ? "" : value.strip();
        if (url.isEmpty() || url.length() > MAX_URL) {
            throw new IllegalArgumentException("image url is invalid");
        }
        if (media.isControlled(url) || httpsWithHost(url)) {
            return url;
        }
        throw new MallOpsException(Error.MEDIA_URL_UNCONTROLLED);
    }

    static boolean httpsWithHost(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank();
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** 按文件头判断；不是 JPEG、PNG、WebP 的一律拒绝。 */
    static String sniffImage(byte[] content) {
        if (startsWith(content, 0, 0xFF, 0xD8, 0xFF)) {
            return "image/jpeg";
        }
        if (startsWith(content, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return "image/png";
        }
        if (startsWith(content, 0, 0x52, 0x49, 0x46, 0x46) && startsWith(content, 8, 0x57, 0x45, 0x42, 0x50)) {
            return "image/webp";
        }
        throw new IllegalArgumentException("file type is not supported");
    }

    private static boolean startsWith(byte[] content, int offset, int... prefix) {
        if (content.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[offset + i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private ProductRow requireProduct(UUID productId) {
        ProductRow row = productId == null ? null : mapper.findProduct(productId);
        if (row == null) {
            throw new MallOpsException(Error.NOT_FOUND);
        }
        return row;
    }

    private UUID lock(UUID productId) {
        UUID id = productId == null ? null : mapper.lockProduct(productId);
        if (id == null) {
            throw new MallOpsException(Error.NOT_FOUND);
        }
        return id;
    }

    private void record(UUID productId, OpsCatalogAction action, String detail, Operator operator,
                        OffsetDateTime at) {
        mapper.insertOperation(productId, action.name(), clip(detail, 500), operator.accountId(),
                clip(operator.displayName(), 80), at);
    }

    private String cityLabel(String cityCode) {
        if (cityCode == null) {
            return "全国";
        }
        return cities.cityName(cityCode).orElse(cityCode);
    }

    static String priceLabel(Long amountMinor, String unit) {
        if (amountMinor == null) {
            return "询价";
        }
        String yuan = amountMinor % 100 == 0
                ? String.valueOf(amountMinor / 100)
                : String.format(Locale.ROOT, "%d.%02d", amountMinor / 100, amountMinor % 100);
        String suffix = switch (unit) {
            case "DAY" -> "/天";
            case "HALF_DAY" -> "/半天";
            default -> "";
        };
        return "¥" + yuan + suffix;
    }

    private static String stockLabel(Integer stock) {
        return stock == null ? "不限" : stock + " 件";
    }

    private static String deliveryLabel(OfferFields fields) {
        List<String> parts = new ArrayList<>();
        if (fields.expressAvailable()) {
            parts.add("快递（运费 " + priceLabel(fields.shippingFeeMinor(), "ITEM") + "）");
        }
        if (fields.pickupPoint() != null) {
            parts.add("自提 " + fields.pickupPoint());
        }
        return String.join(" / ", parts);
    }

    private static String saleModeLabel(MallSaleMode saleMode) {
        return switch (saleMode) {
            case BUY -> "购买";
            case RENT -> "租赁";
            case INQUIRY -> "询价";
        };
    }

    /** 库里还有 DELETED（运营端不会写），读到时按下架显示。 */
    private static OpsProductStatus productStatus(String status) {
        try {
            return OpsProductStatus.valueOf(status);
        } catch (IllegalArgumentException unknown) {
            return OpsProductStatus.OFFLINE;
        }
    }

    private static int sortOrder(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0 || value > MAX_SORT_ORDER) {
            throw new IllegalArgumentException("sort order is invalid");
        }
        return value;
    }

    /** % 和 _ 去掉；空白当作没有。 */
    static String keywordPattern(String keyword) {
        if (keyword == null) {
            return null;
        }
        String cleaned = keyword.replaceAll("[%_\\\\]", "").strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.codePointCount(0, cleaned.length()) > MAX_KEYWORD) {
            throw new IllegalArgumentException("keyword is too long");
        }
        return "%" + cleaned + "%";
    }

    private static String text(String value, int max, String what) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > max
                || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return trimmed;
    }

    private static String optionalText(String value, int max, String what) {
        if (blank(value)) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > max || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(what + " is invalid");
        }
        return trimmed;
    }

    /** 图文段的文字可以换行。required 为 false 时空白返回 null。 */
    private static String multiline(String value, int max, boolean required) {
        if (blank(value)) {
            if (required) {
                throw new IllegalArgumentException("text is required");
            }
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.codePointCount(0, trimmed.length()) > max
                || trimmed.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw new IllegalArgumentException("text is invalid");
        }
        return trimmed;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String code, String what) {
        if (blank(code)) {
            throw new IllegalArgumentException(what + " is required");
        }
        try {
            return Enum.valueOf(type, code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException(what + " is invalid", unknown);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value.strip();
    }

    private static String clip(String value, int max) {
        return value.codePointCount(0, value.length()) <= max
                ? value
                : value.substring(0, value.offsetByCodePoints(0, max));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void requireOperator(Operator operator) {
        if (operator == null || operator.accountId() == null || blank(operator.displayName())) {
            throw new IllegalArgumentException("operator is required");
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** 谁在操作（平台运营的账号和显示名），写进操作记录。 */
    public record Operator(UUID accountId, String displayName) {
    }

    /** 商品资料。编码都是字符串（契约的枚举），version 是详情里读到的版本号。 */
    public record ProductDraft(String category, String name, String summary, String coverUrl, String experienceBadge,
                               Integer sortOrder, List<String> sellingPoints, List<OpsSpecView> specs,
                               List<OpsPackItemView> packItems, List<String> gallery,
                               List<OpsIntroBlockView> introBlocks, Integer version) {
    }

    /** 供给。改的时候 skuId、merchantId 不用（建好后不改）。 */
    public record OfferDraft(UUID skuId, UUID merchantId, String merchantKind, String cityCode, Long priceAmountMinor,
                             String priceUnit, Integer stockQuantity, Boolean expressAvailable, Long shippingFeeMinor,
                             String pickupPoint, String deliveryNote, String shippingNote, String fulfilledFrom) {
    }
}
