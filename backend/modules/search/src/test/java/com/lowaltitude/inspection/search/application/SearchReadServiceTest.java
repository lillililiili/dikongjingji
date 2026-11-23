package com.lowaltitude.inspection.search.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

class SearchReadServiceTest {

    private final List<String> calls = new ArrayList<>();

    private SearchPorts.Found found(String name, String city, String keyword, int limit, long total) {
        calls.add(name + ":" + city + ":" + keyword + ":" + limit);
        return new SearchPorts.Found(total, IntStream.range(0, (int) Math.min(limit, total))
                .mapToObj(index -> Map.of("name", name + index))
                .toList());
    }

    private SearchReadService service(long products, long inspection, long pro, long activities, long contents,
                                      long venues) {
        return new SearchReadService(
                (city, keyword, limit) -> found("products", city, keyword, limit, products),
                (city, keyword, limit) -> found("inspection", city, keyword, limit, inspection),
                (city, keyword, limit) -> found("pro", city, keyword, limit, pro),
                (city, keyword, limit) -> found("activities", city, keyword, limit, activities),
                (city, keyword, limit) -> found("contents", city, keyword, limit, contents),
                (city, keyword, limit) -> found("venues", city, keyword, limit, venues),
                SearchKeywordCorrections.defaults());
    }

    @Test
    void allScopeReadsEveryGroupWithThreeEachByDefault() {
        SearchResultView result = service(2, 1, 4, 1, 1, 0).search(" 370500 ", "  测绘 ", null, null);

        assertThat(result.cityCode()).isEqualTo("370500");
        assertThat(result.keyword()).isEqualTo("测绘");
        assertThat(result.scope()).isEqualTo(SearchScope.ALL);
        assertThat(result.correction()).isNull();
        assertThat(result.products().total()).isEqualTo(2);
        assertThat(result.products().items()).hasSize(2);
        // 服务组先巡检、再专业服务，凑满 3 条；total 是两边之和。
        assertThat(result.services().total()).isEqualTo(5);
        assertThat(result.services().items()).hasSize(3);
        assertThat(result.services().items()).extracting(item -> ((SearchServiceHitView) item).kind())
                .containsExactly(SearchServiceKind.INSPECTION, SearchServiceKind.PRO_SERVICE,
                        SearchServiceKind.PRO_SERVICE);
        assertThat(calls).contains("inspection:370500:测绘:3", "pro:370500:测绘:2");
        assertThat(result.venues().total()).isZero();
        assertThat(result.venues().items()).isEmpty();
    }

    @Test
    void aSingleScopeReadsOnlyItsGroupWithTwentyByDefault() {
        SearchResultView result = service(30, 0, 0, 0, 0, 0).search("370500", "机器人", "product", null);

        assertThat(result.scope()).isEqualTo(SearchScope.PRODUCT);
        assertThat(result.products().items()).hasSize(20);
        assertThat(result.products().total()).isEqualTo(30);
        assertThat(result.services()).isNull();
        assertThat(result.activities()).isNull();
        assertThat(result.contents()).isNull();
        assertThat(result.venues()).isNull();
        assertThat(calls).containsExactly("products:370500:机器人:20");
    }

    @Test
    void whenInspectionFillsTheServiceGroupProServicesAreOnlyCounted() {
        SearchResultView result = service(0, 5, 2, 0, 0, 0).search("370500", "巡检", "SERVICE", 4);

        assertThat(result.services().items()).hasSize(4)
                .allSatisfy(item -> assertThat(((SearchServiceHitView) item).kind())
                        .isEqualTo(SearchServiceKind.INSPECTION));
        assertThat(result.services().total()).isEqualTo(7);
        assertThat(calls).contains("pro:370500:巡检:1");
    }

