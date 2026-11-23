package com.lowaltitude.inspection.search.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全局搜索（契约 contracts/openapi-search.yaml，画板 SearchPre、MallSearch）。公开只读，只按关键词把各模块已有的
 * 公开列表拼在一起；哪个模块读取失败，那一组为空，其余照常返回。
 */
public final class SearchReadService {

    static final int MAX_KEYWORD_LENGTH = 40;
    static final int ALL_DEFAULT_SIZE = 3;
    static final int ALL_MAX_SIZE = 5;
    static final int SCOPE_DEFAULT_SIZE = 20;
    static final int SCOPE_MAX_SIZE = 20;
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchReadService.class);

    private final SearchPorts.Products products;
    private final SearchPorts.InspectionServices inspectionServices;
    private final SearchPorts.ProServices proServices;
    private final SearchPorts.Activities activities;
    private final SearchPorts.Contents contents;
    private final SearchPorts.Venues venues;
    private final SearchKeywordCorrections corrections;

    public SearchReadService(
            SearchPorts.Products products,
            SearchPorts.InspectionServices inspectionServices,
            SearchPorts.ProServices proServices,
            SearchPorts.Activities activities,
            SearchPorts.Contents contents,
            SearchPorts.Venues venues,
            SearchKeywordCorrections corrections
    ) {
        this.products = products;
        this.inspectionServices = inspectionServices;
        this.proServices = proServices;
        this.activities = activities;
        this.contents = contents;
        this.venues = venues;
        this.corrections = corrections;
    }

    public SearchResultView search(String cityCode, String keyword, String scopeCode, Integer size) {
        String city = cityCode == null ? null : cityCode.trim();
        if (city == null || !city.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        String word = keyword == null ? "" : keyword.strip();
        if (word.isEmpty() || word.length() > MAX_KEYWORD_LENGTH) {
            throw new IllegalArgumentException("keyword is invalid");
        }
        SearchScope scope = parseScope(scopeCode);
        int limit = limit(scope, size);

        SearchGroupView products = wanted(scope, SearchScope.PRODUCT)
                ? group("products", () -> this.products.search(city, word, limit))
                : null;
        SearchGroupView services = wanted(scope, SearchScope.SERVICE) ? services(city, word, limit) : null;
        SearchGroupView activities = wanted(scope, SearchScope.ACTIVITY)
                ? group("activities", () -> this.activities.search(city, word, limit))
                : null;
        SearchGroupView contents = wanted(scope, SearchScope.CONTENT)
                ? group("contents", () -> this.contents.search(city, word, limit))
                : null;
        SearchGroupView venues = wanted(scope, SearchScope.VENUE)
                ? group("venues", () -> this.venues.search(city, word, limit))
                : null;
        SearchCorrectionView correction = corrections.correct(word)
                .filter(suggested -> !suggested.equals(word))
                .map(SearchCorrectionView::new)
                .orElse(null);
        return new SearchResultView(city, word, scope, correction, products, services, activities, contents, venues);
    }

    /** 服务组：先巡检，再四类专业服务；两边各自失败互不影响。 */
    private SearchGroupView services(String city, String word, int limit) {
        SearchPorts.Found inspection = found("inspectionServices", () -> inspectionServices.search(city, word, limit));
        SearchPorts.Found pro = inspection.items().size() >= limit
                ? countOnly(city, word)
                : found("proServices", () -> proServices.search(city, word, limit - inspection.items().size()));
        List<SearchServiceHitView> hits = new ArrayList<>();
        inspection.items().forEach(item -> hits.add(SearchServiceHitView.inspection(item)));
        pro.items().forEach(item -> hits.add(SearchServiceHitView.proService(item)));
        return new SearchGroupView(inspection.total() + pro.total(), hits);
    }

    /** 巡检已经占满时，专业服务只要个数（读一条拿 total）。 */
    private SearchPorts.Found countOnly(String city, String word) {
        SearchPorts.Found pro = found("proServices", () -> proServices.search(city, word, 1));
        return new SearchPorts.Found(pro.total(), List.of());
    }

    private static boolean wanted(SearchScope scope, SearchScope group) {
        return scope == SearchScope.ALL || scope == group;
    }

    private static SearchScope parseScope(String code) {
        if (code == null || code.isBlank()) {
            return SearchScope.ALL;
        }
        try {
            return SearchScope.valueOf(code.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("scope is invalid");
        }
    }

    private static int limit(SearchScope scope, Integer size) {
        boolean all = scope == SearchScope.ALL;
        if (size == null) {
            return all ? ALL_DEFAULT_SIZE : SCOPE_DEFAULT_SIZE;
        }
        int max = all ? ALL_MAX_SIZE : SCOPE_MAX_SIZE;
        if (size < 1 || size > max) {
            throw new IllegalArgumentException("size is invalid");
        }
        return size;
    }

    private static SearchGroupView group(String name, Supplier<SearchPorts.Found> loader) {
        SearchPorts.Found found = found(name, loader);
        return new SearchGroupView(found.total(), found.items());
    }

    private static SearchPorts.Found found(String name, Supplier<SearchPorts.Found> loader) {
        try {
            SearchPorts.Found found = loader.get();
            return found == null ? SearchPorts.Found.empty() : found;
        } catch (RuntimeException failure) {
            // 不记关键词：搜索词可能带个人信息（AGENTS §14 日志不得记录完整请求）。
            LOGGER.warn("search group {} failed: {}", name, failure.getClass().getSimpleName());
            return SearchPorts.Found.empty();
        }
    }
}
