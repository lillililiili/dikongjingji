package com.lowaltitude.inspection.opsimport.application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportBatchPageView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportBatchView;
import com.lowaltitude.inspection.opsimport.application.OpsImportViews.OpsImportKindView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 运营端“批量导入”（S2-3，契约 contracts/openapi-ops-import.yaml 1.0.0）：预检、导入、导入记录。只给平台运营。
 */
@RestController
public final class OpsImportController {

    private static final String IMPORTS = "/api/v1/ops/imports";

    private final OpsImportService service;
    private final OpsActors actors;

    public OpsImportController(OpsImportService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping(IMPORTS + "/kinds")
    public List<OpsImportKindView> kinds(Authentication authentication) {
        actors.operator(authentication);
        return service.kinds();
    }

    @PostMapping(path = IMPORTS + "/{kind}/checks", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OpsImportBatchView> check(
            Authentication authentication,
            @PathVariable String kind,
            @RequestPart("files") List<MultipartFile> files
    ) throws IOException {
        OpsImportPorts.Actor actor = actor(authentication);
        if (files == null || files.isEmpty() || files.size() > OpsImportService.MAX_FILES) {
            throw new IllegalArgumentException("1–12 files are required");
        }
        List<OpsImportService.UploadedFile> uploads = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty() || file.getSize() > OpsImportService.MAX_FILE_BYTES) {
                throw new IllegalArgumentException("file size is invalid");
            }
            uploads.add(new OpsImportService.UploadedFile(file.getOriginalFilename(), file.getBytes()));
        }
        return ResponseEntity.status(201).body(service.check(kind, uploads, actor));
    }

    @GetMapping(IMPORTS + "/batches")
    public OpsImportBatchPageView batches(
            Authentication authentication,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.batches(kind, page, size);
    }

    @GetMapping(IMPORTS + "/batches/{batchId}")
    public OpsImportBatchView batch(Authentication authentication, @PathVariable UUID batchId) {
        actors.operator(authentication);
        return service.batch(batchId);
    }

    /** 第一次导入时返回里有新账号的一次性初始口令：不缓存。 */
    @PostMapping(IMPORTS + "/batches/{batchId}/run")
    public ResponseEntity<OpsImportBatchView> run(Authentication authentication, @PathVariable UUID batchId) {
        OpsImportBatchView view = service.run(batchId, actor(authentication));
        return ResponseEntity.ok().header(org.springframework.http.HttpHeaders.CACHE_CONTROL, "no-store").body(view);
    }

    private OpsImportPorts.Actor actor(Authentication authentication) {
        OpsActor actor = actors.operator(authentication);
        return new OpsImportPorts.Actor(actor.accountId(), actor.displayName());
    }
}
