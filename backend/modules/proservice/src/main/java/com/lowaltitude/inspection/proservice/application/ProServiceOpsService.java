package com.lowaltitude.inspection.proservice.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.identity.domain.IdentityOrganization;
import com.lowaltitude.inspection.identity.domain.IdentityOrganizationPort;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.common.OpsText;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceOpsMapper;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceOpsMapper.DetailRow;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceOpsMapper.ItemRow;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceOpsMapper.ServiceFields;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运营端“专业服务目录”（S3-1，契约 contracts/openapi-ops-proservice.yaml 1.0.0）：航拍、测绘、设备维护、技术支持四类服务的
 * 新建（先是未上架）、修改、上架、下架和改动记录。只给平台运营（控制器检查角色）。
 * 上架前要有在用的服务商、至少一个上架城市、至少一个筛选项（成果类型或服务方式）和服务流程，缺了 422 带 details.missing；
 * 已上架的服务改完也要齐全。价格的组合（起步价要有金额、单位和一句说明）由表约束兜底，这里先按参数无效挡掉。
 * App 读取不变：目录、详情、服务商主页只给已上架、在这个城市上架的服务。
 */
public class ProServiceOpsService {

    public static final int MAX_PAGE_SIZE = 50;
    public static final int MAX_KEYWORD = 40;
    public static final int CHANGE_HISTORY_LIMIT = 50;
    public static final int MAX_TITLE = 40;
    public static final int MAX_SUMMARY = 80;
    public static final int MAX_PRICE_UNIT = 16;
    public static final int MAX_PRICE_NOTE = 40;
    public static final int MAX_LABEL = 40;
    public static final int MAX_DETAIL = 120;
    public static final int MAX_ITEMS = 10;
    public static final int MAX_CITIES = 300;
    public static final int MAX_SORT_ORDER = 9999;
    public static final long MAX_AMOUNT_MINOR = 10_000_000_000L;

    /** 上架前缺了会在 details.missing 里出现的键，按页面上的顺序。 */
    public static final List<String> PUBLISH_FIELDS = List.of("provider", "cities", "facets", "steps");

    private static final Pattern CITY_CODE = Pattern.compile("^[0-9]{6}$");

    private final ProServiceOpsMapper mapper;
    private final IdentityOrganizationPort organizations;
    private final ProServicePorts.CityDirectory cities;
    private final Clock clock;

    public ProServiceOpsService(ProServiceOpsMapper mapper, IdentityOrganizationPort organizations,
                                ProServicePorts.CityDirectory cities) {
        this(mapper, organizations, cities, Clock.systemUTC());
    }

