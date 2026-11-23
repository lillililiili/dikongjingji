package com.lowaltitude.inspection.mall.application;

import java.time.Duration;

import com.lowaltitude.inspection.bootstrap.infrastructure.LocalMallMediaStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开发环境回读运营端上传的商品图片（mall.media.local-dir 配置了才有这个接口；不在任何契约里，生产没有）。
 * 公开读（商品图本来就公开展示），只认商城模块生成的文件名，按扩展名给类型，不让浏览器猜类型。
 */
@RestController
@ConditionalOnProperty(prefix = "mall.media", name = "local-dir")
public final class MallMediaDevController {

    private final MallOpsPorts.MediaStore store;

    public MallMediaDevController(MallOpsPorts.MediaStore store) {
        this.store = store;
    }

    @GetMapping("/api/v1/mall/media/products/{fileName}")
    public ResponseEntity<byte[]> media(@PathVariable String fileName) {
        if (!(store instanceof LocalMallMediaStore local)) {
            return ResponseEntity.notFound().build();
        }
        return local.read("products/" + fileName)
                .map(content -> ResponseEntity.ok()
                        .contentType(fileName.endsWith(".png") ? MediaType.IMAGE_PNG
                                : fileName.endsWith(".webp") ? MediaType.parseMediaType("image/webp")
                                : MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(content))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
