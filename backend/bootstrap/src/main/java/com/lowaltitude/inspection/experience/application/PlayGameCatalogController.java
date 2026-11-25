package com.lowaltitude.inspection.experience.application;

import com.lowaltitude.inspection.experience.infrastructure.persistence.PlayGameMapper;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class PlayGameCatalogController {
    private final PlayGameMapper games;

    public PlayGameCatalogController(PlayGameMapper games) {
        this.games = games;
    }

    @GetMapping("/api/v1/experience/play-games")
    public List<PlayGameMapper.Game> games() {
        return games.listPublished();
    }
}