    ProServiceOpsService(ProServiceOpsMapper mapper, IdentityOrganizationPort organizations,
                         ProServicePorts.CityDirectory cities, Clock clock) {
        this.mapper = mapper;
        this.organizations = organizations;
        this.cities = cities;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageView services(String keyword, String type, String cityCode, String status, int page, int size) {
        OpsText.page(page, size, MAX_PAGE_SIZE);
        String filterKeyword = OpsText.keyword(keyword, MAX_KEYWORD);
        ProServiceType filterType = OpsText.optionalEnum(ProServiceType.class, type, "type");
        String filterCity = cityCode == null || cityCode.isBlank() ? null : cityCode(cityCode);
        Status filterStatus = OpsText.optionalEnum(Status.class, status, "status");
        String typeName = filterType == null ? null : filterType.name();
        String statusName = filterStatus == null ? null : filterStatus.name();
        long total = mapper.countServices(filterKeyword, typeName, filterCity, statusName);
        var rows = mapper.findServices(filterKeyword, typeName, filterCity, statusName, size,
                Math.multiplyExact(page - 1, size));
        Map<UUID, String> names = new LinkedHashMap<>();
        rows.forEach(row -> names.computeIfAbsent(row.providerId(), id -> providerName(id).orElse(null)));
        List<RowView> items = rows.stream()
                .map(row -> new RowView(row.serviceId(), ProServiceType.valueOf(row.type()),
                        ProServiceCategory.valueOf(row.categoryCode()), row.title(), row.providerId(),
                        names.get(row.providerId()), ProServicePriceMode.valueOf(row.priceMode()),
                        row.priceAmountMinor(), Status.valueOf(row.status()), row.sample(), row.sortOrder(),
                        Math.toIntExact(row.cityCount()), row.updatedAt(), row.version()))
                .toList();
        return new PageView(page, size, total, (long) page * size < total, items);
    }

    @Transactional(readOnly = true)
    public DetailView service(UUID serviceId) {
        return detail(require(serviceId));
    }

    @Transactional
    public DetailView create(ServiceDraft draft, UUID operatorId, String operatorName) {
        String operator = OpsText.operatorName(operatorName);
        Normalized next = normalize(draft);
        UUID id = UUID.randomUUID();
        OffsetDateTime at = now();
        if (mapper.insertService(id, next.fields(), at) != 1) {
            throw new IllegalStateException("pro service was not created");
        }
        writeChildren(id, next, at);
        record(id, "CREATE", "新建" + next.type().label() + "服务“" + next.fields().title() + "”（未上架）", operatorId,
                operator, at);
        return detail(require(id));
    }

    @Transactional
    public DetailView update(UUID serviceId, ServiceDraft draft, Integer version, UUID operatorId,
                             String operatorName) {
        String operator = OpsText.operatorName(operatorName);
        int expected = version(version);
        DetailRow row = require(serviceId);
        if (row.version() != expected) {
            throw new OpsException(ProServiceOpsError.VERSION_CONFLICT);
        }
        DetailView current = detail(row);
        Normalized next = normalize(draft);
        if (Status.valueOf(row.status()) == Status.PUBLISHED) {
            requireComplete(true, next.cities().size(), next.facets().size(), next.lists().get("STEP").size());
        }
        List<String> changed = changedParts(current, next);
        if (changed.isEmpty()) {
            return current;
        }
        OffsetDateTime at = now();
        if (mapper.updateService(serviceId, next.fields(), expected, at) != 1) {
            throw new OpsException(ProServiceOpsError.VERSION_CONFLICT);
        }
        writeChildren(serviceId, next, at);
        record(serviceId, "UPDATE", "修改：" + String.join("、", changed), operatorId, operator, at);
        return detail(require(serviceId));
    }

    @Transactional
    public DetailView publish(UUID serviceId, Integer version, UUID operatorId, String operatorName) {
        return changeStatus(serviceId, version, Status.PUBLISHED, operatorId, operatorName);
    }

    @Transactional
    public DetailView offline(UUID serviceId, Integer version, UUID operatorId, String operatorName) {
        return changeStatus(serviceId, version, Status.UNPUBLISHED, operatorId, operatorName);
    }

    @Transactional(readOnly = true)
    public List<OpsChangeView> changes(UUID serviceId) {
        require(serviceId);
        return mapper.findChanges(serviceId, CHANGE_HISTORY_LIMIT).stream()
                .map(row -> new OpsChangeView(row.changeId(), "SERVICE", row.serviceId(), row.action(), row.summary(),
                        row.operatorName(), row.changedAt()))
                .toList();
    }

    private DetailView changeStatus(UUID serviceId, Integer version, Status target, UUID operatorId,
                                    String operatorName) {
        String operator = OpsText.operatorName(operatorName);
        int expected = version(version);
        DetailRow row = require(serviceId);
        if (row.version() != expected) {
            throw new OpsException(ProServiceOpsError.VERSION_CONFLICT);
        }
        if (Status.valueOf(row.status()) == target) {
            throw new OpsException(ProServiceOpsError.STATUS_UNCHANGED);
        }
        DetailView current = detail(row);
        if (target == Status.PUBLISHED) {
            boolean providerActive = organizations.findActiveOrganization(row.providerId())
                    .filter(found -> "PROVIDER".equals(found.organizationType())).isPresent();
            requireComplete(providerActive, current.cities().size(), current.facets().size(), current.steps().size());
        }
        OffsetDateTime at = now();
        if (mapper.updateStatus(serviceId, target.name(), expected, at) != 1) {
            throw new OpsException(ProServiceOpsError.VERSION_CONFLICT);
        }
        record(serviceId, target == Status.PUBLISHED ? "PUBLISH" : "OFFLINE",
                (target == Status.PUBLISHED ? "上架“" : "下架“") + current.title() + "”", operatorId, operator, at);
        return detail(require(serviceId));
    }

    static List<String> missing(boolean providerActive, int cityCount, int facetCount, int stepCount) {
        List<String> missing = new ArrayList<>();
        if (!providerActive) {
            missing.add("provider");
        }
        if (cityCount == 0) {
            missing.add("cities");
        }
        if (facetCount == 0) {
            missing.add("facets");
        }
        if (stepCount == 0) {
            missing.add("steps");
        }
        return missing;
    }

    private static void requireComplete(boolean providerActive, int cityCount, int facetCount, int stepCount) {
        List<String> missing = missing(providerActive, cityCount, facetCount, stepCount);
        if (!missing.isEmpty()) {
            throw OpsException.incomplete(ProServiceOpsError.PUBLISH_INCOMPLETE, missing);
        }
    }

    // ---- 整理输入 ----

    private Normalized normalize(ServiceDraft draft) {
        Objects.requireNonNull(draft, "draft");
        if (draft.providerId() == null) {
            throw new IllegalArgumentException("provider is required");
        }
        IdentityOrganization provider = organizations.findActiveOrganization(draft.providerId())
                .filter(found -> "PROVIDER".equals(found.organizationType()))
                .orElseThrow(() -> new OpsException(ProServiceOpsError.PROVIDER_UNKNOWN));
        ProServiceType type = OpsText.enumValue(ProServiceType.class, draft.type(), "type");
        ProServiceCategory category = OpsText.enumValue(ProServiceCategory.class, draft.categoryCode(),
                "categoryCode");
        String title = OpsText.required(draft.title(), MAX_TITLE, "title");
        String summary = OpsText.required(draft.summary(), MAX_SUMMARY, "summary");
        ProServicePriceMode mode = OpsText.enumValue(ProServicePriceMode.class, draft.priceMode(), "priceMode");
        Long amount = null;
        String unit = null;
        String note = OpsText.optional(draft.priceNote(), MAX_PRICE_NOTE, "priceNote");
        if (mode == ProServicePriceMode.PRICE_FROM) {
            amount = draft.priceAmountMinor();
            if (amount == null || amount <= 0 || amount > MAX_AMOUNT_MINOR) {
                throw new IllegalArgumentException("priceAmountMinor is invalid");
            }
            unit = OpsText.required(draft.priceUnit(), MAX_PRICE_UNIT, "priceUnit");
            if (note == null) {
                throw new IllegalArgumentException("priceNote is required");
            }
        }
        String coverUrl = OpsText.httpsUrl(draft.coverUrl(), "coverUrl");
        int sortOrder = draft.sortOrder() == null ? 0 : draft.sortOrder();
        if (sortOrder < 0 || sortOrder > MAX_SORT_ORDER) {
            throw new IllegalArgumentException("sortOrder is invalid");
        }
        List<String> cityCodes = cityCodes(draft.cityCodes());
        List<ProServiceFacet> facets = new ArrayList<>(new LinkedHashSet<>((draft.facets() == null
                ? List.<String>of() : draft.facets()).stream()
                .map(code -> OpsText.enumValue(ProServiceFacet.class, code, "facets")).toList()));
        if (!type.facets().containsAll(facets)) {
            throw new IllegalArgumentException("facet does not belong to the type");
        }
        Map<String, List<ItemRow>> lists = new LinkedHashMap<>();
        lists.put("TAG", labels("TAG", draft.tags()));
        lists.put("CAPABILITY", labels("CAPABILITY", draft.capabilities()));
        lists.put("PRICE_ROW", lines("PRICE_ROW", draft.priceRows()));
        lists.put("SCOPE", labels("SCOPE", draft.scopes()));
        lists.put("OUTPUT", lines("OUTPUT", draft.outputs()));
        lists.put("SERVICE_MODE", lines("SERVICE_MODE", draft.serviceModes()));
        lists.put("TERM", lines("TERM", draft.terms()));
        lists.put("STEP", labels("STEP", draft.steps()));
        lists.put("GUARANTEE", labels("GUARANTEE", draft.guarantees()));
        ServiceFields fields = new ServiceFields(provider.organizationId(), type.name(), category.name(), title, summary,
                mode.name(), amount, unit, note, coverUrl, sortOrder);
        return new Normalized(fields, type, cityCodes, facets, lists);
    }

    private List<String> cityCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        if (codes.size() > MAX_CITIES) {
            throw new IllegalArgumentException("too many cities");
        }
        List<String> distinct = new ArrayList<>(new LinkedHashSet<>(codes.stream()
                .map(ProServiceOpsService::cityCode).toList()));
        if (!distinct.isEmpty() && !cities.cityNames(distinct).keySet().containsAll(distinct)) {
            throw new OpsException(ProServiceOpsError.CITY_UNKNOWN);
        }
        return distinct;
    }

