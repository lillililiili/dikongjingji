package com.lowaltitude.inspection.proservice.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.proservice.application.ProServicePorts.InspectionService;
import com.lowaltitude.inspection.proservice.application.ProServicePorts.Provider;
import com.lowaltitude.inspection.proservice.infrastructure.persistence.ProServiceMapper;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/**
 * 四类专业服务的读取规则（F2，§23.2）。mapper 换成内存实现（按 SQL 的条件写），端口换成替身。SQL 文本由
 * ProServiceMapperContractTest 检查。
 */
class ProServiceReadServiceTest {

    private static final String DONGYING = "370500";
    private static final String JINAN = "370100";
    private static final String WEIFANG = "370700";
    private static final UUID YUNYI = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID EAGLE = UUID.fromString("f2000000-0000-4000-8000-000000000001");
    private static final UUID NO_PROFILE = UUID.fromString("f2000000-0000-4000-8000-0000000000ff");
    private static final UUID INSPECTION = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final InMemoryMapper mapper = new InMemoryMapper();
    private final ProServiceReadService service = new ProServiceReadService(
            mapper,
            code -> Optional.ofNullable(Map.of(DONGYING, "东营市", JINAN, "济南市", WEIFANG, "潍坊市").get(code)),
            providerId -> Optional.ofNullable(Map.of(
                    YUNYI, new Provider(YUNYI, "云翼科技", "企业认证", "专业飞手团队"),
                    EAGLE, new Provider(EAGLE, "鹰眼智能巡检", "资质已审核", "东营本地团队")).get(providerId)),
            new ProServicePorts.InspectionOffers() {
                @Override
                public List<InspectionService> servicesOf(UUID providerId, String cityCode) {
                    return providerId.equals(YUNYI) && cityCode.equals(DONGYING)
                            ? List.of(
                                    new InspectionService(INSPECTION, "电力输电线路无人机巡检", "高精度巡检", "电力", false,
                                            69900L, "/公里起", "含现场巡检和报告"),
                                    new InspectionService(UUID.randomUUID(), "园区安防巡检", "周界巡查", "园区", true,
                                            null, null, null))
                            : List.of();
                }

                @Override
                public Set<String> citiesServed(UUID providerId) {
                    return providerId.equals(YUNYI) ? Set.of(DONGYING, "999999") : Set.of();
                }
            });

    private final UUID park = mapper.add(YUNYI, "AERIAL", "ENGINEERING", "园区与工程航拍", "园区全景 · 工程进度",
            128000L, "/次起", "含拍摄和素材整理", 10, List.of(DONGYING, JINAN),
            List.of("视频素材", "照片", "持证飞手"), List.of("VIDEO_FOOTAGE", "PHOTOS"));
    private final UUID energy = mapper.add(EAGLE, "AERIAL", "ENERGY", "能源设施航拍", "光伏场站 · 厂区全景",
            null, null, null, 20, List.of(DONGYING), List.of("视频素材", "照片"), List.of("VIDEO_FOOTAGE", "PHOTOS"));
    private final UUID scenic = mapper.add(EAGLE, "AERIAL", "TOURISM", "文旅风光航拍", "城市风光 · 景区宣传",
            168000L, "/次起", "含拍摄和视频素材", 30, List.of(DONGYING, JINAN), List.of("视频素材"),
            List.of("VIDEO_FOOTAGE"));
    private final UUID event = mapper.add(EAGLE, "AERIAL", "EVENT", "活动现场航拍", "赛事记录 · 开幕现场",
            98000L, "/场起", "含单场拍摄和素材整理", 40, List.of(DONGYING), List.of("视频素材", "照片"),
            List.of("VIDEO_FOOTAGE", "PHOTOS"));
    private final UUID survey = mapper.add(YUNYI, "SURVEY", "MODELING", "无人机航测与三维建模", "地形测绘 · 实景建模",
            null, null, null, 50, List.of(DONGYING), List.of("正射影像", "三维模型"), List.of("ORTHOPHOTO", "MODEL_3D"));
    private final UUID hidden = mapper.add(EAGLE, "AERIAL", "EVENT", "已下架的航拍", "不应出现", 100L, "/次起", "无",
            5, List.of(DONGYING), List.of(), List.of());
    private final UUID orphan = mapper.add(NO_PROFILE, "TECH_SUPPORT", "SOFTWARE", "软件配置与数据处理", "软件配置",
            50000L, "/次起", "含远程配置", 60, List.of(DONGYING), List.of("远程"), List.of("REMOTE"));

