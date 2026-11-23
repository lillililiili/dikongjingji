package com.lowaltitude.inspection.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoritePorts;
import org.junit.jupiter.api.Test;

/** 收藏列表里的内容（计划 08 S5-6）：先按图文读，读不到再按视频读；都读不到就是下线了。 */
class ContentFavoriteTargetsTest {

    private static final UUID ID = UUID.fromString("d1000000-0000-4000-8000-000000000001");
    private final ContentReadService articles = mock(ContentReadService.class);
    private final ContentVideoService videos = mock(ContentVideoService.class);
    private final FavoritePorts.TargetReader reader = ContentFavoriteTargets.reader(articles, videos);
    private final ContentAuthorView author = new ContentAuthorView(ContentAuthorKind.MERCHANT, UUID.randomUUID(),
            "示例机器人商家");

    @Test
    void anArticleIsTaggedByItsKind() {
        when(articles.article(ID, "DONGYING")).thenReturn(new ContentArticleView(ID, ContentArticleKind.CASE,
                "一台机器狗如何完成园区夜间巡逻", author, true, "DONGYING", null, true, OffsetDateTime.now(), List.of(),
                List.of()));

        assertThat(reader.find(ID, "DONGYING")).hasValueSatisfying(target -> {
            assertThat(target.title()).isEqualTo("一台机器狗如何完成园区夜间巡逻");
            assertThat(target.tag()).isEqualTo("案例");
            assertThat(target.meta()).isEqualTo("示例机器人商家");
            assertThat(target.price()).isNull();
        });
    }

    @Test
    void aVideoIsReadWhenItIsNotAnArticle() {
        when(articles.article(ID, "DONGYING")).thenThrow(new ContentNotFoundException());
        when(videos.video(ID, "DONGYING", null)).thenReturn(new ContentVideoView(ID, "夜间巡逻实拍", author, false,
                "DONGYING", new ContentMediaView("https://img.example.com/v.jpg", "封面"), true, OffsetDateTime.now(), 46,
                ContentVideoOrientation.PORTRAIT, "https://video.example.com/v.mp4", null, List.of(), List.of(), 0, 0,
                false, false));

        assertThat(reader.find(ID, "DONGYING")).hasValueSatisfying(target -> {
            assertThat(target.tag()).isEqualTo("视频");
            assertThat(target.coverUrl()).isEqualTo("https://img.example.com/v.jpg");
        });
    }

    @Test
    void contentThatIsNeitherIsNotFound() {
        when(articles.article(ID, "DONGYING")).thenThrow(new ContentNotFoundException());
        when(videos.video(ID, "DONGYING", null)).thenThrow(new ContentNotFoundException());

        assertThat(reader.find(ID, "DONGYING")).isEmpty();
    }
}