    private static List<ItemRow> labels(String kind, List<String> labels) {
        if (labels == null) {
            return List.of();
        }
        if (labels.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("too many " + kind);
        }
        return labels.stream().map(label -> new ItemRow(kind, null, OpsText.required(label, MAX_LABEL, kind), null))
                .toList();
    }

    private static List<ItemRow> lines(String kind, List<LineDraft> lines) {
        if (lines == null) {
            return List.of();
        }
        if (lines.size() > MAX_ITEMS) {
            throw new IllegalArgumentException("too many " + kind);
        }
        return lines.stream().map(line -> {
            if (line == null) {
                throw new IllegalArgumentException(kind + " is required");
            }
            return new ItemRow(kind, null, OpsText.required(line.label(), MAX_LABEL, kind),
                    OpsText.required(line.detail(), MAX_DETAIL, kind));
        }).toList();
    }

    private void writeChildren(UUID serviceId, Normalized next, OffsetDateTime at) {
        mapper.disableOtherCities(serviceId, next.cities(), at);
        for (String cityCode : next.cities()) {
            mapper.activateCity(serviceId, cityCode, at);
        }
        mapper.deleteItems(serviceId);
        int order = 0;
        for (ProServiceFacet facet : next.facets()) {
            mapper.insertItem(serviceId, new ItemRow("FACET", facet.name(), facet.label(), null), order += 10, at);
        }
        for (List<ItemRow> items : next.lists().values()) {
            int position = 0;
            for (ItemRow item : items) {
                mapper.insertItem(serviceId, item, position += 10, at);
            }
        }
    }

