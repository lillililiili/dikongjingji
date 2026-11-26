package com.lowaltitude.inspection.ops.pagecontent;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsActors;
import com.lowaltitude.inspection.ops.pagecontent.PageContentOpsService.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@RequestMapping("/api/v1/ops")
public class PageContentOpsController {
    private final PageContentOpsService service;
    private final OpsActors actors;
    public PageContentOpsController(PageContentOpsService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }
    public record Saved(String id) {}

    @GetMapping("/home/carousel")
    public Page<Slide> slides(Authentication auth, @RequestParam(required=false) String cityCode,
                              @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) {
        actors.operator(auth);
        return service.slides(cityCode, page, size);
    }

    @PostMapping("/home/carousel/{city}")
    @ResponseStatus(HttpStatus.CREATED)
    public Saved addSlide(Authentication auth, @PathVariable String city, @RequestBody SlideInput input) {
        return new Saved(service.saveSlide(city, null, input, actors.operator(auth)));
    }
    @PutMapping("/home/carousel/{city}/{id}")
    public Saved saveSlide(Authentication auth, @PathVariable String city, @PathVariable String id,
                           @RequestBody SlideInput input) {
        return new Saved(service.saveSlide(city, id, input, actors.operator(auth)));
    }
    @GetMapping("/home/carousel/{city}/{id}/changes")
    public List<Change> slideChanges(Authentication auth, @PathVariable String city, @PathVariable String id) {
        actors.operator(auth);
        return service.slideChanges(city,id);
    }
    @GetMapping("/inspection/portal-content")
    public List<Portal> portal(Authentication auth, @RequestParam(required=false) String cityCode) {
        actors.operator(auth);
        return service.portal(cityCode);
    }
    @PostMapping("/inspection/portal-content")
    @ResponseStatus(HttpStatus.CREATED)
    public Saved addPortal(Authentication auth, @RequestBody PortalInput input) {
        return new Saved(service.savePortal(null, input, actors.operator(auth)).toString());
    }
    @PutMapping("/inspection/portal-content/{id}")
    public Saved savePortal(Authentication auth, @PathVariable UUID id, @RequestBody PortalInput input) {
        return new Saved(service.savePortal(id, input, actors.operator(auth)).toString());
    }
    @GetMapping("/inspection/portal-content/{id}/changes")
    public List<Change> portalChanges(Authentication auth, @PathVariable UUID id) {
        actors.operator(auth);
        return service.portalChanges(id);
    }
    @GetMapping("/inspection/services/{id}/deliverable-images")
    public List<Deliverable> deliverables(Authentication auth, @PathVariable UUID id) {
        actors.operator(auth);
        return service.deliverables(id);
    }
    @PutMapping("/inspection/services/{id}/deliverable-images/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void saveDeliverable(Authentication auth, @PathVariable UUID id, @PathVariable String code,
                                @RequestBody DeliverableImage input) {
        service.saveDeliverable(id, code, input, actors.operator(auth));
    }
}
