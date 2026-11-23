package com.lowaltitude.inspection.content.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.content.application.ops.ContentOpsService;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.ArticleDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.AuthorDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.BlockDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.BlockItemDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.MerchantItemDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.Operator;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsArticleView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsContentPageView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsTopicPageView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsTopicView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.OpsVideoView;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.RefDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.TopicDraft;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews.VideoDraft;
import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“发现内容”（S3-1，契约 contracts/openapi-ops-content.yaml 1.0.0）：图文、视频、专题。只给平台运营；
 * 改内容、上线、下线都带版本号，每次改动记一条改动记录（操作人显示名取自身份资料）。
 */
@RestController
public final class OpsContentController {

    private static final String ARTICLES = "/api/v1/ops/content/articles";
    private static final String ARTICLE = ARTICLES + "/{articleId}";
    private static final String VIDEOS = "/api/v1/ops/content/videos";
    private static final String VIDEO = VIDEOS + "/{videoId}";
    private static final String TOPICS = "/api/v1/ops/content/topics";
    private static final String TOPIC = TOPICS + "/{topicId}";

    private final ContentOpsService service;
    private final OpsActors actors;

    public OpsContentController(ContentOpsService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    // ---- 图文 ----

    @GetMapping(ARTICLES)
    public OpsContentPageView articles(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.articles(keyword, kind, cityCode, status, page, size);
    }

    @PostMapping(ARTICLES)
    public ResponseEntity<OpsArticleView> createArticle(Authentication authentication,
                                                        @RequestBody ArticleRequest request) {
        Operator operator = operator(authentication);
        return ResponseEntity.status(201).body(service.createArticle(request.draft(), operator));
    }

    @GetMapping(ARTICLE)
    public OpsArticleView article(Authentication authentication, @PathVariable UUID articleId) {
        actors.operator(authentication);
        return service.article(articleId);
    }

    @PutMapping(ARTICLE)
    public OpsArticleView updateArticle(Authentication authentication, @PathVariable UUID articleId,
                                        @RequestBody ArticleRequest request) {
        Operator operator = operator(authentication);
        return service.updateArticle(articleId, request.draft(), request.version(), operator);
    }

    @PostMapping(ARTICLE + "/publish")
    public OpsArticleView publishArticle(Authentication authentication, @PathVariable UUID articleId,
                                         @RequestBody PublishRequest request) {
        Operator operator = operator(authentication);
        return service.publishArticle(articleId, request.version(), request.publishAt(), operator);
    }

    @PostMapping(ARTICLE + "/offline")
    public OpsArticleView offlineArticle(Authentication authentication, @PathVariable UUID articleId,
                                         @RequestBody VersionRequest request) {
        Operator operator = operator(authentication);
        return service.offlineArticle(articleId, request.version(), operator);
    }

    @GetMapping(ARTICLE + "/changes")
    public List<OpsChangeView> articleChanges(Authentication authentication, @PathVariable UUID articleId) {
        actors.operator(authentication);
        return service.articleChanges(articleId);
    }

    // ---- 视频 ----

    @GetMapping(VIDEOS)
    public OpsContentPageView videos(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.videos(keyword, cityCode, status, page, size);
    }

    @PostMapping(VIDEOS)
    public ResponseEntity<OpsVideoView> createVideo(Authentication authentication, @RequestBody VideoRequest request) {
        Operator operator = operator(authentication);
        return ResponseEntity.status(201).body(service.createVideo(request.draft(), operator));
    }

    @GetMapping(VIDEO)
    public OpsVideoView video(Authentication authentication, @PathVariable UUID videoId) {
        actors.operator(authentication);
        return service.video(videoId);
    }

    @PutMapping(VIDEO)
    public OpsVideoView updateVideo(Authentication authentication, @PathVariable UUID videoId,
                                    @RequestBody VideoRequest request) {
        Operator operator = operator(authentication);
        return service.updateVideo(videoId, request.draft(), request.version(), operator);
    }

    @PostMapping(VIDEO + "/publish")
    public OpsVideoView publishVideo(Authentication authentication, @PathVariable UUID videoId,
                                     @RequestBody PublishRequest request) {
        Operator operator = operator(authentication);
        return service.publishVideo(videoId, request.version(), request.publishAt(), operator);
    }

    @PostMapping(VIDEO + "/offline")
    public OpsVideoView offlineVideo(Authentication authentication, @PathVariable UUID videoId,
                                     @RequestBody VersionRequest request) {
        Operator operator = operator(authentication);
        return service.offlineVideo(videoId, request.version(), operator);
    }

    @GetMapping(VIDEO + "/changes")
    public List<OpsChangeView> videoChanges(Authentication authentication, @PathVariable UUID videoId) {
        actors.operator(authentication);
        return service.videoChanges(videoId);
    }

    // ---- 专题 ----

    @GetMapping(TOPICS)
    public OpsTopicPageView topics(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.topics(keyword, cityCode, status, page, size);
    }

    @PostMapping(TOPICS)
    public ResponseEntity<OpsTopicView> createTopic(Authentication authentication, @RequestBody TopicRequest request) {
        Operator operator = operator(authentication);
        return ResponseEntity.status(201).body(service.createTopic(request.draft(), operator));
    }

    @GetMapping(TOPIC)
    public OpsTopicView topic(Authentication authentication, @PathVariable UUID topicId) {
        actors.operator(authentication);
        return service.topic(topicId);
    }

    @PutMapping(TOPIC)
    public OpsTopicView updateTopic(Authentication authentication, @PathVariable UUID topicId,
                                    @RequestBody TopicRequest request) {
        Operator operator = operator(authentication);
        return service.updateTopic(topicId, request.draft(), request.version(), operator);
    }

    @PostMapping(TOPIC + "/publish")
    public OpsTopicView publishTopic(Authentication authentication, @PathVariable UUID topicId,
                                     @RequestBody VersionRequest request) {
        Operator operator = operator(authentication);
        return service.publishTopic(topicId, request.version(), operator);
    }

    @PostMapping(TOPIC + "/offline")
    public OpsTopicView offlineTopic(Authentication authentication, @PathVariable UUID topicId,
                                     @RequestBody VersionRequest request) {
        Operator operator = operator(authentication);
        return service.offlineTopic(topicId, request.version(), operator);
    }

    @GetMapping(TOPIC + "/changes")
    public List<OpsChangeView> topicChanges(Authentication authentication, @PathVariable UUID topicId) {
        actors.operator(authentication);
        return service.topicChanges(topicId);
    }

    private Operator operator(Authentication authentication) {
        OpsActor actor = actors.operator(authentication);
        return new Operator(actor.accountId(), actor.displayName());
    }

    // ---- 请求体（契约里的 *Request；字段按文字收，服务按枚举和长度校验） ----

    /** 图文：新建时 version 不填，修改时必填。作者是平台时填 authorName，否则填 authorOrganizationId。 */
    record ArticleRequest(
            String kind,
            String title,
            String authorKind,
            UUID authorOrganizationId,
            String authorName,
            String cityCode,
            String coverUrl,
            Boolean sample,
            List<BlockRequest> blocks,
            List<RefRequest> refs,
            Integer version
    ) {

        ArticleDraft draft() {
            return new ArticleDraft(kind, title, new AuthorDraft(authorKind, authorOrganizationId, authorName),
                    cityCode, coverUrl, sample,
                    blocks == null ? null : blocks.stream().map(block -> block == null ? null : block.draft()).toList(),
                    RefRequest.drafts(refs));
        }
    }

    record BlockRequest(
            String type,
            String text,
            List<BlockItemRequest> items,
            String imageUrl,
            String imageAlt,
            String imageCaption
    ) {

        BlockDraft draft() {
            return new BlockDraft(type, text, items == null ? null : items.stream()
                    .map(item -> item == null ? null : new BlockItemDraft(item.title(), item.text()))
                    .toList(), imageUrl, imageAlt, imageCaption);
        }
    }

    record BlockItemRequest(String title, String text) {
    }

    record RefRequest(String type, UUID refId, String title) {

        static List<RefDraft> drafts(List<RefRequest> refs) {
            return refs == null ? null : refs.stream()
                    .map(ref -> ref == null ? null : new RefDraft(ref.type(), ref.refId(), ref.title()))
                    .toList();
        }
    }

    record VideoRequest(
            String title,
            String authorKind,
            UUID authorOrganizationId,
            String authorName,
            String cityCode,
            String coverUrl,
            Boolean sample,
            Integer durationSeconds,
            String orientation,
            String playUrl,
            String caption,
            List<String> tags,
            Integer rank,
            List<RefRequest> refs,
            Integer version
    ) {

        VideoDraft draft() {
            return new VideoDraft(title, new AuthorDraft(authorKind, authorOrganizationId, authorName), cityCode,
                    coverUrl, sample, durationSeconds, orientation, playUrl, caption, tags, rank,
                    RefRequest.drafts(refs));
        }
    }

    record TopicRequest(
            String title,
            String subtitle,
            String publisherKind,
            UUID publisherOrganizationId,
            String publisherName,
            String cityCode,
            String startsOn,
            String endsOn,
            String coverUrl,
            Boolean sample,
            List<UUID> activityIds,
            List<UUID> venueIds,
            List<UUID> articleIds,
            List<MerchantItemRequest> merchantItems,
            Integer version
    ) {

        TopicDraft draft() {
            return new TopicDraft(title, subtitle,
                    new AuthorDraft(publisherKind, publisherOrganizationId, publisherName), cityCode, startsOn,
                    endsOn, coverUrl, sample, activityIds, venueIds, articleIds,
                    merchantItems == null ? null : merchantItems.stream()
                            .map(item -> item == null ? null
                                    : new MerchantItemDraft(item.productId(), item.merchantOrganizationId()))
                            .toList());
        }
    }

    record MerchantItemRequest(UUID productId, UUID merchantOrganizationId) {
    }

    /** 上线：publishAt 不填为现在，可以定在以后（最远一年）。 */
    record PublishRequest(Integer version, OffsetDateTime publishAt) {
    }

    record VersionRequest(Integer version) {
    }
}