    {
        mapper.unpublish(hidden);
        mapper.disableCity(scenic, JINAN);
        mapper.detail(park, "CAPABILITY", "视频素材", null, 0);
        mapper.detail(park, "CAPABILITY", "持证飞手", null, 10);
        mapper.detail(park, "PRICE_ROW", "取消说明", "方案确认前取消不收费，确认后按方案约定", 20);
        mapper.detail(park, "PRICE_ROW", "计价方式", "按次计价，真实金额以方案报价为准", 0);
        mapper.detail(park, "SCOPE", "园区全景", null, 0);
        mapper.detail(park, "OUTPUT", "视频素材", "原始素材，分辨率和时长按方案约定", 0);
        mapper.detail(park, "STEP", "提交需求", null, 0);
        mapper.detail(park, "STEP", "报价比选", null, 10);
        mapper.detail(park, "GUARANTEE", "持证飞手", null, 0);
    }

    // ---- 目录 ----

    @Test
    void theCatalogListsTheTypeInTheCityInTheDefaultOrder() {
        ProServicePageView page = service.list("AERIAL", DONGYING, null, null, null, null, 1, 20);

        assertThat(page.cityOpen()).isTrue();
        assertThat(page.cityName()).isEqualTo("东营市");
        assertThat(page.sort()).isEqualTo(ProServiceSort.DEFAULT);
        assertThat(page.items()).extracting(ProServiceCardView::serviceId).containsExactly(park, energy, scenic, event);
        assertThat(page.total()).isEqualTo(4);
        assertThat(page.facetGroup()).isEqualTo(new ProServiceFacetGroupView("成果类型", List.of(
                new ProServiceFacetView(ProServiceFacet.VIDEO_FOOTAGE, "视频素材"),
                new ProServiceFacetView(ProServiceFacet.PHOTOS, "照片"))));
        ProServiceCardView first = page.items().getFirst();
        assertThat(first.category()).isEqualTo(new ProServiceCategoryView(ProServiceCategory.ENGINEERING, "工程"));
        assertThat(first.tags()).containsExactly("视频素材", "照片", "持证飞手");
        assertThat(first.price()).isEqualTo(new ProServicePriceView(ProServicePriceMode.PRICE_FROM, 128000L, "/次起",
                "含拍摄和素材整理"));
        assertThat(first.coverUrl()).isNull();
        assertThat(first.sample()).isTrue();
        // 按项目报价：没有金额和单位，说明是“方案确认后定价”。
        assertThat(page.items().get(1).price()).isEqualTo(new ProServicePriceView(ProServicePriceMode.PROJECT, null,
                null, "方案确认后定价"));
    }

    @Test
    void servicesAreListedPerCity() {
        assertThat(service.list("AERIAL", JINAN, null, null, null, null, 1, 20).items())
                .extracting(ProServiceCardView::serviceId).containsExactly(park);
        assertThat(service.list("SURVEY", JINAN, null, null, null, null, 1, 20))
                .satisfies(page -> {
                    assertThat(page.cityOpen()).isFalse();
                    assertThat(page.cityName()).isEqualTo("济南市");
                    assertThat(page.items()).isEmpty();
                });
        ProServicePageView weifang = service.list("AERIAL", WEIFANG, null, null, null, null, 1, 20);
        assertThat(weifang.cityOpen()).isFalse();
        // 不在平台城市目录里的城市：和没开通一样，不查库。
        int calls = mapper.catalogCalls;
        ProServicePageView unknown = service.list("AERIAL", "999999", null, null, null, null, 1, 20);
        assertThat(unknown.cityOpen()).isFalse();
        assertThat(unknown.cityName()).isNull();
        assertThat(mapper.catalogCalls).isEqualTo(calls);
    }