    @Test
    void aFailingModuleLeavesOnlyItsGroupEmpty() {
        SearchReadService service = new SearchReadService(
                (city, keyword, limit) -> {
                    throw new IllegalStateException("mall store is down");
                },
                (city, keyword, limit) -> found("inspection", city, keyword, limit, 1),
                (city, keyword, limit) -> {
                    throw new IllegalStateException("pro store is down");
                },
                (city, keyword, limit) -> found("activities", city, keyword, limit, 1),
                (city, keyword, limit) -> null,
                (city, keyword, limit) -> found("venues", city, keyword, limit, 1),
                SearchKeywordCorrections.defaults());

        SearchResultView result = service.search("370500", "东营", "ALL", 5);

        assertThat(result.products().total()).isZero();
        assertThat(result.products().items()).isEmpty();
        assertThat(result.services().total()).isEqualTo(1);
        assertThat(result.contents().items()).isEmpty();
        assertThat(result.activities().items()).hasSize(1);
        assertThat(result.venues().items()).hasSize(1);
    }

    @Test
    void aKnownTypoGetsACorrectionButIsStillSearchedAsTyped() {
        SearchResultView result = service(0, 0, 0, 0, 0, 0).search("370500", "巡 简", null, null);

        assertThat(result.correction()).isEqualTo(new SearchCorrectionView("巡检"));
        assertThat(result.keyword()).isEqualTo("巡 简");
        assertThat(calls).contains("inspection:370500:巡 简:3");
        assertThat(service(0, 0, 0, 0, 0, 0).search("370500", "机器够", null, null).correction().keyword())
                .isEqualTo("机器狗");
        assertThat(service(0, 0, 0, 0, 0, 0).search("370500", "巡检", null, null).correction()).isNull();
    }

    @Test
    void badInputIsRejectedBeforeAnyModuleIsRead() {
        SearchReadService service = service(1, 1, 1, 1, 1, 1);

        for (String city : new String[] {null, "", "x", "370500; DROP"}) {
            assertThatThrownBy(() -> service.search(city, "测绘", null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String keyword : new String[] {null, "", "   ", "测".repeat(41)}) {
            assertThatThrownBy(() -> service.search("370500", keyword, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.search("370500", "测绘", "PEOPLE", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("370500", "测绘", "ALL", 6))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("370500", "测绘", "VENUE", 21))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.search("370500", "测绘", "VENUE", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(calls).isEmpty();
        assertThat(service.search("370500", "测".repeat(40), "VENUE", 20).venues()).isNotNull();
    }

    @Test
    void scanReadsPagesUntilTheEndOrTwoHundredItemsAndKeepsListOrder() {
        List<Integer> pagesRead = new ArrayList<>();
        SearchPorts.Found found = SearchPorts.scan((page, size) -> {
            pagesRead.add(page);
            List<String> items = IntStream.range(0, size)
                    .mapToObj(index -> ((page - 1) * size + index) % 3 == 0 ? "东营站" + index : "别处")
                    .toList();
            return new SearchPorts.Page<>(items, true);
        }, item -> SearchPorts.contains("东营", item), 5);

        assertThat(pagesRead).containsExactly(1, 2, 3, 4);
        assertThat(found.total()).isEqualTo(67);
        assertThat(found.items()).hasSize(5).first().isEqualTo("东营站0");

        List<Integer> shortList = new ArrayList<>();
        SearchPorts.Found few = SearchPorts.scan((page, size) -> {
            shortList.add(page);
            return new SearchPorts.Page<>(List.of("东营低空与机器人体验馆", "济南馆"), false);
        }, item -> SearchPorts.contains("体验 馆", item), 3);
        assertThat(shortList).containsExactly(1);
        assertThat(few.total()).isEqualTo(1);
    }

    @Test
    void containsIgnoresSpacesAndCase() {
        assertThat(SearchPorts.contains("3分钟", "3 分钟看懂编程机器人")).isTrue();
        assertThat(SearchPorts.contains("dji", null, "DJI Mini 4")).isTrue();
        assertThat(SearchPorts.contains("巡检", "测绘", "航拍")).isFalse();
        assertThat(SearchPorts.contains("  ", "任何")).isFalse();
    }
}
