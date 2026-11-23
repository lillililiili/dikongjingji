package com.lowaltitude.inspection.proservice.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.lowaltitude.inspection.proservice.application.ProServicePorts.CityDirectory;
import com.lowaltitude.inspection.proservice.application.ProServicePorts.InspectionOffers;
import com.lowaltitude.inspection.proservice.application.ProServicePorts.InspectionService;
import com.lowaltitude.inspection.proservice.application.ProServicePorts.Provider;
import com.lowaltitude.inspection.proservice.application.ProServicePorts.ProviderProfiles;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceMapper;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceMapper.ItemRow;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceMapper.ServiceRow;
import org.springframework.transaction.annotation.Transactional;

/**
 * 四类专业服务的目录、详情和服务商主页（F2，契约 contracts/openapi-proservice.yaml 1.0.0，规则见
 * docs/redesign/03-App实现任务计划.md §23.2）。全部公开只读。
 *
 * <ul>
 *   <li>服务按城市上架；这个城市没有这一类服务时 cityOpen 为 false。</li>
 *   <li>价格两种：起步价（金额 + 单位 + 起步价含什么）和按项目报价（“方案确认后定价”）；价格排序里按项目报价的排在最后。</li>
 *   <li>没有授权照片时 coverUrl 为 null，App 按场景分类画图标。</li>
 *   <li>服务商资料、巡检服务通过端口读巡检模块；服务商主页只给有在用服务商资料的服务商。</li>
 * </ul>
 */
public class ProServiceReadService {

    static final int MAX_PAGE_SIZE = 50;
    static final int MAX_KEYWORD_LENGTH = 40;
    static final String PROJECT_PRICE_NOTE = "方案确认后定价";
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";
    private static final List<String> CARD_KINDS = List.of("TAG");
    private static final List<String> DETAIL_KINDS = List.of(
            "CAPABILITY", "PRICE_ROW", "SCOPE", "OUTPUT", "SERVICE_MODE", "TERM", "STEP", "GUARANTEE");

    private final ProServiceMapper mapper;
    private final CityDirectory cities;
    private final ProviderProfiles profiles;
    private final InspectionOffers inspection;
    /** 服务商主页资料（S4-4）：只给平台审核通过的那一份。 */
    private final java.util.function.Function<UUID, Optional<ProviderProfileService.ProfileView>> publishedProfiles;

    ProServiceReadService(
            ProServiceMapper mapper,
            CityDirectory cities,
            ProviderProfiles profiles,
            InspectionOffers inspection
    ) {
        this(mapper, cities, profiles, inspection, providerId -> Optional.empty());
    }

    ProServiceReadService(
            ProServiceMapper mapper,
            CityDirectory cities,
            ProviderProfiles profiles,
            InspectionOffers inspection,
            java.util.function.Function<UUID, Optional<ProviderProfileService.ProfileView>> publishedProfiles
    ) {
        this.mapper = mapper;
        this.cities = cities;
        this.profiles = profiles;
        this.inspection = inspection;
        this.publishedProfiles = publishedProfiles;
    }