    // 城市开通了、但关键词或筛选没有结果：cityOpen 仍为 true（页面显示“没有找到”而不是“未开通”）。
    @Test
    void keywordAndFiltersNarrowTheListButTheCityStaysOpen() {
        assertThat(service.list("AERIAL", DONGYING, " 光伏 ", null, null, null, 1, 20).items())
                .extracting(ProServiceCardView::serviceId).containsExactly(energy);
        assertThat(service.list("AERIAL", DONGYING, "持证", null, null, null, 1, 20).items())
                .extracting(ProServiceCardView::serviceId).containsExactly(park);
        assertThat(service.list("AERIAL", DONGYING, null, List.of("PHOTOS"), null, null, 1, 20).items())
                .extracting(ProServiceCardView::serviceId).containsExactly(park, energy, event);
        assertThat(service.list("AERIAL", DONGYING, null, List.of("PHOTOS,VIDEO_FOOTAGE"), "PRICE_FROM", null, 1, 20)
                .items()).extracting(ProServiceCardView::serviceId).containsExactly(park, scenic, event);
        assertThat(mapper.lastFacets).containsExactly("VIDEO_FOOTAGE", "PHOTOS");
        assertThat(service.list("AERIAL", DONGYING, null, null, "PROJECT", null, 1, 20).items())
                .extracting(ProServiceCardView::serviceId).containsExactly(energy);
        ProServicePageView none = service.list("AERIAL", DONGYING, "风电", null, null, null, 1, 20);
        assertThat(none.cityOpen()).isTrue();
        assertThat(none.items()).isEmpty();
        // % 和 _ 按字面匹配。
        assertThat(service.list("AERIAL", DONGYING, "100%", null, null, null, 1, 20).items()).isEmpty();
        assertThat(ProServiceReadService.keywordPattern("a_b%c\\")).isEqualTo("%a\\_b\\%c\\\\%");
    }

    @Test
    void priceSortsPutProjectPricingLast() {
        assertThat(service.list("AERIAL", DONGYING, null, null, null, "PRICE_ASC", 1, 20).items())
                .extracting(ProServiceCardView::serviceId).containsExactly(event, park, scenic, energy);
        assertThat(service.list("AERIAL", DONGYING, null, null, null, "PRICE_DESC", 1, 20).items())
                .extracting(ProServiceCardView::serviceId).containsExactly(scenic, park, event, energy);
    }

    @Test
    void pagesAreCountedAndCut() {
        ProServicePageView first = service.list("AERIAL", DONGYING, null, null, null, null, 1, 3);
        ProServicePageView second = service.list("AERIAL", DONGYING, null, null, null, null, 2, 3);

        assertThat(first.items()).hasSize(3);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.items()).extracting(ProServiceCardView::serviceId).containsExactly(event);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void invalidParametersAreRejected() {
        assertInvalid(() -> service.list(null, DONGYING, null, null, null, null, 1, 20));
        assertInvalid(() -> service.list("INSPECTION", DONGYING, null, null, null, null, 1, 20));
        assertInvalid(() -> service.list("AERIAL", null, null, null, null, null, 1, 20));
        assertInvalid(() -> service.list("AERIAL", "dongying", null, null, null, null, 1, 20));
        assertInvalid(() -> service.list("AERIAL", DONGYING, "关".repeat(41), null, null, null, 1, 20));
        assertInvalid(() -> service.list("AERIAL", DONGYING, "a\tb", null, null, null, 1, 20));
        assertInvalid(() -> service.list("AERIAL", DONGYING, null, List.of("DRONE"), null, null, 1, 20));
        assertInvalid(() -> service.list("AERIAL", DONGYING, null, null, "FREE", null, 1, 20));
        assertInvalid(() -> service.list("AERIAL", DONGYING, null, null, null, "DISTANCE", 1, 20));
        assertInvalid(() -> service.list("AERIAL", DONGYING, null, null, null, null, 0, 20));
        assertInvalid(() -> service.list("AERIAL", DONGYING, null, null, null, null, 1, 51));
    }

