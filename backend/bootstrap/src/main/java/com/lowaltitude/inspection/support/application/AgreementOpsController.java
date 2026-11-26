package com.lowaltitude.inspection.support.application;

import java.util.UUID;
import com.lowaltitude.inspection.ops.OpsActors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@RequestMapping("/api/v1/ops/agreements")
public class AgreementOpsController {
    private final AgreementOpsService service;
    private final OpsActors actors;

    public AgreementOpsController(AgreementOpsService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping
    public AgreementOpsService.Page list(Authentication auth, @RequestParam(required=false) String doc,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        actors.operator(auth);
        return service.list(doc, page, size);
    }

    @GetMapping("/{id}")
    public AgreementOpsService.Detail detail(Authentication auth, @PathVariable UUID id) {
        actors.operator(auth);
        return service.detail(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgreementOpsService.Detail create(Authentication auth, @RequestBody AgreementOpsService.Draft draft) {
        return service.save(null, draft, actors.operator(auth));
    }

    @PutMapping("/{id}")
    public AgreementOpsService.Detail save(Authentication auth, @PathVariable UUID id, @RequestBody AgreementOpsService.Draft draft) {
        return service.save(id, draft, actors.operator(auth));
    }

    @PostMapping("/{id}/publish")
    public AgreementOpsService.Detail publish(Authentication auth, @PathVariable UUID id, @RequestBody Publication input) {
        return service.publish(id, input.revision(), actors.operator(auth));
    }

    public record Publication(Integer revision) {}
}
