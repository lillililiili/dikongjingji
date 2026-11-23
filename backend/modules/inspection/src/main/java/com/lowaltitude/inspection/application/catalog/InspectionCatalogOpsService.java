package com.lowaltitude.inspection.application.catalog;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.identity.domain.IdentityOrganization;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper.CategoryRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper.CityRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper.DetailItemRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper.ItemRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper.ServiceDetailRow;
import com.lowaltitude.inspection.infrastructure.persistence.InspectionCatalogOpsMapper.ServiceFields;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.common.OpsText;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“巡检服务目录”（S3-1，契约 contracts/openapi-ops-inspection-catalog.yaml 1.0.0）：只给平台运营（控制器检查角色）。
 * <ul>
 *   <li>类目：新建（编码定了不改）、改名称 / 副标题 / 排序、启用 / 停用（下面还有上架服务时不能停用）；</li>
 *   <li>服务：新建（先是未上架）、修改、上架、下架。上架前校验 App 详情页要用的必填项，缺了 422 带 details.missing；
 *       已上架的服务改完也要齐全。覆盖城市只到城市级（PostGIS 中心点不在这里维护，距离排序用已有的中心点）；</li>
 *   <li>每次改动记一条改动记录（谁、什么时候、改了什么），带版本号防止覆盖别人的修改。</li>
 * </ul>
 * App 读取不变：公开目录、详情、首页推荐只给 status = ACTIVE、资料齐全、在这个城市覆盖的服务。
 */