    /**
     * 目录。type、cityCode 必填；facets 是筛选项编码（有任意一项即可）；priceMode、sort 可以不填。
     * 城市不在平台城市目录里时和没开通一样：空列表、cityOpen 为 false。
     */
    @Transactional(readOnly = true)
    public ProServicePageView list(
            String typeCode,
            String cityCode,
            String keyword,
            List<String> facetCodes,
            String priceModeCode,
            String sortCode,
            int page,
            int size
    ) {
        ProServiceType type = parse(typeCode, ProServiceType::valueOf, "type")
                .orElseThrow(() -> new IllegalArgumentException("type is required"));
        String city = requireCity(cityCode);
        String pattern = keywordPattern(keyword);
        List<String> facets = facets(facetCodes);
        ProServicePriceMode priceMode = parse(priceModeCode, ProServicePriceMode::valueOf, "price mode").orElse(null);
        ProServiceSort sort = parse(sortCode, ProServiceSort::valueOf, "sort").orElse(ProServiceSort.DEFAULT);
        if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page is invalid");
        }
        ProServiceFacetGroupView facetGroup = new ProServiceFacetGroupView(type.facetLabel(), type.facets().stream()
                .map(facet -> new ProServiceFacetView(facet, facet.label()))
                .toList());
        Optional<String> cityName = cities.cityName(city);
        boolean open = cityName.isPresent() && mapper.countOfferedInCity(city, type.name()) > 0;
        if (!open) {
            return new ProServicePageView(type, city, cityName.orElse(null), false, facetGroup, sort, page, size, 0,
                    false, List.of());
        }
        String mode = priceMode == null ? null : priceMode.name();
        long total = mapper.countCatalog(city, type.name(), pattern, facets, mode);
        List<ServiceRow> rows = total == 0
                ? List.of()
                : mapper.findCatalog(city, type.name(), pattern, facets, mode, sort.name(), size, (page - 1) * size);
        return new ProServicePageView(type, city, cityName.get(), true, facetGroup, sort, page, size, total,
                (long) page * size < total, cards(rows));
    }

    /** 服务详情：要在这个城市上架；不在时和不存在一样是 404。 */
    @Transactional(readOnly = true)
    public ProServiceDetailView detail(UUID serviceId, String cityCode) {
        String city = requireCity(cityCode);
        if (serviceId == null) {
            throw new IllegalArgumentException("service id is required");
        }
        ServiceRow row = mapper.findVisible(serviceId, city);
        if (row == null) {
            throw new ProServiceNotFoundException();
        }
        Map<String, List<ItemRow>> items = mapper.findItems(List.of(row.id()), DETAIL_KINDS).stream()
                .collect(Collectors.groupingBy(ItemRow::kind, LinkedHashMap::new, Collectors.toList()));
        ProServiceProviderView provider = profiles.find(row.providerId())
                .map(found -> new ProServiceProviderView(found.providerId(), found.name(), found.certificationLabel(),
                        found.description(), found.qualificationReviewed()))
                .orElse(null);
        return new ProServiceDetailView(
                row.id(),
                ProServiceType.valueOf(row.type()),
                category(row),
                row.title(),
                row.summary(),
                price(row),
                row.coverUrl(),
                row.sample(),
                labels(items, "CAPABILITY"),
                lines(items, "PRICE_ROW"),
                labels(items, "SCOPE"),
                lines(items, "OUTPUT"),
                lines(items, "SERVICE_MODE"),
                lines(items, "TERM"),
                labels(items, "STEP"),
                labels(items, "GUARANTEE"),
                provider,
                cityViews(mapper.findCities(row.id())),
                mapper.findWarranty(row.id()));
    }

    /**
     * 服务商主页：在 cityCode 这个城市能看到的巡检服务和四类服务；cities 是它的巡检服务和四类服务覆盖的全部城市。
     * 服务商不存在或没有在用的服务商资料时 404。
     */
    @Transactional(readOnly = true)
    public ProviderPageView provider(UUID providerId, String cityCode) {
        String city = requireCity(cityCode);
        if (providerId == null) {
            throw new IllegalArgumentException("provider id is required");
        }
        Provider provider = profiles.find(providerId).orElseThrow(ProServiceNotFoundException::new);
        List<ProviderInspectionServiceView> inspectionServices = inspection.servicesOf(providerId, city).stream()
                .map(ProServiceReadService::inspectionView)
                .toList();
        Set<String> served = new TreeSet<>(inspection.citiesServed(providerId));
        served.addAll(mapper.findProviderCities(providerId));
        return new ProviderPageView(
                provider.providerId(),
                provider.name(),
                provider.certificationLabel(),
                provider.qualificationReviewed(),
                provider.description(),
                city,
                inspectionServices,
                cards(mapper.findProviderServices(providerId, city)),
                cityViews(served),
                publishedProfiles.apply(providerId).orElse(null));
    }

    /** 在这个城市有已上架四类服务的服务商（需求的平台匹配和邀请用）。 */
    @Transactional(readOnly = true)
    public List<UUID> providerIdsIn(String cityCode) {
        if (cityCode == null || cityCode.isBlank()) {
            return List.of();
        }
        return mapper.findProviderIdsIn(cityCode.trim());
    }

    /** 这家服务商已上架的四类服务覆盖的城市。 */
    @Transactional(readOnly = true)
    public Set<String> citiesOf(UUID providerId) {
        if (providerId == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(mapper.findProviderCities(providerId));
    }

    // ---- 转换 ----

    private List<ProServiceCardView> cards(List<ServiceRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<String>> tags = mapper.findItems(rows.stream().map(ServiceRow::id).toList(), CARD_KINDS)
                .stream()
                .collect(Collectors.groupingBy(ItemRow::serviceId, Collectors.mapping(ItemRow::label,
                        Collectors.toList())));
        return rows.stream()
                .map(row -> new ProServiceCardView(
                        row.id(),
                        ProServiceType.valueOf(row.type()),
                        category(row),
                        row.title(),
                        row.summary(),
                        tags.getOrDefault(row.id(), List.of()),
                        price(row),
                        row.coverUrl(),
                        row.sample()))
                .toList();
    }

    private static ProServiceCategoryView category(ServiceRow row) {
        ProServiceCategory category = ProServiceCategory.valueOf(row.categoryCode());
        return new ProServiceCategoryView(category, category.label());
    }

    private static ProServicePriceView price(ServiceRow row) {
        ProServicePriceMode mode = ProServicePriceMode.valueOf(row.priceMode());
        if (mode == ProServicePriceMode.PROJECT) {
            return new ProServicePriceView(mode, null, null,
                    row.priceNote() == null ? PROJECT_PRICE_NOTE : row.priceNote());
        }
        return new ProServicePriceView(mode, row.priceAmountMinor(), row.priceUnit(), row.priceNote());
    }

    private static ProviderInspectionServiceView inspectionView(InspectionService service) {
        ProServicePriceView price = service.projectPrice() || service.amountMinor() == null
                ? new ProServicePriceView(ProServicePriceMode.PROJECT, null, null,
                        service.note() == null ? PROJECT_PRICE_NOTE : service.note())
                : new ProServicePriceView(ProServicePriceMode.PRICE_FROM, service.amountMinor(), service.unit(),
                        service.note());
        return new ProviderInspectionServiceView(service.serviceId(), service.title(), service.summary(),
                service.categoryName(), price);
    }

    private static List<String> labels(Map<String, List<ItemRow>> items, String kind) {
        return items.getOrDefault(kind, List.of()).stream().map(ItemRow::label).toList();
    }

    private static List<ProServiceLineView> lines(Map<String, List<ItemRow>> items, String kind) {
        return items.getOrDefault(kind, List.of()).stream()
                .map(item -> new ProServiceLineView(item.label(), item.detail()))
                .toList();
    }

    /** 城市按编码排；平台城市目录里没有的不显示。 */
    private List<ProServiceCityView> cityViews(Iterable<String> codes) {
        List<ProServiceCityView> views = new ArrayList<>();
        for (String code : codes) {
            cities.cityName(code).ifPresent(name -> views.add(new ProServiceCityView(code, name)));
        }
        return views;
    }

    // ---- 校验 ----

    private static String requireCity(String cityCode) {
        String city = cityCode == null ? "" : cityCode.trim();
        if (!city.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return city;
    }

    /** 关键词去掉首尾空白，转成 ILIKE 的包含匹配（% 和 _ 按字面）；空的当作没填。 */
    static String keywordPattern(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("keyword is invalid");
        }
        return "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** 筛选项可以重复传，也可以逗号分隔；去重，按枚举（契约）的顺序。 */
    private static List<String> facets(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        Set<ProServiceFacet> facets = new TreeSet<>();
        for (String value : codes) {
            if (value == null) {
                continue;
            }
            for (String code : value.split(",")) {
                parse(code, ProServiceFacet::valueOf, "facet").ifPresent(facets::add);
            }
        }
        return facets.stream().map(Enum::name).toList();
    }

    private static <E> Optional<E> parse(String code, Function<String, E> parser, String what) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(parser.apply(code.trim()));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(what + " is invalid");
        }
    }
}
