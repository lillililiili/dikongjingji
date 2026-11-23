package com.lowaltitude.inspection.mall.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.mall.infrastructure.persistence.MallCatalogMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallMerchantMapper;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallMerchantMapper.OfferSpreadRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallMerchantMapper.ProfileRow;
import com.lowaltitude.inspection.mall.infrastructure.persistence.MallMerchantMapper.SubmissionRow;
import com.lowaltitude.inspection.ops.common.OpsException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * 商家主页与商家资料（S5-2，S4-4 默认决定“商家资料由机构在工作台填，平台在运营端审核”）：
 * <ul>
 *   <li>公开：商家主页——名称、类型、资质已审核、审核通过的资料、可售城市、这个城市的在售商品数，以及经端口取的活动和内容。</li>
 *   <li>商家（机构操作员）：看自己机构对外的资料和最近一次提交，提交新的一版（待审核时再交，上一版作废）。</li>
 *   <li>平台运营：按状态看提交、审核通过（整份写成对外资料）或驳回（写原因）。</li>
 * </ul>
 * 对外只展示审核通过的那一版；提交、审核都不影响已经对外的资料，直到下一版通过。
 */
public final class MallMerchantService {

    static final int PAGE_TABS_LIMIT = 10;
    static final int MAX_PAGE_SIZE = 50;
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";
    private static final Set<String> STATUSES = Set.of("SUBMITTED", "APPROVED", "REJECTED", "SUPERSEDED");

    private final MallMerchantMapper mapper;
    private final MallCatalogMapper catalog;
    private final MallInquiryPorts.MerchantQualifications qualifications;
    private final MallMerchantPorts.Activities activities;
    private final MallMerchantPorts.Contents contents;
    private final TransactionOperations tx;
    private final Clock clock;

    MallMerchantService(
            MallMerchantMapper mapper,
            MallCatalogMapper catalog,
            MallInquiryPorts.MerchantQualifications qualifications,
            MallMerchantPorts.Activities activities,
            MallMerchantPorts.Contents contents,
            TransactionOperations tx,
            Clock clock
    ) {
        this.mapper = mapper;
        this.catalog = catalog;
        this.qualifications = qualifications;
        this.activities = activities;
        this.contents = contents;
        this.tx = tx;
        this.clock = clock;
    }

    // ---- 公开：商家主页 ----

    /**
     * 商家主页。机构不存在、或既没有在售供给也没有审核通过的资料时 404（不是商家）。
     * kind 是这家商家供给里最多的商家类型；saleCityCodes 是有在售供给的城市（nationwide 表示有全国供给）。
     */
    public MallMerchantPageView page(UUID merchantId, String cityCode) {
        if (merchantId == null) {
            throw new IllegalArgumentException("merchant id is required");
        }
        if (cityCode == null || !cityCode.trim().matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        String city = cityCode.trim();
        String name = mapper.findOrganizationName(merchantId);
        if (name == null) {
            throw new OpsException(MallMerchantError.NOT_FOUND);
        }
        List<OfferSpreadRow> spread = mapper.findOfferSpread(merchantId);
        ProfileRow profile = mapper.findProfile(merchantId);
        if (spread.isEmpty() && profile == null) {
            throw new OpsException(MallMerchantError.NOT_FOUND);
        }
        MallMerchantKind kind = spread.isEmpty() ? null : MallMerchantKind.valueOf(spread.getFirst().merchantKind());
        Set<String> cities = new LinkedHashSet<>();
        boolean nationwide = false;
        for (OfferSpreadRow row : spread) {
            if (row.cityCode() == null) {
                nationwide = true;
            } else {
                cities.add(row.cityCode().trim());
            }
        }
        long productCount = catalog.countProducts(city, "ALL", null, List.of(), List.of(), List.of(), null, null,
                merchantId);
        MallMerchantPorts.Slice<MallMerchantPorts.ActivityItem> acts = activities.upcoming(merchantId, city,
                PAGE_TABS_LIMIT);
        MallMerchantPorts.Slice<MallMerchantPorts.ContentItem> posts = contents.published(merchantId, city,
                PAGE_TABS_LIMIT);
        return new MallMerchantPageView(
                merchantId,
                name,
                kind,
                qualifications.reviewed(List.of(merchantId)).contains(merchantId),
                profile == null ? null : profileView(profile),
                List.copyOf(cities),
                nationwide,
                productCount,
                acts.total(),
                acts.items().stream()
                        .map(item -> new MallMerchantActivityView(item.activityId(), item.title(),
                                MallCatalogReadService.secureMedia(item.coverUrl(), item.title()), item.venueName(),
                                item.startsAt(), item.priceMode(), item.priceAmountMinor()))
                        .toList(),
                posts.total(),
                posts.items().stream()
                        .map(item -> new MallMerchantContentView(item.contentId(), item.kind(), item.title(),
                                MallCatalogReadService.secureMedia(item.coverUrl(), item.title()), item.sponsored(),
                                item.sample(), item.publishedAt()))
                        .toList());
    }

    // ---- 商家：自己的资料 ----

    public MallMerchantProfileStateView readOwn(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("organization is required");
        }
        String name = mapper.findOrganizationName(organizationId);
        if (name == null) {
            throw new OpsException(MallMerchantError.NOT_FOUND);
        }
        ProfileRow profile = mapper.findProfile(organizationId);
        SubmissionRow latest = mapper.findLatestSubmission(organizationId);
        return new MallMerchantProfileStateView(organizationId, name,
                profile == null ? null : profileView(profile),
                latest == null ? null : submissionView(latest));
    }

