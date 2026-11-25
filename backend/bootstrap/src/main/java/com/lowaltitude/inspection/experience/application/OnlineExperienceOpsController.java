package com.lowaltitude.inspection.experience.application;

import com.lowaltitude.inspection.ops.OpsActors;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import static com.lowaltitude.inspection.experience.application.OnlineExperienceOpsService.*;

@RestController
@ConditionalOnProperty(prefix="spring.datasource", name="url")
@RequestMapping("/api/v1/ops/experience-items")
public class OnlineExperienceOpsController {
    private final OpsActors actors;
    private final OnlineExperienceOpsService service;
    public OnlineExperienceOpsController(OpsActors actors, OnlineExperienceOpsService service) {
        this.actors=actors;
        this.service=service;
    }
    @GetMapping
    Page list(Authentication auth, @RequestParam(required=false) String keyword, @RequestParam(required=false) Status status,
              @RequestParam(required=false) Type type, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        actors.operator(auth);
        return service.list(keyword,status,type,page,size);
    }
    @GetMapping("/choices/{target}")
    List<Choice> choices(Authentication auth, @PathVariable Target target, @RequestParam(required=false) String keyword) {
        actors.operator(auth);
        return service.choices(target,keyword);
    }
    @GetMapping("/{id}")
    Item item(Authentication auth, @PathVariable UUID id) {
        actors.operator(auth);
        return service.item(id);
    }
    @GetMapping("/{id}/changes")
    List<Change> history(Authentication auth, @PathVariable UUID id) {
        actors.operator(auth);
        return service.changes(id);
    }
    @PutMapping("/{id}")
    Item save(Authentication auth, @PathVariable UUID id, @RequestBody Input input) {
        return service.save(id,input,actors.operator(auth));
    }
}