    // ---- 详情 ----

    @Test
    void theDetailGroupsItsItemsAndNamesTheProviderAndCities() {
        ProServiceDetailView detail = service.detail(park, DONGYING);

        assertThat(detail.title()).isEqualTo("园区与工程航拍");
        assertThat(detail.type()).isEqualTo(ProServiceType.AERIAL);
        assertThat(detail.capabilities()).containsExactly("视频素材", "持证飞手");
        assertThat(detail.priceRows()).containsExactly(
                new ProServiceLineView("计价方式", "按次计价，真实金额以方案报价为准"),
                new ProServiceLineView("取消说明", "方案确认前取消不收费，确认后按方案约定"));
        assertThat(detail.scope()).containsExactly("园区全景");
        assertThat(detail.outputs()).containsExactly(new ProServiceLineView("视频素材", "原始素材，分辨率和时长按方案约定"));
        assertThat(detail.serviceModes()).isEmpty();
        assertThat(detail.terms()).isEmpty();
        assertThat(detail.steps()).containsExactly("提交需求", "报价比选");
        assertThat(detail.guarantees()).containsExactly("持证飞手");
        assertThat(detail.provider()).isEqualTo(new ProServiceProviderView(YUNYI, "云翼科技", "企业认证", "专业飞手团队"));
        assertThat(detail.cities()).containsExactly(
                new ProServiceCityView(JINAN, "济南市"), new ProServiceCityView(DONGYING, "东营市"));
        // 停用的城市不算上架城市。
        assertThat(service.detail(scenic, DONGYING).cities()).extracting(ProServiceCityView::cityCode)
                .containsExactly(DONGYING);
    }

    @Test
    void aServiceOutsideTheCityOrUnpublishedIsNotFound() {
        assertNotFound(() -> service.detail(event, JINAN));
        assertNotFound(() -> service.detail(scenic, JINAN));
        assertNotFound(() -> service.detail(hidden, DONGYING));
        assertNotFound(() -> service.detail(UUID.randomUUID(), DONGYING));
        assertInvalid(() -> service.detail(park, null));
        // 服务商资料停用：服务还能看，没有服务商行。
        assertThat(service.detail(orphan, DONGYING).provider()).isNull();
    }

    // ---- 服务商主页 ----

    @Test
    void theProviderPageShowsItsInspectionAndProServicesInTheCityAndAllItsCities() {
        ProviderPageView page = service.provider(YUNYI, DONGYING);

        assertThat(page.name()).isEqualTo("云翼科技");
        assertThat(page.certificationLabel()).isEqualTo("企业认证");
        assertThat(page.cityCode()).isEqualTo(DONGYING);
        assertThat(page.inspectionServices()).extracting(
                        ProviderInspectionServiceView::title, view -> view.price().mode(), view -> view.price().note())
                .containsExactly(
                        tuple("电力输电线路无人机巡检", ProServicePriceMode.PRICE_FROM, "含现场巡检和报告"),
                        tuple("园区安防巡检", ProServicePriceMode.PROJECT, "方案确认后定价"));
        assertThat(page.inspectionServices().getFirst().price().unit()).isEqualTo("/公里起");
        // 先按类型，再按综合顺序。
        assertThat(page.proServices()).extracting(ProServiceCardView::serviceId).containsExactly(park, survey);
        // 巡检的城市和四类服务的城市合在一起，按编码排；目录里没有的城市不显示。
        assertThat(page.cities()).containsExactly(
                new ProServiceCityView(JINAN, "济南市"), new ProServiceCityView(DONGYING, "东营市"));

        ProviderPageView inJinan = service.provider(EAGLE, JINAN);
        assertThat(inJinan.inspectionServices()).isEmpty();
        assertThat(inJinan.proServices()).isEmpty();
        assertThat(inJinan.cities()).extracting(ProServiceCityView::cityCode).containsExactly(DONGYING);
    }

