package com.lowaltitude.inspection.application.city;

import com.lowaltitude.inspection.infrastructure.persistence.PlatformMapPlaceMapper;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PlatformMapPlaceController {
    private final PlatformMapPlaceMapper places;

    public PlatformMapPlaceController(PlatformMapPlaceMapper places) {
        this.places = places;
    }

    @GetMapping("/api/v1/platform/map-places")
    public List<PlatformMapPlaceMapper.PlaceRow> places(
            @RequestParam String cityCode, @RequestParam(defaultValue = "") String keyword,
            @RequestParam(required = false) Double latitude, @RequestParam(required = false) Double longitude) {
        String search = keyword.strip();
        if (!cityCode.matches("[0-9]{6}") || search.length() > 40
                || (latitude == null) != (longitude == null)
                || (latitude != null && (!Double.isFinite(latitude) || Math.abs(latitude) > 90
                || !Double.isFinite(longitude) || Math.abs(longitude) > 180))) {
            throw new IllegalArgumentException("Invalid map search");
        }
        return places.find(cityCode, search.replaceAll("(?U)\\s+", ""), latitude, longitude);
    }
}