    // ---- 改了什么 ----

    private static List<String> changedParts(DetailView current, Normalized next) {
        ServiceFields fields = next.fields();
        List<String> changed = new ArrayList<>();
        if (!current.providerId().equals(fields.providerId())) {
            changed.add("服务商");
        }
        if (!current.type().name().equals(fields.type())) {
            changed.add("类型");
        }
        if (!current.categoryCode().name().equals(fields.categoryCode())) {
            changed.add("场景分类");
        }
        if (!current.title().equals(fields.title())) {
            changed.add("标题");
        }
        if (!current.summary().equals(fields.summary())) {
            changed.add("简介");
        }
        if (!current.priceMode().name().equals(fields.priceMode())
                || !Objects.equals(current.priceAmountMinor(), fields.priceAmountMinor())
                || !Objects.equals(current.priceUnit(), fields.priceUnit())
                || !Objects.equals(current.priceNote(), fields.priceNote())) {
            changed.add("价格");
        }
        if (!Objects.equals(current.coverUrl(), fields.coverUrl())) {
            changed.add("封面");
        }
        if (current.sortOrder() != fields.sortOrder()) {
            changed.add("排序");
        }
        if (!current.cities().stream().map(CityView::cityCode).toList().equals(next.cities())) {
            changed.add("上架城市（" + next.cities().size() + " 个）");
        }
        if (!current.facets().equals(next.facets())) {
            changed.add("筛选项");
        }
        Map<String, List<String>> currentLabels = Map.of("TAG", current.tags(), "CAPABILITY",
                current.capabilities(), "SCOPE", current.scopes(), "STEP", current.steps(), "GUARANTEE",
                current.guarantees());
        Map<String, List<LineView>> currentLines = Map.of("PRICE_ROW", current.priceRows(), "OUTPUT",
                current.outputs(), "SERVICE_MODE", current.serviceModes(), "TERM", current.terms());
        Map<String, String> names = Map.of("TAG", "卡片标签", "CAPABILITY", "能力", "PRICE_ROW", "价格与取消",
                "SCOPE", "服务内容", "OUTPUT", "成果格式", "SERVICE_MODE", "服务方式", "TERM", "说明行",
                "STEP", "服务流程", "GUARANTEE", "服务保障");
        for (Map.Entry<String, List<ItemRow>> list : next.lists().entrySet()) {
            String kind = list.getKey();
            boolean same = currentLabels.containsKey(kind)
                    ? currentLabels.get(kind).equals(list.getValue().stream().map(ItemRow::label).toList())
                    : currentLines.get(kind).equals(list.getValue().stream()
                            .map(item -> new LineView(item.label(), item.detail())).toList());
            if (!same) {
                changed.add(names.get(kind));
            }
        }
        return changed;
    }

    // ---- 读出来给页面 ----

    private DetailView detail(DetailRow row) {
        Map<String, List<ItemRow>> items = new LinkedHashMap<>();
        mapper.findItems(row.serviceId()).forEach(item -> items.computeIfAbsent(item.kind(), kind -> new ArrayList<>())
                .add(item));
        List<String> cityCodes = mapper.findCities(row.serviceId());
        Map<String, String> names = cityCodes.isEmpty() ? Map.of() : cities.cityNames(cityCodes);
        return new DetailView(row.serviceId(), Status.valueOf(row.status()), row.version(), row.updatedAt(),
                row.sample(), row.providerId(), providerName(row.providerId()).orElse(null),
                ProServiceType.valueOf(row.type()), ProServiceCategory.valueOf(row.categoryCode()), row.title(),
                row.summary(), ProServicePriceMode.valueOf(row.priceMode()), row.priceAmountMinor(), row.priceUnit(),
                row.priceNote(), row.coverUrl(), row.sortOrder(),
                cityCodes.stream().map(code -> new CityView(code, names.get(code))).toList(),
                labelsOf(items, "TAG"),
                items.getOrDefault("FACET", List.of()).stream().map(item -> ProServiceFacet.valueOf(item.code()))
                        .toList(),
                labelsOf(items, "CAPABILITY"), linesOf(items, "PRICE_ROW"), labelsOf(items, "SCOPE"),
                linesOf(items, "OUTPUT"), linesOf(items, "SERVICE_MODE"), linesOf(items, "TERM"),
                labelsOf(items, "STEP"), labelsOf(items, "GUARANTEE"));
    }