    @Test
    void aProviderWithoutAnActiveProfileIsNotFound() {
        assertNotFound(() -> service.provider(NO_PROFILE, DONGYING));
        assertNotFound(() -> service.provider(UUID.randomUUID(), DONGYING));
        assertInvalid(() -> service.provider(YUNYI, " "));
    }

    // 需求（F1）的服务商名录用：在这个城市有已上架服务的服务商，和服务商的服务城市。
    @Test
    void providersAndCitiesForTheDemandDirectory() {
        assertThat(service.providerIdsIn(JINAN)).containsExactly(YUNYI);
        assertThat(service.providerIdsIn(DONGYING)).containsExactlyInAnyOrder(YUNYI, EAGLE, NO_PROFILE);
        assertThat(service.providerIdsIn(" ")).isEmpty();
        assertThat(service.citiesOf(EAGLE)).containsExactly(DONGYING);
        assertThat(service.citiesOf(null)).isEmpty();
    }

    private static void assertInvalid(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertNotFound(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(ProServiceNotFoundException.class);
    }

    /** 内存里的 mapper：条件和排序按 ProServiceMapper 的 SQL 写。 */
    private static final class InMemoryMapper implements ProServiceMapper {

        private final List<Service> services = new ArrayList<>();
        private final List<Item> items = new ArrayList<>();
        int catalogCalls;
        List<String> lastFacets;

        UUID add(UUID provider, String type, String category, String title, String summary, Long amount, String unit,
                 String note, int sort, List<String> cities, List<String> tags, List<String> facets) {
            Service service = new Service();
            service.id = UUID.randomUUID();
            service.provider = provider;
            service.type = type;
            service.category = category;
            service.title = title;
            service.summary = summary;
            service.amount = amount;
            service.unit = unit;
            service.note = note;
            service.sort = sort;
            service.cities.addAll(cities);
            services.add(service);
            for (int i = 0; i < tags.size(); i++) {
                items.add(new Item(service.id, "TAG", null, tags.get(i), null, i));
            }
            for (int i = 0; i < facets.size(); i++) {
                items.add(new Item(service.id, "FACET", facets.get(i), facets.get(i), null, i));
            }
            return service.id;
        }

        void unpublish(UUID id) {
            find(id).published = false;
        }

        void disableCity(UUID id, String city) {
            find(id).cities.remove(city);
            find(id).disabledCities.add(city);
        }

        void detail(UUID id, String kind, String label, String detail, int sort) {
            items.add(new Item(id, kind, null, label, detail, sort));
        }

        private Service find(UUID id) {
            return services.stream().filter(service -> service.id.equals(id)).findFirst().orElseThrow();
        }

        private List<Service> visible(String city) {
            return services.stream().filter(service -> service.published && service.cities.contains(city)).toList();
        }

        private List<Service> catalog(String city, String type, String keyword, List<String> facets, String mode) {
            catalogCalls++;
            lastFacets = facets;
            Pattern like = keyword == null ? null : Pattern.compile(
                    keyword.substring(1, keyword.length() - 1)
                            .replace("\\\\", "\u0000").replace("\\%", "%").replace("\\_", "_").replace("\u0000", "\\")
                            .chars()
                            .mapToObj(ch -> Pattern.quote(String.valueOf((char) ch)))
                            .reduce("", String::concat),
                    Pattern.CASE_INSENSITIVE);
            return visible(city).stream()
                    .filter(service -> service.type.equals(type))
                    .filter(service -> like == null || like.matcher(service.title).find()
                            || like.matcher(service.summary).find()
                            || items.stream().anyMatch(item -> item.serviceId.equals(service.id)
                                    && item.kind.equals("TAG") && like.matcher(item.label).find()))
                    .filter(service -> facets.isEmpty() || items.stream().anyMatch(item ->
                            item.serviceId.equals(service.id) && item.kind.equals("FACET")
                                    && facets.contains(item.code)))
                    .filter(service -> mode == null || service.mode().equals(mode))
                    .toList();
        }

        @Override
        public long countCatalog(String cityCode, String type, String keyword, List<String> facets, String priceMode) {
            return catalog(cityCode, type, keyword, facets, priceMode).size();
        }

        @Override
        public List<ServiceRow> findCatalog(String cityCode, String type, String keyword, List<String> facets,
                                            String priceMode, String sort, int limit, int offset) {
            Comparator<Service> byDefault = Comparator.comparingInt((Service service) -> service.sort)
                    .thenComparing(service -> service.id);
            Comparator<Service> projectLast = Comparator.comparing(service -> service.amount == null);
            Comparator<Service> order = switch (sort) {
                case "PRICE_ASC" -> projectLast.thenComparing(service -> service.amount,
                        Comparator.nullsLast(Comparator.<Long>naturalOrder())).thenComparing(byDefault);
                case "PRICE_DESC" -> projectLast.thenComparing(service -> service.amount,
                        Comparator.nullsLast(Comparator.<Long>reverseOrder())).thenComparing(byDefault);
                default -> byDefault;
            };
            return catalog(cityCode, type, keyword, facets, priceMode).stream()
                    .sorted(order)
                    .skip(offset)
                    .limit(limit)
                    .map(Service::row)
                    .toList();
        }

        @Override
        public long countOfferedInCity(String cityCode, String type) {
            return visible(cityCode).stream().filter(service -> service.type.equals(type)).count();
        }

        @Override
        public ServiceRow findVisible(UUID serviceId, String cityCode) {
            return visible(cityCode).stream().filter(service -> service.id.equals(serviceId))
                    .map(Service::row).findFirst().orElse(null);
        }

        @Override
        public List<ServiceRow> findProviderServices(UUID providerId, String cityCode) {
            List<String> types = List.of("AERIAL", "SURVEY", "MAINTENANCE", "TECH_SUPPORT");
            return visible(cityCode).stream()
                    .filter(service -> service.provider.equals(providerId))
                    .sorted(Comparator.comparingInt((Service service) -> types.indexOf(service.type))
                            .thenComparingInt(service -> service.sort))
                    .map(Service::row)
                    .toList();
        }

        @Override
        public List<ItemRow> findItems(List<UUID> serviceIds, List<String> kinds) {
            if (serviceIds.isEmpty() || kinds.isEmpty()) {
                throw new IllegalStateException("IN () is not valid SQL");
            }
            return items.stream()
                    .filter(item -> serviceIds.contains(item.serviceId) && kinds.contains(item.kind))
                    .sorted(Comparator.comparing((Item item) -> item.serviceId).thenComparing(item -> item.kind)
                            .thenComparingInt(item -> item.sort))
                    .map(item -> new ItemRow(item.serviceId, item.kind, item.code, item.label, item.detail))
                    .toList();
        }

        @Override
        public List<String> findCities(UUID serviceId) {
            return find(serviceId).cities.stream().sorted().toList();
        }

        @Override
        public List<String> findProviderCities(UUID providerId) {
            return services.stream()
                    .filter(service -> service.published && service.provider.equals(providerId))
                    .flatMap(service -> service.cities.stream())
                    .distinct()
                    .sorted()
                    .toList();
        }

        @Override
        public List<UUID> findProviderIdsIn(String cityCode) {
            return visible(cityCode).stream().map(service -> service.provider).distinct().sorted().toList();
        }

        private static final class Service {
            UUID id;
            UUID provider;
            String type;
            String category;
            String title;
            String summary;
            Long amount;
            String unit;
            String note;
            int sort;
            boolean published = true;
            final List<String> cities = new ArrayList<>();
            final List<String> disabledCities = new ArrayList<>();

            String mode() {
                return amount == null ? "PROJECT" : "PRICE_FROM";
            }

            ServiceRow row() {
                return new ServiceRow(id, provider, type, category, title, summary, mode(), amount, unit, note, null,
                        true);
            }
        }

        private record Item(UUID serviceId, String kind, String code, String label, String detail, int sort) {
        }
    }
}
