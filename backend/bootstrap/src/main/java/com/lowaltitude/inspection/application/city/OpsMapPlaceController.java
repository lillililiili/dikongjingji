package com.lowaltitude.inspection.application.city;

import com.lowaltitude.inspection.ops.OpsActors;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@RequestMapping("/api/v1/ops/map-places")
public class OpsMapPlaceController {
    private final OpsActors actors;
    private final MapPlaceOperations places;

    public OpsMapPlaceController(OpsActors actors, MapPlaceOperations places) {
        this.actors = actors;
        this.places = places;
    }

    @GetMapping
    public MapPlaceOperations.Page list(Authentication auth, @RequestParam(required = false) String cityCode,
            @RequestParam(defaultValue = "") String keyword, @RequestParam(required = false) Boolean published,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        actors.operator(auth);
        return places.list(cityCode, keyword, published, page, size);
    }

    @PutMapping("/{id}")
    public MapPlaceOperations.Place save(Authentication auth, @PathVariable UUID id,
            @RequestBody MapPlaceOperations.Edit edit) {
        return places.save(actors.operator(auth), id, edit);
    }

    @GetMapping("/{id}/changes")
    public List<MapPlaceOperations.Change> changes(Authentication auth, @PathVariable UUID id) {
        actors.operator(auth);
        return places.changes(id);
    }
}
