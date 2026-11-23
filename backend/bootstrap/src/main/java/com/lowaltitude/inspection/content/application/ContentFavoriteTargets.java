package com.lowaltitude.inspection.content.application;

import java.util.Optional;
import java.util.UUID;

import com.lowaltitude.inspection.favorite.application.FavoritePorts;
import com.lowaltitude.inspection.favorite.application.FavoriteTargetType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 收藏列表里的发现页内容（计划 08 S5-6）：先按图文读，读不到再按视频读（同一个内容 ID 只会是其中一种）；都读不到就是下线了
 * 或这个城市看不到。小标签“案例 / 知识 / 视频”，说明是作者；没有价格。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class ContentFavoriteTargets {

    @Bean
    FavoritePorts.TargetReader contentFavoriteReader(ContentReadService articles, ContentVideoService videos) {
        return reader(articles, videos);
    }

    static FavoritePorts.TargetReader reader(ContentReadService articles, ContentVideoService videos) {
        return new FavoritePorts.TargetReader() {
            @Override
            public FavoriteTargetType type() {
                return FavoriteTargetType.CONTENT;
            }

            @Override
            public Optional<FavoritePorts.Target> find(UUID targetId, String cityCode) {
                try {
                    ContentArticleView article = articles.article(targetId, cityCode);
                    return Optional.of(new FavoritePorts.Target(article.title(), author(article.author()),
                            tag(article.kind()), null,
                            article.coverImage() == null ? null : article.coverImage().url(), null));
                } catch (ContentNotFoundException notAnArticle) {
                    // 不是图文，再看是不是视频。
                }
                try {
                    ContentVideoView video = videos.video(targetId, cityCode, null);
                    return Optional.of(new FavoritePorts.Target(video.title(), author(video.author()), "视频", null,
                            video.coverImage() == null ? null : video.coverImage().url(), null));
                } catch (ContentNotFoundException gone) {
                    return Optional.empty();
                }
            }
        };
    }

    private static String tag(ContentArticleKind kind) {
        return switch (kind) {
            case CASE -> "案例";
            case KNOWLEDGE -> "知识";
            case VIDEO -> "视频";
        };
    }

    private static String author(ContentAuthorView author) {
        return author == null ? null : author.name();
    }
}
