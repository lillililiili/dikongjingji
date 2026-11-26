package com.lowaltitude.inspection.experience.application.ops;

import java.util.List;
import com.lowaltitude.inspection.ops.OpsActors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ops/experience/play-games")
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class PlayGameOpsController {
    public record Version(Integer version) {}
    private final PlayGameOpsService games;
    private final OpsActors actors;
    public PlayGameOpsController(PlayGameOpsService games, OpsActors actors) { this.games=games; this.actors=actors; }

    @GetMapping
    public List<PlayGameOpsService.Game> list(Authentication auth) {
        actors.operator(auth);
        return games.list();
    }
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlayGameOpsService.Game create(Authentication auth, @RequestBody PlayGameOpsService.Input input) {
        return games.save(null,input,actors.operator(auth));
    }
    @PutMapping("/{id}")
    public PlayGameOpsService.Game edit(Authentication auth,@PathVariable String id,@RequestBody PlayGameOpsService.Input input) {
        return games.save(id,input,actors.operator(auth));
    }
    @PostMapping("/{id}/publish")
    public PlayGameOpsService.Game publish(Authentication auth,@PathVariable String id,@RequestBody Version input) {
        return games.publish(id,input.version(),actors.operator(auth));
    }
    @PostMapping("/{id}/offline")
    public PlayGameOpsService.Game offline(Authentication auth,@PathVariable String id,@RequestBody Version input) {
        return games.offline(id,input.version(),actors.operator(auth));
    }
    @GetMapping("/{id}/changes")
    public List<PlayGameOpsService.Change> changes(Authentication auth,@PathVariable String id) {
        actors.operator(auth);
        return games.changes(id);
    }
}
