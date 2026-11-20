package com.lowaltitude.inspection.content.application;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 发现页的图文内容和专题（H1，契约 contracts/openapi-content.yaml 1.1.0）。公开只读，不需要登录。
 * 必填的 cityCode 声明为 required = false，缺失时由读服务按参数无效处理并返回 400。
 */
@RestController
public final class ContentController {

    private final ContentReadService service;

    public ContentController(ContentReadService service) {
        this.service = service;
    }

    /** 推荐、案例、知识频道的一页；推荐频道第一页另带当前的专题。赛事、研学频道由 App 读活动列表。 */
    @GetMapping("/api/v1/content/feed")
    public ContentFeedView feed(
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String channel,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.feed(cityCode, channel, page, size);
    }

    @GetMapping("/api/v1/content/articles/{articleId}")
    public ContentArticleView article(@PathVariable UUID articleId, @RequestParam(required = false) String cityCode) {
        return service.article(articleId, cityCode);
    }

    @GetMapping("/api/v1/content/topics/{topicId}")
    public ContentTopicView topic(@PathVariable UUID topicId, @RequestParam(required = false) String cityCode) {
        return service.topic(topicId, cityCode);
    }

    /** R6-3 相关内容：提到某个对象（商品详情传 PRODUCT 和商品 ID）的图文卡片。 */
    @GetMapping("/api/v1/content/related")
    public ContentRelatedView related(
            @RequestParam(required = false) String refType,
            @RequestParam(required = false) UUID refId,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) Integer limit
    ) {
        return service.related(refType, refId, cityCode, limit);
    }
}