    public record ProfileDraft(
            String businessEntity,
            String intro,
            String saleScopeNote,
            LocalDate authorizationUntil,
            String categoryNote,
            String invoiceIssuer,
            String aftersaleParty
    ) {
    }

    /** 提交新的一版（待审核时再交，上一版作废成 SUPERSEDED）。 */
    public MallMerchantProfileStateView submit(UUID organizationId, UUID operatorId, String operatorName,
                                               ProfileDraft draft) {
        if (organizationId == null || operatorId == null || operatorName == null || operatorName.isBlank()) {
            throw new IllegalArgumentException("organization and operator are required");
        }
        ProfileDraft valid = validate(draft);
        tx.executeWithoutResult(status -> {
            if (mapper.findOrganizationName(organizationId) == null) {
                throw new OpsException(MallMerchantError.NOT_FOUND);
            }
            mapper.lockOrganization(organizationId);
            Instant now = clock.instant();
            mapper.supersedePending(organizationId, now);
            mapper.insertSubmission(organizationId, valid.businessEntity(), valid.intro(), valid.saleScopeNote(),
                    valid.authorizationUntil(), valid.categoryNote(), valid.invoiceIssuer(), valid.aftersaleParty(),
                    operatorId, clip(operatorName.trim(), 80), now);
        });
        return readOwn(organizationId);
    }

    // ---- 平台运营：审核 ----

    public MallMerchantSubmissionPageView listSubmissions(String status, String keyword, int page, int size) {
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        String state = status == null || status.isBlank() ? null : status.trim();
        if (state != null && !STATUSES.contains(state)) {
            throw new IllegalArgumentException("status is invalid");
        }
        String pattern = null;
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            if (trimmed.length() > 50) {
                throw new IllegalArgumentException("keyword is too long");
            }
            pattern = "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        }
        long total = mapper.countSubmissions(state, pattern);
        List<MallMerchantSubmissionView> items = mapper.findSubmissions(state, pattern, size, (page - 1) * size)
                .stream()
                .map(MallMerchantService::submissionView)
                .toList();
        return new MallMerchantSubmissionPageView(page, size, total, (long) page * size < total, items);
    }

    public MallMerchantSubmissionDetailView readSubmission(UUID submissionId) {
        if (submissionId == null) {
            throw new IllegalArgumentException("submission id is required");
        }
        SubmissionRow row = mapper.findSubmission(submissionId);
        if (row == null) {
            throw new OpsException(MallMerchantError.NOT_FOUND);
        }
        ProfileRow published = mapper.findProfile(row.organizationId());
        return new MallMerchantSubmissionDetailView(
                submissionView(row),
                published == null ? null : profileView(published),
                qualifications.reviewed(List.of(row.organizationId())).contains(row.organizationId()),
                mapper.findHistory(row.organizationId()).stream().map(MallMerchantService::submissionView).toList());
    }

    public MallMerchantSubmissionDetailView approve(UUID submissionId, int version, UUID reviewerId,
                                                    String reviewerName) {
        review(submissionId, version, reviewerId, reviewerName, "APPROVED", null);
        return readSubmission(submissionId);
    }

    public MallMerchantSubmissionDetailView reject(UUID submissionId, int version, UUID reviewerId,
                                                   String reviewerName, String reason) {
        String why = text(reason, 200, false);
        review(submissionId, version, reviewerId, reviewerName, "REJECTED", why);
        return readSubmission(submissionId);
    }

