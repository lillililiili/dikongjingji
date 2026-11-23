package com.lowaltitude.inspection.ops.media;

import java.io.IOException;

import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import com.lowaltitude.inspection.ops.media.OpsMediaService.OpsMediaContent;
import com.lowaltitude.inspection.ops.media.OpsMediaService.OpsMediaView;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 运营端素材上传与预览（S3-1，契约 contracts/openapi-ops-media.yaml 1.0.0）：只给平台运营。
 */
@RestController
public final class OpsMediaController {

    private static final String MEDIA = "/api/v1/ops/media";

    private final OpsMediaService service;
    private final OpsActors actors;

    public OpsMediaController(OpsMediaService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping(path = MEDIA, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OpsMediaView> upload(Authentication authentication,
                                               @RequestPart("file") MultipartFile file) throws IOException {
        OpsActor actor = actors.operator(authentication);
        if (file.isEmpty() || file.getSize() > OpsMediaService.MAX_VIDEO_BYTES) {
            throw new IllegalArgumentException("file size is invalid");
        }
        return ResponseEntity.status(201).body(service.upload(file.getBytes(), actor));
    }

    /** 预览：带登录取回（开发环境的本机文件没有能打开的公开地址）；不缓存，不嗅探类型。 */
    @GetMapping(MEDIA + "/{fileName}")
    public ResponseEntity<byte[]> content(Authentication authentication, @PathVariable String fileName) {
        actors.operator(authentication);
        OpsMediaContent content = service.open(fileName);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(content.content());
    }
}
