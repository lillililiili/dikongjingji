package com.lowaltitude.inspection.ops.media;

import java.time.Duration;

import com.lowaltitude.inspection.ops.media.OpsMediaService.OpsMediaContent;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仅开发、现场演示环境：公开读运营端上传的素材（封面、图集本来就公开展示），正式环境由对象存储的公开地址承担。
 * 演示时 ops.media.public-base-url 设成 https://media.demo.localhost/api/v1/dev，演示包把这个主机换成局域网地址来读
 * （docs/ops/现场演示.md）。只认素材模块生成的文件名；不在契约里。
 */
@RestController
@Profile("dev")
public final class OpsMediaDevController {

    private final OpsMediaService service;

    public OpsMediaDevController(OpsMediaService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/dev/ops-media/{fileName}")
    public ResponseEntity<byte[]> media(@PathVariable String fileName) {
        OpsMediaContent content = service.open(fileName);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .header("X-Content-Type-Options", "nosniff")
                .body(content.content());
    }
}