    private void review(UUID submissionId, int version, UUID reviewerId, String reviewerName, String target,
                        String reason) {
        if (submissionId == null || reviewerId == null || reviewerName == null || reviewerName.isBlank()) {
            throw new IllegalArgumentException("submission and reviewer are required");
        }
        tx.executeWithoutResult(status -> {
            SubmissionRow row = mapper.lockSubmission(submissionId);
            if (row == null) {
                throw new OpsException(MallMerchantError.NOT_FOUND);
            }
            if (!"SUBMITTED".equals(row.status())) {
                throw new OpsException(MallMerchantError.REVIEW_CLOSED);
            }
            if (row.version() != version) {
                throw new OpsException(MallMerchantError.VERSION_CONFLICT);
            }
            Instant now = clock.instant();
            mapper.review(submissionId, version, target, reviewerId, clip(reviewerName.trim(), 80), reason, now);
            if ("APPROVED".equals(target)) {
                mapper.publish(submissionId, reviewerId, now);
            }
        });
    }

    // ---- 平台运营：商品的交易条款（S4-4） ----

    /** 商品是否支持七天无理由，和每个供给的开票方、售后责任方（含按商家资料兜底后的实际值）。 */
    public MallTradeTermsDetailView tradeTerms(UUID productId) {
        if (productId == null) {
            throw new IllegalArgumentException("product id is required");
        }
        MallMerchantMapper.ProductTermsRow product = mapper.findProductTerms(productId);
        if (product == null) {
            throw new OpsException(MallMerchantError.NOT_FOUND);
        }
        return new MallTradeTermsDetailView(product.id(), product.name(), product.noReasonReturn(),
                mapper.findOfferTerms(productId).stream()
                        .map(row -> new MallOfferTermsView(row.offerId(), row.skuName(), row.merchantId(),
                                row.merchantName(), row.cityCode(), row.status(), row.invoiceIssuer(),
                                row.aftersaleParty(),
                                firstPresent(row.invoiceIssuer(), row.profileInvoiceIssuer(), row.merchantName()),
                                firstPresent(row.aftersaleParty(), row.profileAftersaleParty(), row.merchantName())))
                        .toList());
    }

    public MallTradeTermsDetailView setNoReasonReturn(UUID productId, boolean value, UUID operatorId,
                                                      String operatorName) {
        requireOperator(productId, operatorId, operatorName);
        tx.executeWithoutResult(status -> {
            MallMerchantMapper.ProductTermsRow product = mapper.lockProductTerms(productId);
            if (product == null) {
                throw new OpsException(MallMerchantError.NOT_FOUND);
            }
            if (product.noReasonReturn() == value) {
                return;
            }
            Instant now = clock.instant();
            mapper.updateNoReasonReturn(productId, value, now);
            mapper.insertCatalogOperation(productId, "PRODUCT_UPDATE",
                    value ? "改为支持七天无理由（未拆封）" : "标为不支持七天无理由", operatorId,
                    clip(operatorName.trim(), 80), now);
        });
        return tradeTerms(productId);
    }

    /** 供给的开票方、售后责任方：留空表示按商家资料（再没有就是商家自己）。 */
    public MallTradeTermsDetailView setOfferTerms(UUID productId, UUID offerId, String invoiceIssuer,
                                                  String aftersaleParty, UUID operatorId, String operatorName) {
        requireOperator(productId, operatorId, operatorName);
        if (offerId == null) {
            throw new IllegalArgumentException("offer id is required");
        }
        String invoice = text(invoiceIssuer, 60, true);
        String aftersale = text(aftersaleParty, 60, true);
        tx.executeWithoutResult(status -> {
            if (mapper.lockOffer(productId, offerId) == null) {
                throw new OpsException(MallMerchantError.NOT_FOUND);
            }
            Instant now = clock.instant();
            mapper.updateOfferTerms(offerId, invoice, aftersale, now);
            mapper.insertCatalogOperation(productId, "OFFER_UPDATE",
                    "供给 " + offerId + " 开票方：" + (invoice == null ? "按商家资料" : invoice)
                            + "；售后责任方：" + (aftersale == null ? "按商家资料" : aftersale),
                    operatorId, clip(operatorName.trim(), 80), now);
        });
        return tradeTerms(productId);
    }

    private static void requireOperator(UUID productId, UUID operatorId, String operatorName) {
        if (productId == null || operatorId == null || operatorName == null || operatorName.isBlank()) {
            throw new IllegalArgumentException("product and operator are required");
        }
    }

    private static String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    // ---- 转换与校验 ----

    private static MallMerchantProfileView profileView(ProfileRow row) {
        return new MallMerchantProfileView(row.businessEntity(), row.intro(), row.saleScopeNote(),
                row.authorizationUntil(), row.categoryNote(), row.invoiceIssuer(), row.aftersaleParty(),
                row.approvedAt());
    }