    private static List<String> labelsOf(Map<String, List<ItemRow>> items, String kind) {
        return items.getOrDefault(kind, List.of()).stream().map(ItemRow::label).toList();
    }

    private static List<LineView> linesOf(Map<String, List<ItemRow>> items, String kind) {
        return items.getOrDefault(kind, List.of()).stream().map(item -> new LineView(item.label(), item.detail()))
                .toList();
    }

    private Optional<String> providerName(UUID providerId) {
        return organizations.findActiveOrganization(providerId).map(IdentityOrganization::name);
    }

    private void record(UUID serviceId, String action, String summary, UUID operatorId, String operatorName,
                        OffsetDateTime at) {
        if (operatorId == null) {
            throw new IllegalArgumentException("operator is required");
        }
        if (mapper.insertChange(serviceId, action, OpsText.clip(summary, 300), operatorId, operatorName, at) != 1) {
            throw new IllegalStateException("pro service change was not recorded");
        }
    }

    private DetailRow require(UUID serviceId) {
        DetailRow row = serviceId == null ? null : mapper.findService(serviceId);
        if (row == null) {
            throw new OpsException(ProServiceOpsError.NOT_FOUND);
        }
        return row;
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static String cityCode(String code) {
        if (code == null || !CITY_CODE.matcher(code.strip()).matches()) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return code.strip();
    }

    private static int version(Integer version) {
        if (version == null || version < 0) {
            throw new IllegalArgumentException("version is required");
        }
        return version;
    }

    private record Normalized(ServiceFields fields, ProServiceType type, List<String> cities,
                              List<ProServiceFacet> facets, Map<String, List<ItemRow>> lists) {
    }

    // ---- 输入 ----

    public record LineDraft(String label, String detail) {
    }

    public record ServiceDraft(
            UUID providerId,
            String type,
            String categoryCode,
            String title,
            String summary,
            String priceMode,
            Long priceAmountMinor,
            String priceUnit,
            String priceNote,
            String coverUrl,
            Integer sortOrder,
            List<String> cityCodes,
            List<String> tags,
            List<String> facets,
            List<String> capabilities,
            List<LineDraft> priceRows,
            List<String> scopes,
            List<LineDraft> outputs,
            List<LineDraft> serviceModes,
            List<LineDraft> terms,
            List<String> steps,
            List<String> guarantees
    ) {
    }

    // ---- 对外的形状（契约 openapi-ops-proservice.yaml） ----

    /** PUBLISHED 已上架（App 能看到）；UNPUBLISHED 未上架或已下架。 */
    public enum Status {
        PUBLISHED,
        UNPUBLISHED
    }

    public record RowView(UUID serviceId, ProServiceType type, ProServiceCategory categoryCode, String title,
                          UUID providerId, String providerName, ProServicePriceMode priceMode, Long priceAmountMinor,
                          Status status, boolean sample, int sortOrder, int cityCount, OffsetDateTime updatedAt,
                          int version) {
    }

    public record PageView(int page, int size, long total, boolean hasMore, List<RowView> items) {
    }

    public record CityView(String cityCode, String cityName) {
    }

    public record LineView(String label, String detail) {
    }

    public record DetailView(
            UUID serviceId,
            Status status,
            int version,
            OffsetDateTime updatedAt,
            boolean sample,
            UUID providerId,
            String providerName,
            ProServiceType type,
            ProServiceCategory categoryCode,
            String title,
            String summary,
            ProServicePriceMode priceMode,
            Long priceAmountMinor,
            String priceUnit,
            String priceNote,
            String coverUrl,
            int sortOrder,
            List<CityView> cities,
            List<String> tags,
            List<ProServiceFacet> facets,
            List<String> capabilities,
            List<LineView> priceRows,
            List<String> scopes,
            List<LineView> outputs,
            List<LineView> serviceModes,
            List<LineView> terms,
            List<String> steps,
            List<String> guarantees
    ) {
    }
}
