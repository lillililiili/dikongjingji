package com.lowaltitude.inspection.content.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.lowaltitude.inspection.content.application.ops.ContentOpsCodes;
import com.lowaltitude.inspection.content.application.ops.ContentOpsError;
import com.lowaltitude.inspection.content.application.ops.ContentOpsService;
import com.lowaltitude.inspection.content.application.ops.ContentOpsViews;
import com.lowaltitude.inspection.ops.OpsContractChecker;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import org.junit.jupiter.api.Test;

/** 契约 contracts/openapi-ops-content.yaml 和 OpsContentController 逐项对照（S3-1 运营端“发现内容”）。 */
class OpsContentOpenApiContractTest {

    private final OpsContractChecker checker = new OpsContractChecker("openapi-ops-content.yaml",
            OpsContentController.class)
            .response("OpsContentAuthor", ContentOpsViews.OpsContentAuthorView.class)
            .response("OpsBlockItem", ContentOpsViews.OpsBlockItemView.class)
            .response("OpsBlockImage", ContentOpsViews.OpsBlockImageView.class)
            .response("OpsBlock", ContentOpsViews.OpsBlockView.class)
            .response("OpsRef", ContentOpsViews.OpsRefView.class)
            .response("OpsArticle", ContentOpsViews.OpsArticleView.class)
            .response("OpsVideo", ContentOpsViews.OpsVideoView.class)
            .response("OpsTopicMerchantItem", ContentOpsViews.OpsTopicMerchantItemView.class)
            .response("OpsTopic", ContentOpsViews.OpsTopicView.class)
            .response("OpsContentSummary", ContentOpsViews.OpsContentSummaryView.class)
            .response("OpsContentPage", ContentOpsViews.OpsContentPageView.class)
            .response("OpsTopicSummary", ContentOpsViews.OpsTopicSummaryView.class)
            .response("OpsTopicPage", ContentOpsViews.OpsTopicPageView.class)
            .response("OpsChange", OpsChangeView.class)
            .request("OpsArticleRequest", OpsContentController.ArticleRequest.class, "kind", "title", "authorKind")
            .request("OpsBlockRequest", OpsContentController.BlockRequest.class, "type")
            .request("OpsBlockItemRequest", OpsContentController.BlockItemRequest.class, "title")
            .request("OpsRefRequest", OpsContentController.RefRequest.class, "type", "refId", "title")
            .request("OpsVideoRequest", OpsContentController.VideoRequest.class, "title", "authorKind", "cityCode",
                    "durationSeconds", "orientation")
            .request("OpsTopicRequest", OpsContentController.TopicRequest.class, "title", "publisherKind", "cityCode",
                    "startsOn", "endsOn")
            .request("OpsMerchantItemRequest", OpsContentController.MerchantItemRequest.class, "productId",
                    "merchantOrganizationId")
            .request("OpsPublishRequest", OpsContentController.PublishRequest.class, "version")
            .request("OpsVersionRequest", OpsContentController.VersionRequest.class, "version")
            .requestEnumField("OpsArticleRequest.kind", "OpsArticleKind")
            .requestEnumField("OpsArticleRequest.authorKind", "OpsAuthorKind")
            .requestEnumField("OpsBlockRequest.type", "OpsBlockType")
            .requestEnumField("OpsRefRequest.type", "OpsRefType")
            .requestEnumField("OpsVideoRequest.authorKind", "OpsAuthorKind")
            .requestEnumField("OpsVideoRequest.orientation", "OpsVideoOrientation")
            .requestEnumField("OpsTopicRequest.publisherKind", "OpsAuthorKind")
            .enumeration("OpsArticleKind", ContentOpsCodes.OpsArticleKind.class)
            .enumeration("OpsAuthorKind", ContentOpsCodes.OpsAuthorKind.class)
            .enumeration("OpsContentStatus", ContentOpsCodes.OpsContentStatus.class)
            .enumeration("OpsBlockType", ContentOpsCodes.OpsBlockType.class)
            .enumeration("OpsRefType", ContentOpsCodes.OpsRefType.class)
            .enumeration("OpsVideoOrientation", ContentOpsCodes.OpsVideoOrientation.class)
            .other("ApiError", "OpsContentErrorCode", "OpsContentMissingField");

    @Test
    void contractMatchesTheController() {
        checker.verifyAll("1.0.0");
        checker.verifyOpsErrors("OpsContentErrorCode", ContentOpsError.values());
    }