    private static MallMerchantSubmissionView submissionView(SubmissionRow row) {
        return new MallMerchantSubmissionView(row.id(), row.organizationId(), row.organizationName(),
                new MallMerchantDraftView(row.businessEntity(), row.intro(), row.saleScopeNote(),
                        row.authorizationUntil(), row.categoryNote(), row.invoiceIssuer(), row.aftersaleParty()),
                row.status(), row.submittedByName(), row.submittedAt(), row.reviewedByName(), row.reviewedAt(),
                row.rejectReason(), row.version());
    }

    private static ProfileDraft validate(ProfileDraft draft) {
        if (draft == null) {
            throw new IllegalArgumentException("profile is required");
        }
        String entity = text(draft.businessEntity(), 160, false);
        if (entity.codePointCount(0, entity.length()) < 2) {
            throw new IllegalArgumentException("business entity is too short");
        }
        return new ProfileDraft(
                entity,
                text(draft.intro(), 300, true),
                text(draft.saleScopeNote(), 40, true),
                draft.authorizationUntil(),
                text(draft.categoryNote(), 40, true),
                text(draft.invoiceIssuer(), 60, true),
                text(draft.aftersaleParty(), 60, true));
    }

    private static String text(String value, int max, boolean optional) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            if (optional) {
                return null;
            }
            throw new IllegalArgumentException("text is required");
        }
        if (trimmed.codePointCount(0, trimmed.length()) > max
                || trimmed.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n')) {
            throw new IllegalArgumentException("text is invalid");
        }
        return trimmed;
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}

/** 商家主页（S5-2，契约 openapi-mall-merchant.yaml 的 MallMerchantPage）。 */
record MallMerchantPageView(
        UUID merchantId,
        String name,
        MallMerchantKind kind,
        boolean qualificationReviewed,
        MallMerchantProfileView profile,
        List<String> saleCityCodes,
        boolean nationwide,
        long productCount,
        long activityCount,
        List<MallMerchantActivityView> activities,
        long contentCount,
        List<MallMerchantContentView> contents
) {
}

/** 审核通过、对外展示的资料。 */
record MallMerchantProfileView(
        String businessEntity,
        String intro,
        String saleScopeNote,
        LocalDate authorizationUntil,
        String categoryNote,
        String invoiceIssuer,
        String aftersaleParty,
        Instant approvedAt
) {
}

record MallMerchantActivityView(UUID activityId, String title, MallMediaView coverImage, String venueName,
                                Instant startsAt, String priceMode, Long priceAmountMinor) {
}

record MallMerchantContentView(UUID contentId, String kind, String title, MallMediaView coverImage, boolean sponsored,
                               boolean sample, Instant publishedAt) {
}

/** 商家工作台：对外的资料（没通过过时为 null）和最近一次提交（没交过为 null）。 */
record MallMerchantProfileStateView(UUID organizationId, String organizationName, MallMerchantProfileView published,
                                    MallMerchantSubmissionView latest) {
}

record MallMerchantDraftView(
        String businessEntity,
        String intro,
        String saleScopeNote,
        LocalDate authorizationUntil,
        String categoryNote,
        String invoiceIssuer,
        String aftersaleParty
) {
}

/** status：SUBMITTED 待审核；APPROVED 已通过；REJECTED 已驳回；SUPERSEDED 已被新提交的一版替换。 */
record MallMerchantSubmissionView(
        UUID submissionId,
        UUID organizationId,
        String organizationName,
        MallMerchantDraftView draft,
        String status,
        String submittedByName,
        Instant submittedAt,
        String reviewedByName,
        Instant reviewedAt,
        String rejectReason,
        int version
) {
}

record MallMerchantSubmissionPageView(int page, int size, long total, boolean hasMore,
                                      List<MallMerchantSubmissionView> items) {
}

/** 运营端审核详情：这一版、现在对外的资料、机构资质审核过没有、这家最近 20 次提交。 */
record MallMerchantSubmissionDetailView(MallMerchantSubmissionView submission, MallMerchantProfileView published,
                                        boolean qualificationReviewed, List<MallMerchantSubmissionView> history) {
}

/** 运营端：商品的交易条款（S4-4）。 */
record MallTradeTermsDetailView(UUID productId, String productName, boolean noReasonReturn,
                                List<MallOfferTermsView> offers) {
}

/**
 * invoiceIssuer、aftersaleParty 是供给上填的（没填为 null）；effective… 是按供给 → 商家资料 → 商家名称取到的实际值，
 * 商品详情和订单上显示的就是它。
 */
record MallOfferTermsView(UUID offerId, String skuName, UUID merchantId, String merchantName, String cityCode,
                          String status, String invoiceIssuer, String aftersaleParty, String effectiveInvoiceIssuer,
                          String effectiveAftersaleParty) {
}
