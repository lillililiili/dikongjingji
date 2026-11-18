package com.lowaltitude.inspection.home;

import java.util.List;
import java.util.function.Supplier;

import com.lowaltitude.inspection.activity.application.ActivityHomeFeed;
import com.lowaltitude.inspection.experience.application.ExperienceHomeFeed;
import com.lowaltitude.inspection.mall.application.MallHomeFeed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页“推荐”频道下半部分的四块内容，一次给出（契约 contracts/openapi-home.yaml）。
 * 公开只读，只是把三个模块已有的公开列表拼在一起，每一项的结构和各自的列表接口相同。
 * 哪个模块读取失败，哪一块就是空的，其余照常返回：首页不能因为一块内容整页打不开。
 */
@RestController
public class HomeRecommendController {

    static final int ACTIVITY_COUNT = 3;
    static final int PRODUCT_COUNT = 4;
    static final int VENUE_COUNT = 2;
    static final int TODAY_PROJECT_COUNT = 3;
    private static final String CITY_CODE_PATTERN = "^[A-Z0-9]{4,16}$";
    private static final Logger LOGGER = LoggerFactory.getLogger(HomeRecommendController.class);

    private final ActivityHomeFeed activities;
    private final MallHomeFeed products;
    private final ExperienceHomeFeed venues;

    public HomeRecommendController(
            ActivityHomeFeed activities,
            MallHomeFeed products,
            ExperienceHomeFeed venues
    ) {
        this.activities = activities;
        this.products = products;
        this.venues = venues;
    }

    @GetMapping("/api/v1/home/recommend")
    public HomeRecommendView recommend(@RequestParam(required = false) String cityCode) {
        String city = cityCode == null ? null : cityCode.trim();
        if (city == null || !city.matches(CITY_CODE_PATTERN)) {
            throw new IllegalArgumentException("city code is invalid");
        }
        ExperienceHomeFeed.Feed venueFeed = block(
                "venues",
                () -> venues.load(city, VENUE_COUNT, TODAY_PROJECT_COUNT),
                new ExperienceHomeFeed.Feed(List.of(), null));
        return new HomeRecommendView(
                venueFeed.today(),
                block("activities", () -> activities.upcoming(city, ACTIVITY_COUNT), List.of()),
                block("products", () -> products.featured(city, PRODUCT_COUNT), List.of()),
                venueFeed.venues());
    }

    private static <T> T block(String name, Supplier<T> loader, T empty) {
        try {
            return loader.get();
        } catch (RuntimeException failure) {
            LOGGER.warn("home recommend block {} failed: {}", name, failure.toString());
            return empty;
        }
    }

    public record HomeRecommendView(
            ExperienceHomeFeed.Today today,
            List<?> activities,
            List<?> products,
            List<?> venues
    ) {
    }
}
