package com.lowaltitude.inspection.home;

import java.util.List;

import com.lowaltitude.inspection.content.infrastructure.persistence.HomeCarouselMapper;
import com.lowaltitude.inspection.content.infrastructure.persistence.HomeCarouselMapper.SlideRow;
import com.lowaltitude.inspection.media.DevelopmentMediaPath;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.media.OpsMediaService;
import com.lowaltitude.inspection.ops.media.OpsMediaStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class HomeCarouselController {
    private final HomeCarouselMapper slides;
    private final OpsMediaStore media;
    private final boolean development;

    public HomeCarouselController(HomeCarouselMapper slides, OpsMediaStore media, Environment environment) {
        this.slides = slides;
        this.media = media;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

    @GetMapping("/api/v1/home/carousel")
    public Carousel carousel(@RequestParam String cityCode) {
        String city = cityCode.trim();
        if (!city.matches("[A-Z0-9]{4,16}")) {
            throw new IllegalArgumentException("city code is invalid");
        }
        return new Carousel(slides.published(city).stream()
                .filter(row -> development || !DevelopmentMediaPath.matches(row.imageRef()))
                .map(this::slide).toList());
    }

    private Slide slide(SlideRow row) {
        String url;
        try {
            url = DevelopmentMediaPath.matches(row.imageRef()) ? row.imageRef() : media.publicUrl(row.imageRef());
        } catch (OpsMediaStore.UnavailableException unavailable) {
            throw new OpsException(OpsMediaService.Error.STORE_UNAVAILABLE);
        }
        return new Slide(row.id(), row.tag(), List.of(row.titleFirst(), row.titleSecond()),
                List.of(row.descriptionFirst(), row.descriptionSecond()), row.actionLabel(),
                url, row.imageAlt(), row.tone(), row.target());
    }

    public record Carousel(List<Slide> items) {}
    public record Slide(String id, String tag, List<String> titleLines, List<String> descriptionLines,
                        String actionLabel, String imageUrl, String imageAlt, String tone, String target) {}
}