    @Test
    void missingFieldsAndChangeCodesAreTheOnesTheServiceUses() {
        assertThat(OpsContractChecker.strings(OpsContractChecker.map(checker.schemas().get("OpsContentMissingField"))
                .get("enum"))).containsExactlyInAnyOrder(ContentOpsService.MISSING_AUTHOR,
                ContentOpsService.MISSING_PUBLISHER, ContentOpsService.MISSING_COVER, ContentOpsService.MISSING_BLOCKS,
                ContentOpsService.MISSING_IMAGE, ContentOpsService.MISSING_PLAY_URL, ContentOpsService.MISSING_ITEMS);
        Map<String, Object> change = OpsContractChecker.map(OpsContractChecker.map(checker.schemas().get("OpsChange"))
                .get("properties"));
        assertThat(OpsContractChecker.strings(OpsContractChecker.map(change.get("subjectType")).get("enum")))
                .containsExactlyElementsOf(names(ContentOpsCodes.OpsContentSubject.values()));
        assertThat(OpsContractChecker.strings(OpsContractChecker.map(change.get("action")).get("enum")))
                .containsExactlyElementsOf(names(ContentOpsCodes.OpsContentAction.values()));
    }

    @Test
    void limitsAreTheOnesTheServiceEnforces() {
        Map<String, Object> block = OpsContractChecker.map(OpsContractChecker.map(checker.schemas()
                .get("OpsBlockRequest")).get("properties"));
        assertThat(OpsContractChecker.map(block.get("text")).get("maxLength")).isEqualTo(ContentOpsService.MAX_BLOCK_TEXT);
        assertThat(OpsContractChecker.map(block.get("imageAlt")).get("maxLength"))
                .isEqualTo(ContentOpsService.MAX_IMAGE_ALT);
        assertThat(OpsContractChecker.map(block.get("imageCaption")).get("maxLength"))
                .isEqualTo(ContentOpsService.MAX_IMAGE_CAPTION);
        assertThat(OpsContractChecker.map(block.get("items")).get("maxItems")).isEqualTo(ContentOpsService.MAX_BLOCK_ITEMS);
        Map<String, Object> article = OpsContractChecker.map(OpsContractChecker.map(checker.schemas()
                .get("OpsArticleRequest")).get("properties"));
        assertThat(OpsContractChecker.map(article.get("title")).get("maxLength")).isEqualTo(ContentOpsService.MAX_TITLE);
        assertThat(OpsContractChecker.map(article.get("blocks")).get("maxItems")).isEqualTo(ContentOpsService.MAX_BLOCKS);
        assertThat(OpsContractChecker.map(article.get("refs")).get("maxItems")).isEqualTo(ContentOpsService.MAX_REFS);
        Map<String, Object> video = OpsContractChecker.map(OpsContractChecker.map(checker.schemas()
                .get("OpsVideoRequest")).get("properties"));
        assertThat(OpsContractChecker.map(video.get("durationSeconds")).get("maximum"))
                .isEqualTo(ContentOpsService.MAX_VIDEO_SECONDS);
        assertThat(OpsContractChecker.map(video.get("tags")).get("maxItems")).isEqualTo(ContentOpsService.MAX_TAGS);
        assertThat(OpsContractChecker.map(video.get("rank")).get("maximum")).isEqualTo(ContentOpsService.MAX_RANK);
        Map<String, Object> topic = OpsContractChecker.map(OpsContractChecker.map(checker.schemas()
                .get("OpsTopicRequest")).get("properties"));
        assertThat(OpsContractChecker.map(topic.get("title")).get("maxLength")).isEqualTo(ContentOpsService.MAX_TOPIC_TITLE);
        assertThat(OpsContractChecker.map(topic.get("merchantItems")).get("maxItems"))
                .isEqualTo(ContentOpsService.MAX_TOPIC_MERCHANT_ITEMS);
        assertThat(OpsContractChecker.map(topic.get("activityIds")).get("maxItems"))
                .isEqualTo(ContentOpsService.MAX_TOPIC_ACTIVITIES);
        assertThat(OpsContractChecker.map(topic.get("venueIds")).get("maxItems")).isEqualTo(ContentOpsService.MAX_TOPIC_VENUES);
        assertThat(OpsContractChecker.map(topic.get("articleIds")).get("maxItems"))
                .isEqualTo(ContentOpsService.MAX_TOPIC_ARTICLES);
        assertThat(checker.text()).contains("maximum: " + ContentOpsService.MAX_PAGE_SIZE)
                .contains("maxLength: " + ContentOpsService.MAX_KEYWORD)
                .contains("maxItems: " + ContentOpsService.CHANGE_LIMIT);
    }

    private static List<String> names(Enum<?>[] values) {
        return java.util.Arrays.stream(values).map(Enum::name).toList();
    }
}