public class InspectionCatalogOpsService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_KEYWORD = 40;
    public static final int CHANGE_HISTORY_LIMIT = 50;
    public static final int MAX_TITLE = 160;
    public static final int MAX_SUMMARY = 500;
    public static final int MAX_PRICE_NOTE = 240;
    public static final int MAX_CANCELLATION_NOTE = 500;
    public static final int MAX_UNIT_LABEL = 40;
    public static final int MAX_CITIES = 300;
    public static final int MAX_BOOKING_ITEMS = 10;
    public static final int MAX_PRICE_FACTORS = 10;
    public static final int MAX_SECTION_ITEMS = 20;
    public static final int MAX_BOOKING_ITEM_TITLE = 80;
    public static final int MAX_DETAIL_ITEM_TITLE = 120;
    public static final int MAX_ITEM_DESCRIPTION = 240;
    public static final int MAX_CATEGORY_NAME = 20;
    public static final int MAX_CATEGORY_SUBTITLE = 60;
    public static final int MAX_SORT_ORDER = 9999;
    public static final long MAX_AMOUNT_MINOR = 10_000_000_000L;

    /** 上架前缺了会在 details.missing 里出现的键，按页面上的顺序。 */
    public static final List<String> PUBLISH_FIELDS = List.of("provider", "summary", "categoryCode", "coverUrl",
            "priceMode", "startingAmountMinor", "unit", "availability", "primaryAction", "cities", "bookingItems",
            "processSteps", "deliverables");

    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");
    private static final Pattern CATEGORY_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");
    private static final Pattern ITEM_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_]{0,63}$");
    private static final Pattern ICON_KEY = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");
    private static final Pattern UNIT_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,31}$");
    private static final String SERVICE = "SERVICE";
    private static final String CATEGORY = "CATEGORY";

    private final InspectionCatalogOpsMapper mapper;
    private final IdentityOrganizationPort organizations;
    private final Clock clock;

    public InspectionCatalogOpsService(InspectionCatalogOpsMapper mapper, IdentityOrganizationPort organizations) {
        this(mapper, organizations, Clock.systemUTC());
    }

    InspectionCatalogOpsService(InspectionCatalogOpsMapper mapper, IdentityOrganizationPort organizations,
                                Clock clock) {
        this.mapper = mapper;
        this.organizations = organizations;
        this.clock = clock;
    }

    // ---- 类目 ----

    @Transactional(readOnly = true)
    public List<CategoryView> categories() {
        return mapper.findCategories().stream().map(InspectionCatalogOpsService::category).toList();
    }

    @Transactional
    public CategoryView createCategory(CategoryDraft draft, UUID operatorId, String operatorName) {
        Objects.requireNonNull(draft, "draft");
        String operator = OpsText.operatorName(operatorName);
        String code = draft.code() == null ? "" : draft.code().strip();
        if (!CATEGORY_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("category code is invalid");
        }
        String name = OpsText.required(draft.name(), MAX_CATEGORY_NAME, "name");
        String subtitle = OpsText.optional(draft.subtitle(), MAX_CATEGORY_SUBTITLE, "subtitle");
        int sortOrder = sortOrder(draft.sortOrder());
        String parentCode = draft.parentCode() == null || draft.parentCode().isBlank() ? null : draft.parentCode().strip();
        if (parentCode != null) {
            CategoryRow parent = mapper.findCategory(parentCode);
            if (parent == null || parent.parentCode() != null) {
                throw new OpsException(InspectionCatalogOpsError.CATEGORY_UNKNOWN);
            }
        }
        OffsetDateTime at = now();
        if (mapper.insertCategory(code, name, subtitle, parentCode, sortOrder, at) != 1) {
            throw new OpsException(InspectionCatalogOpsError.CATEGORY_CODE_TAKEN);
        }
        record(CATEGORY, categorySubject(code), "CREATE", "新建类目 " + code + "（" + name + "）", operatorId,
                operator, at);
        return category(mapper.findCategory(code));
    }

    @Transactional
    public CategoryView updateCategory(String code, String nameText, String subtitleText, Integer sortOrderValue,
                                       Integer version, UUID operatorId, String operatorName) {
        String operator = OpsText.operatorName(operatorName);
        String name = OpsText.required(nameText, MAX_CATEGORY_NAME, "name");
        String subtitle = OpsText.optional(subtitleText, MAX_CATEGORY_SUBTITLE, "subtitle");
        int sortOrder = sortOrder(sortOrderValue);
        int expected = version(version);
        CategoryRow current = requireCategory(code);
        if (current.version() != expected) {
            throw new OpsException(InspectionCatalogOpsError.VERSION_CONFLICT);
        }
        List<String> changed = new ArrayList<>();
        if (!current.name().equals(name)) {
            changed.add("名称 " + current.name() + " → " + name);
        }
        if (!Objects.equals(current.subtitle(), subtitle)) {
            changed.add("副标题");
        }
        if (current.sortOrder() != sortOrder) {
            changed.add("排序 " + current.sortOrder() + " → " + sortOrder);
        }
        if (changed.isEmpty()) {
            return category(current);
        }
        OffsetDateTime at = now();
        if (mapper.updateCategory(current.code(), name, subtitle, sortOrder, expected, at) != 1) {
            throw new OpsException(InspectionCatalogOpsError.VERSION_CONFLICT);
        }
        if (!current.name().equals(name)) {
            mapper.renameCategoryOnServices(current.code(), name);
        }
        record(CATEGORY, categorySubject(current.code()), "UPDATE",
                "类目 " + current.code() + "：" + String.join("；", changed), operatorId, operator, at);
        return category(mapper.findCategory(current.code()));
    }

    @Transactional
    public CategoryView changeCategoryStatus(String code, String statusText, Integer version, UUID operatorId,
                                             String operatorName) {
        String operator = OpsText.operatorName(operatorName);
        CategoryStatus target = OpsText.enumValue(CategoryStatus.class, statusText, "status");
        int expected = version(version);
        CategoryRow current = requireCategory(code);
        if (current.version() != expected) {
            throw new OpsException(InspectionCatalogOpsError.VERSION_CONFLICT);
        }
        if (CategoryStatus.valueOf(current.status()) == target) {
            throw new OpsException(InspectionCatalogOpsError.STATUS_UNCHANGED);
        }
        if (target == CategoryStatus.INACTIVE && mapper.countActiveServicesUnder(current.code()) > 0) {
            throw new OpsException(InspectionCatalogOpsError.CATEGORY_IN_USE);
        }
        OffsetDateTime at = now();
        if (mapper.updateCategoryStatus(current.code(), target.name(), expected, at) != 1) {
            throw new OpsException(InspectionCatalogOpsError.VERSION_CONFLICT);
        }
        record(CATEGORY, categorySubject(current.code()), target == CategoryStatus.ACTIVE ? "ENABLE" : "DISABLE",
                (target == CategoryStatus.ACTIVE ? "启用类目 " : "停用类目 ") + current.code() + "（" + current.name() + "）",
                operatorId, operator, at);
        return category(mapper.findCategory(current.code()));
    }

    @Transactional(readOnly = true)
    public List<OpsChangeView> categoryChanges(String code) {
        CategoryRow category = requireCategory(code);
        return changes(CATEGORY, categorySubject(category.code()));
    }

    /** 类目改动记录的对象 ID：由编码算出的固定 UUID（类目的主键是编码）。 */
    public static UUID categorySubject(String code) {
        return UUID.nameUUIDFromBytes(("inspection-category:" + code).getBytes(StandardCharsets.UTF_8));
    }

    // ---- 服务 ----

    @Transactional(readOnly = true)
    public ServicePageView services(String keyword, String categoryCode, String cityCode, String status, int page,
                                    int size) {
        OpsText.page(page, size, MAX_PAGE_SIZE);
        String filterKeyword = OpsText.keyword(keyword, MAX_KEYWORD);
        String filterCategory = categoryCode == null || categoryCode.isBlank() ? null : categoryCode.strip();
        String filterCity = cityCode == null || cityCode.isBlank() ? null : cityCode(cityCode);
        ServiceStatus filterStatus = OpsText.optionalEnum(ServiceStatus.class, status, "status");
        String statusName = filterStatus == null ? null : filterStatus.name();
        long total = mapper.countServices(filterKeyword, filterCategory, filterCity, statusName);
        List<ServiceRowView> items = mapper.findServices(filterKeyword, filterCategory, filterCity, statusName, size,
                        Math.multiplyExact(page - 1, size)).stream()
                .map(row -> new ServiceRowView(row.serviceId(), row.serviceNo(), row.title(), row.categoryCode(),
                        row.categoryName(), row.providerId(), row.providerName(), priceMode(row.priceMode()),
                        row.startingAmountMinor(), ServiceStatus.valueOf(row.status()), row.coverUrl(),
                        Math.toIntExact(row.cityCount()), row.updatedAt(), row.version()))
                .toList();
        return new ServicePageView(page, size, total, (long) page * size < total, items);
    }

    @Transactional(readOnly = true)
    public ServiceDetailView service(UUID serviceId) {
        return detail(requireService(serviceId));
    }

    @Transactional
    public ServiceDetailView createService(ServiceDraft draft, UUID operatorId, String operatorName) {
        String operator = OpsText.operatorName(operatorName);
        Normalized service = normalize(draft, Map.of());
        UUID id = UUID.randomUUID();
        String serviceNo = "INS-SVC-" + id.toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        OffsetDateTime at = now();
        if (mapper.insertService(id, serviceNo, service.fields(), at) != 1) {
            throw new IllegalStateException("inspection service was not created");
        }
        writeChildren(id, service, at);
        record(SERVICE, id, "CREATE", "新建服务“" + service.fields().title() + "”（未上架）", operatorId, operator, at);
        return detail(requireService(id));
    }

    @Transactional
    public ServiceDetailView updateService(UUID serviceId, ServiceDraft draft, Integer version, UUID operatorId,
                                           String operatorName) {
        String operator = OpsText.operatorName(operatorName);
        int expected = version(version);
        ServiceDetailRow row = requireService(serviceId);
        if (row.version() != expected) {
            throw new OpsException(InspectionCatalogOpsError.VERSION_CONFLICT);
        }
        ServiceDetailView current = detail(row);
        Normalized next = normalize(draft, currentCodes(current));
        if (ServiceStatus.valueOf(row.status()) == ServiceStatus.ACTIVE) {
            requireComplete(next.completeness());
        }
        List<String> changed = changedParts(current, next);
        if (changed.isEmpty()) {
            return current;
        }
        OffsetDateTime at = now();
        if (mapper.updateService(serviceId, next.fields(), expected, at) != 1) {
            throw new OpsException(InspectionCatalogOpsError.VERSION_CONFLICT);
        }
        writeChildren(serviceId, next, at);
        record(SERVICE, serviceId, "UPDATE", clipSummary("修改：" + String.join("、", changed)), operatorId,
                operator, at);
        return detail(requireService(serviceId));
    }

    /** 上架：资料要齐（缺的在 details.missing 里），服务商要在用。 */
    @Transactional
    public ServiceDetailView publish(UUID serviceId, Integer version, UUID operatorId, String operatorName) {
        return changeStatus(serviceId, version, ServiceStatus.ACTIVE, operatorId, operatorName);
    }

    /** 下架：App 上马上看不到；已有的预约、方案和订单照常进行。 */
    @Transactional
    public ServiceDetailView offline(UUID serviceId, Integer version, UUID operatorId, String operatorName) {
        return changeStatus(serviceId, version, ServiceStatus.DISABLED, operatorId, operatorName);
    }

    @Transactional(readOnly = true)
    public List<OpsChangeView> serviceChanges(UUID serviceId) {
        requireService(serviceId);
        return changes(SERVICE, serviceId);
    }

    private ServiceDetailView changeStatus(UUID serviceId, Integer version, ServiceStatus target, UUID operatorId,
                                           String operatorName) {
        String operator = OpsText.operatorName(operatorName);
        int expected = version(version);
        ServiceDetailRow row = requireService(serviceId);
        if (row.version() != expected) {
            throw new OpsException(InspectionCatalogOpsError.VERSION_CONFLICT);
        }
        if (ServiceStatus.valueOf(row.status()) == target) {
            throw new OpsException(InspectionCatalogOpsError.STATUS_UNCHANGED);
        }
        ServiceDetailView current = detail(row);
        if (target == ServiceStatus.ACTIVE) {
            requireComplete(completeness(current));
        }
        OffsetDateTime at = now();
        if (mapper.updateServiceStatus(serviceId, target.name(), expected, at) != 1) {
            throw new OpsException(InspectionCatalogOpsError.VERSION_CONFLICT);
        }
        record(SERVICE, serviceId, target == ServiceStatus.ACTIVE ? "PUBLISH" : "OFFLINE",
                (target == ServiceStatus.ACTIVE ? "上架“" : "下架“") + current.title() + "”", operatorId, operator, at);
        return detail(requireService(serviceId));
    }

    // ---- 整理输入 ----

    private Normalized normalize(ServiceDraft draft, Map<String, Set<String>> existingCodes) {
        Objects.requireNonNull(draft, "draft");
        if (draft.providerId() == null) {
            throw new IllegalArgumentException("provider is required");
        }
        IdentityOrganization provider = organizations.findActiveOrganization(draft.providerId())
                .filter(found -> "PROVIDER".equals(found.organizationType()))
                .orElseThrow(() -> new OpsException(InspectionCatalogOpsError.PROVIDER_UNKNOWN));
        String title = OpsText.required(draft.title(), MAX_TITLE, "title");
        String summary = OpsText.optional(draft.summary(), MAX_SUMMARY, "summary");
        String categoryCode = draft.categoryCode() == null || draft.categoryCode().isBlank()
                ? null : draft.categoryCode().strip();
        String categoryName = null;
        if (categoryCode != null) {
            CategoryRow category = mapper.findCategory(categoryCode);
            if (category == null || !CategoryStatus.ACTIVE.name().equals(category.status())) {
                throw new OpsException(InspectionCatalogOpsError.CATEGORY_UNKNOWN);
            }
            categoryName = category.name();
        }
        String coverUrl = OpsText.httpsUrl(draft.coverUrl(), "coverUrl");
        PriceMode mode = OpsText.optionalEnum(PriceMode.class, draft.priceMode(), "priceMode");
        Long amount = null;
        String unitCode = null;
        String unitLabel = null;
        if (mode != null && mode != PriceMode.PROJECT_QUOTE) {
            amount = draft.startingAmountMinor();
            if (amount != null && (amount < 0 || amount > MAX_AMOUNT_MINOR)) {
                throw new IllegalArgumentException("startingAmountMinor is invalid");
            }
            unitCode = draft.unitCode() == null || draft.unitCode().isBlank() ? null : draft.unitCode().strip();
            unitLabel = OpsText.optional(draft.unitLabel(), MAX_UNIT_LABEL, "unitLabel");
            if ((unitCode == null) != (unitLabel == null)
                    || (unitCode != null && !UNIT_CODE.matcher(unitCode).matches())) {
                throw new IllegalArgumentException("unit is invalid");
            }
        }
        String priceNote = OpsText.optional(draft.priceNote(), MAX_PRICE_NOTE, "priceNote");
        String cancellationNote = OpsText.optional(draft.cancellationNote(), MAX_CANCELLATION_NOTE,
                "cancellationNote");
        Availability availability = OpsText.optionalEnum(Availability.class, draft.availability(), "availability");
        PrimaryAction action = OpsText.optionalEnum(PrimaryAction.class, draft.primaryAction(), "primaryAction");
        List<ServiceMethod> methods = new ArrayList<>(new LinkedHashSet<>(
                (draft.serviceMethods() == null ? List.<String>of() : draft.serviceMethods()).stream()
                        .map(method -> OpsText.enumValue(ServiceMethod.class, method, "serviceMethods"))
                        .toList()));
        List<CityRow> cities = cities(draft.cityCodes());
        List<ItemRow> bookingItems = items(draft.bookingItems(), MAX_BOOKING_ITEMS, MAX_BOOKING_ITEM_TITLE,
                existingCodes.getOrDefault("BOOKING", Set.of()));
        Map<Section, List<ItemRow>> sections = new LinkedHashMap<>();
        sections.put(Section.PRICE_FACTOR, items(draft.priceFactors(), MAX_PRICE_FACTORS, MAX_DETAIL_ITEM_TITLE,
                existingCodes.getOrDefault(Section.PRICE_FACTOR.name(), Set.of())));
        sections.put(Section.CAPABILITY, items(draft.capabilities(), MAX_SECTION_ITEMS, MAX_DETAIL_ITEM_TITLE,
                existingCodes.getOrDefault(Section.CAPABILITY.name(), Set.of())));
        sections.put(Section.PROCESS, items(draft.processSteps(), MAX_SECTION_ITEMS, MAX_DETAIL_ITEM_TITLE,
                existingCodes.getOrDefault(Section.PROCESS.name(), Set.of())));
        sections.put(Section.DELIVERABLE, items(draft.deliverables(), MAX_SECTION_ITEMS, MAX_DETAIL_ITEM_TITLE,
                existingCodes.getOrDefault(Section.DELIVERABLE.name(), Set.of())));
        sections.put(Section.GUARANTEE, items(draft.guarantees(), MAX_SECTION_ITEMS, MAX_DETAIL_ITEM_TITLE,
                existingCodes.getOrDefault(Section.GUARANTEE.name(), Set.of())));
        ServiceFields fields = new ServiceFields(provider.organizationId(), title, summary, categoryCode, categoryName,
                coverUrl, cities.get(0).cityCode(), mode == null ? null : mode.name(), amount,
                mode == null ? null : "CNY", unitCode, unitLabel, priceNote, cancellationNote,
                availability == null ? null : availability.name(), action == null ? null : action.name(),
                Boolean.TRUE.equals(draft.includesReport()));
        return new Normalized(fields, provider.name(), methods, cities, bookingItems, sections);
    }

    private List<CityRow> cities(List<String> codes) {
        if (codes == null || codes.isEmpty() || codes.size() > MAX_CITIES) {
            throw new IllegalArgumentException("cityCodes is invalid");
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(codes.stream()
                .map(InspectionCatalogOpsService::cityCode).toList()));
        Map<String, String> names = new LinkedHashMap<>();
        for (CityRow row : mapper.findDirectoryCities(distinct)) {
            names.put(row.cityCode(), row.cityName());
        }
        if (!names.keySet().containsAll(distinct)) {
            throw new OpsException(InspectionCatalogOpsError.CITY_UNKNOWN);
        }
        return distinct.stream().map(code -> new CityRow(code, names.get(code))).toList();
    }

    /** 一组服务项目或详情项：编码保留（预约和方案按编码引用过），新项生成 OPS_ 开头的编码；同一组里编码不能重复。 */
    private static List<ItemRow> items(List<ItemDraft> drafts, int maxItems, int maxTitle, Set<String> existing) {
        if (drafts == null) {
            return List.of();
        }
        if (drafts.size() > maxItems) {
            throw new IllegalArgumentException("too many items");
        }
        Set<String> seen = new HashSet<>();
        List<ItemRow> items = new ArrayList<>();
        for (ItemDraft draft : drafts) {
            if (draft == null) {
                throw new IllegalArgumentException("item is required");
            }
            String code = draft.code() == null || draft.code().isBlank() ? null : draft.code().strip();
            if (code != null && !ITEM_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("item code is invalid");
            }
            if (code == null) {
                do {
                    code = "OPS_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8)
                            .toUpperCase(Locale.ROOT);
                } while (existing.contains(code) || seen.contains(code));
            }
            if (!seen.add(code)) {
                throw new IllegalArgumentException("item code is repeated");
            }
            String iconKey = draft.iconKey() == null || draft.iconKey().isBlank() ? null : draft.iconKey().strip();
            if (iconKey != null && !ICON_KEY.matcher(iconKey).matches()) {
                throw new IllegalArgumentException("icon key is invalid");
            }
            items.add(new ItemRow(code, OpsText.required(draft.title(), maxTitle, "item title"),
                    OpsText.optional(draft.description(), MAX_ITEM_DESCRIPTION, "item description"), iconKey));
        }
        return items;
    }

    private void writeChildren(UUID serviceId, Normalized service, OffsetDateTime at) {
        List<String> cityCodes = service.cities().stream().map(CityRow::cityCode).toList();
        mapper.disableOtherCities(serviceId, cityCodes, at);
        for (String cityCode : cityCodes) {
            mapper.activateCity(serviceId, cityCode, at);
        }
        mapper.disableOtherBookingItems(serviceId, service.bookingItems().stream().map(ItemRow::code).toList(), at);
        for (int index = 0; index < service.bookingItems().size(); index++) {
            mapper.upsertBookingItem(serviceId, service.bookingItems().get(index), (index + 1) * 10, at);
        }
        for (Map.Entry<Section, List<ItemRow>> section : service.sections().entrySet()) {
            List<ItemRow> items = section.getValue();
            mapper.disableOtherDetailItems(serviceId, section.getKey().name(), items.stream().map(ItemRow::code)
                    .toList(), at);
            for (int index = 0; index < items.size(); index++) {
                mapper.upsertDetailItem(serviceId, section.getKey().name(), items.get(index), (index + 1) * 10, at);
            }
        }
        mapper.deleteMethods(serviceId);
        for (ServiceMethod method : service.methods()) {
            mapper.insertMethod(serviceId, method.name(), at);
        }
    }

    // ---- 必填校验 ----

    private static void requireComplete(Completeness completeness) {
        List<String> missing = missing(completeness);
        if (!missing.isEmpty()) {
            throw OpsException.incomplete(InspectionCatalogOpsError.PUBLISH_INCOMPLETE, missing);
        }
    }

    static List<String> missing(Completeness c) {
        List<String> missing = new ArrayList<>();
        if (!c.providerActive()) {
            missing.add("provider");
        }
        if (c.summary() == null) {
            missing.add("summary");
        }
        if (c.categoryCode() == null) {
            missing.add("categoryCode");
        }
        if (c.coverUrl() == null) {
            missing.add("coverUrl");
        }
        if (c.priceMode() == null) {
            missing.add("priceMode");
        } else if (c.priceMode() != PriceMode.PROJECT_QUOTE) {
            if (c.startingAmountMinor() == null) {
                missing.add("startingAmountMinor");
            }
            if (c.priceMode() != PriceMode.STARTING_PRICE && c.unitCode() == null) {
                missing.add("unit");
            }
        }
        if (c.availability() == null) {
            missing.add("availability");
        }
        if (c.primaryAction() == null) {
            missing.add("primaryAction");
        }
        if (c.cities() == 0) {
            missing.add("cities");
        }
        if (c.bookingItems() == 0) {
            missing.add("bookingItems");
        }
        if (c.processSteps() == 0) {
            missing.add("processSteps");
        }
        if (c.deliverables() == 0) {
            missing.add("deliverables");
        }
        return missing;
    }

    private Completeness completeness(ServiceDetailView view) {
        boolean providerActive = organizations.findActiveOrganization(view.providerId())
                .filter(found -> "PROVIDER".equals(found.organizationType())).isPresent();
        return new Completeness(providerActive, view.summary(), view.categoryCode(), view.coverUrl(),
                view.priceMode(), view.startingAmountMinor(), view.unitCode(), view.availability(),
                view.primaryAction(), view.cities().size(), view.bookingItems().size(), view.processSteps().size(),
                view.deliverables().size());
    }

    // ---- 改了什么（写进改动记录） ----

    private static List<String> changedParts(ServiceDetailView current, Normalized next) {
        ServiceFields fields = next.fields();
        List<String> changed = new ArrayList<>();
        if (!current.providerId().equals(fields.providerId())) {
            changed.add("服务商");
        }
        if (!current.title().equals(fields.title())) {
            changed.add("标题");
        }
        if (!Objects.equals(current.summary(), fields.summary())) {
            changed.add("简介");
        }
        if (!Objects.equals(current.categoryCode(), fields.categoryCode())) {
            changed.add("类目");
        }
        if (!Objects.equals(current.coverUrl(), fields.coverUrl())) {
            changed.add("封面");
        }
        if (!Objects.equals(name(current.priceMode()), fields.priceMode())
                || !Objects.equals(current.startingAmountMinor(), fields.startingAmountMinor())
                || !Objects.equals(current.unitCode(), fields.unitCode())
                || !Objects.equals(current.unitLabel(), fields.unitLabel())
                || !Objects.equals(current.priceNote(), fields.priceNote())) {
            changed.add("价格");
        }
        if (!Objects.equals(current.cancellationNote(), fields.cancellationNote())) {
            changed.add("取消说明");
        }
        if (!Objects.equals(name(current.availability()), fields.availability())
                || !Objects.equals(name(current.primaryAction()), fields.primaryAction())) {
            changed.add("预约方式");
        }
        if (current.includesReport() != fields.includesReport() || !current.serviceMethods().equals(next.methods())) {
            changed.add("巡检方式与报告");
        }
        if (!current.cities().stream().map(OpsCityView::cityCode).toList()
                .equals(next.cities().stream().map(CityRow::cityCode).toList())) {
            changed.add("覆盖城市（" + next.cities().size() + " 个）");
        }
        if (!sameItems(current.bookingItems(), next.bookingItems())) {
            changed.add("服务项目");
        }
        Map<Section, List<OpsItemView>> currentSections = Map.of(
                Section.PRICE_FACTOR, current.priceFactors(), Section.CAPABILITY, current.capabilities(),
                Section.PROCESS, current.processSteps(), Section.DELIVERABLE, current.deliverables(),
                Section.GUARANTEE, current.guarantees());
        for (Map.Entry<Section, List<ItemRow>> section : next.sections().entrySet()) {
            if (!sameItems(currentSections.get(section.getKey()), section.getValue())) {
                changed.add(section.getKey().label);
            }
        }
        return changed;
    }

    private static boolean sameItems(List<OpsItemView> current, List<ItemRow> next) {
        if (current.size() != next.size()) {
            return false;
        }
        for (int index = 0; index < current.size(); index++) {
            OpsItemView left = current.get(index);
            ItemRow right = next.get(index);
            if (!left.code().equals(right.code()) || !left.title().equals(right.title())
                    || !Objects.equals(left.description(), right.description())
                    || !Objects.equals(left.iconKey(), right.iconKey())) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Set<String>> currentCodes(ServiceDetailView view) {
        Map<String, Set<String>> codes = new LinkedHashMap<>();
        codes.put("BOOKING", codesOf(view.bookingItems()));
        codes.put(Section.PRICE_FACTOR.name(), codesOf(view.priceFactors()));
        codes.put(Section.CAPABILITY.name(), codesOf(view.capabilities()));
        codes.put(Section.PROCESS.name(), codesOf(view.processSteps()));
        codes.put(Section.DELIVERABLE.name(), codesOf(view.deliverables()));
        codes.put(Section.GUARANTEE.name(), codesOf(view.guarantees()));
        return codes;
    }

    private static Set<String> codesOf(List<OpsItemView> items) {
        Set<String> codes = new HashSet<>();
        items.forEach(item -> codes.add(item.code()));
        return codes;
    }

    // ---- 读出来给页面 ----

    private ServiceDetailView detail(ServiceDetailRow row) {
        Map<String, List<OpsItemView>> sections = new LinkedHashMap<>();
        for (Section section : Section.values()) {
            sections.put(section.name(), new ArrayList<>());
        }
        for (DetailItemRow item : mapper.findDetailItems(row.serviceId())) {
            List<OpsItemView> list = sections.get(item.sectionType());
            if (list != null) {
                list.add(new OpsItemView(item.code(), item.title(), item.description(), item.iconKey()));
            }
        }
        List<ServiceMethod> methods = mapper.findMethods(row.serviceId()).stream()
                .map(ServiceMethod::valueOf).toList();
        return new ServiceDetailView(row.serviceId(), row.serviceNo(), ServiceStatus.valueOf(row.status()),
                row.version(), row.updatedAt(), row.providerId(), row.providerName(), row.title(), row.summary(),
                row.categoryCode(), row.categoryName(), row.coverUrl(), priceMode(row.priceMode()),
                row.startingAmountMinor(), row.unitCode(), row.unitLabel(), row.priceNote(), row.cancellationNote(),
                row.availability() == null ? null : Availability.valueOf(row.availability()),
                row.primaryAction() == null ? null : PrimaryAction.valueOf(row.primaryAction()),
                row.includesReport(), methods,
                mapper.findServiceCities(row.serviceId()).stream()
                        .map(city -> new OpsCityView(city.cityCode(), city.cityName())).toList(),
                mapper.findBookingItems(row.serviceId()).stream()
                        .map(item -> new OpsItemView(item.code(), item.title(), item.description(), item.iconKey()))
                        .toList(),
                List.copyOf(sections.get(Section.PRICE_FACTOR.name())),
                List.copyOf(sections.get(Section.CAPABILITY.name())),
                List.copyOf(sections.get(Section.PROCESS.name())),
                List.copyOf(sections.get(Section.DELIVERABLE.name())),
                List.copyOf(sections.get(Section.GUARANTEE.name())),
                row.completedCount());
    }

    private static CategoryView category(CategoryRow row) {
        return new CategoryView(row.code(), row.name(), row.subtitle(), row.parentCode(), row.sortOrder(),
                CategoryStatus.valueOf(row.status()), row.activeServiceCount(), row.version(), row.updatedAt());
    }

    private List<OpsChangeView> changes(String subjectType, UUID subjectId) {
        return mapper.findChanges(subjectType, subjectId, CHANGE_HISTORY_LIMIT).stream()
                .map(row -> new OpsChangeView(row.changeId(), row.subjectType(), row.subjectId(), row.action(),
                        row.summary(), row.operatorName(), row.changedAt()))
                .toList();
    }

    private void record(String subjectType, UUID subjectId, String action, String summary, UUID operatorId,
                        String operatorName, OffsetDateTime at) {
        if (operatorId == null) {
            throw new IllegalArgumentException("operator is required");
        }
        if (mapper.insertChange(subjectType, subjectId, action, clipSummary(summary), operatorId, operatorName, at)
                != 1) {
            throw new IllegalStateException("catalog change was not recorded");
        }
    }

    private CategoryRow requireCategory(String code) {
        CategoryRow row = code == null || code.isBlank() ? null : mapper.findCategory(code.strip());
        if (row == null) {
            throw new OpsException(InspectionCatalogOpsError.NOT_FOUND);
        }
        return row;
    }

    private ServiceDetailRow requireService(UUID serviceId) {
        ServiceDetailRow row = serviceId == null ? null : mapper.findService(serviceId);
        if (row == null) {
            throw new OpsException(InspectionCatalogOpsError.NOT_FOUND);
        }
        return row;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String clipSummary(String summary) {
        return OpsText.clip(summary, 300);
    }

    private static String cityCode(String code) {
        if (code == null || !CITY_CODE.matcher(code.strip()).matches()) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return code.strip();
    }

    private static int sortOrder(Integer value) {
        int order = value == null ? 0 : value;
        if (order < 0 || order > MAX_SORT_ORDER) {
            throw new IllegalArgumentException("sortOrder is invalid");
        }
        return order;
    }

    private static int version(Integer version) {
        if (version == null || version < 0) {
            throw new IllegalArgumentException("version is required");
        }
        return version;
    }

    private static PriceMode priceMode(String code) {
        return code == null ? null : PriceMode.valueOf(code);
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    // ---- 内部形状 ----

    private record Normalized(ServiceFields fields, String providerName, List<ServiceMethod> methods,
                              List<CityRow> cities, List<ItemRow> bookingItems, Map<Section, List<ItemRow>> sections) {

        Completeness completeness() {
            return new Completeness(true, fields.summary(), fields.categoryCode(), fields.coverUrl(),
                    fields.priceMode() == null ? null : PriceMode.valueOf(fields.priceMode()),
                    fields.startingAmountMinor(), fields.unitCode(),
                    fields.availability() == null ? null : Availability.valueOf(fields.availability()),
                    fields.primaryAction() == null ? null : PrimaryAction.valueOf(fields.primaryAction()),
                    cities.size(), bookingItems.size(), sections.get(Section.PROCESS).size(),
                    sections.get(Section.DELIVERABLE).size());
        }
    }

    record Completeness(boolean providerActive, String summary, String categoryCode, String coverUrl,
                        PriceMode priceMode, Long startingAmountMinor, String unitCode, Availability availability,
                        PrimaryAction primaryAction, int cities, int bookingItems, int processSteps,
                        int deliverables) {
    }

    /** 详情项分组（inspection_service_detail_item.section_type）。 */
    enum Section {
        PRICE_FACTOR("价格因素"),
        CAPABILITY("服务能力"),
        PROCESS("服务流程"),
        DELIVERABLE("交付物"),
        GUARANTEE("服务保障");

        private final String label;

        Section(String label) {
            this.label = label;
        }
    }

    // ---- 输入 ----

    public record CategoryDraft(String code, String name, String subtitle, String parentCode, Integer sortOrder) {
    }

    public record ItemDraft(String code, String title, String description, String iconKey) {
    }

    public record ServiceDraft(
            UUID providerId,
            String title,
            String summary,
            String categoryCode,
            String coverUrl,
            String priceMode,
            Long startingAmountMinor,
            String unitCode,
            String unitLabel,
            String priceNote,
            String cancellationNote,
            String availability,
            String primaryAction,
            Boolean includesReport,
            List<String> serviceMethods,
            List<String> cityCodes,
            List<ItemDraft> bookingItems,
            List<ItemDraft> priceFactors,
            List<ItemDraft> capabilities,
            List<ItemDraft> processSteps,
            List<ItemDraft> deliverables,
            List<ItemDraft> guarantees
    ) {
    }

    // ---- 对外的形状（契约 openapi-ops-inspection-catalog.yaml） ----

    /** ACTIVE 上架（App 能看到）；DISABLED 未上架或已下架。 */
    public enum ServiceStatus {
        ACTIVE,
        DISABLED
    }

    public enum CategoryStatus {
        ACTIVE,
        INACTIVE
    }

    public enum PriceMode {
        STARTING_PRICE,
        PER_DISTANCE,
        PER_VISIT,
        PROJECT_QUOTE
    }

    public enum Availability {
        AVAILABLE,
        RESERVABLE,
        UNAVAILABLE
    }

    public enum PrimaryAction {
        CONSULT,
        RESERVE,
        REQUEST_QUOTE,
        NONE
    }

    public enum ServiceMethod {
        UAV,
        ROBOT
    }

    public record CategoryView(String code, String name, String subtitle, String parentCode, int sortOrder,
                               CategoryStatus status, long activeServiceCount, int version,
                               OffsetDateTime updatedAt) {
    }

    public record ServiceRowView(UUID serviceId, String serviceNo, String title, String categoryCode,
                                 String categoryName, UUID providerId, String providerName, PriceMode priceMode,
                                 Long startingAmountMinor, ServiceStatus status, String coverUrl, int cityCount,
                                 OffsetDateTime updatedAt, int version) {
    }

    public record ServicePageView(int page, int size, long total, boolean hasMore, List<ServiceRowView> items) {
    }

    public record OpsCityView(String cityCode, String cityName) {
    }

    public record OpsItemView(String code, String title, String description, String iconKey) {
    }

    public record ServiceDetailView(
            UUID serviceId,
            String serviceNo,
            ServiceStatus status,
            int version,
            OffsetDateTime updatedAt,
            UUID providerId,
            String providerName,
            String title,
            String summary,
            String categoryCode,
            String categoryName,
            String coverUrl,
            PriceMode priceMode,
            Long startingAmountMinor,
            String unitCode,
            String unitLabel,
            String priceNote,
            String cancellationNote,
            Availability availability,
            PrimaryAction primaryAction,
            boolean includesReport,
            List<ServiceMethod> serviceMethods,
            List<OpsCityView> cities,
            List<OpsItemView> bookingItems,
            List<OpsItemView> priceFactors,
            List<OpsItemView> capabilities,
            List<OpsItemView> processSteps,
            List<OpsItemView> deliverables,
            List<OpsItemView> guarantees,
            long completedCount
    ) {
    }
}
