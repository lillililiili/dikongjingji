package com.lowaltitude.inspection.ops.media;

import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Serves local operator media only while a public business record displays it. */
@RestController
@Profile("dev")
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public final class DevelopmentOpsMediaController {
    // Each branch selects an image that the corresponding public detail, feed or carousel actually returns.
    // A lesson's recording, course material and the video body's URL are never display images.
    static final String DISPLAYED = """
            WITH urls(url) AS (VALUES (CAST(? AS text)), (CAST(? AS text)))
            SELECT EXISTS (
                SELECT 1 FROM content_home_slide WHERE image_ref = ? AND status = 'PUBLISHED'
                UNION ALL
                SELECT 1 FROM content_article WHERE cover_url IN (SELECT url FROM urls)
                    AND status = 'PUBLISHED' AND published_at <= CURRENT_TIMESTAMP
                UNION ALL
                SELECT 1 FROM content_article_block b JOIN content_article a ON a.id = b.article_id
                    WHERE b.image_url IN (SELECT url FROM urls) AND b.block_type = 'IMAGE'
                    AND a.kind IN ('CASE', 'KNOWLEDGE') AND a.status = 'PUBLISHED' AND a.published_at <= CURRENT_TIMESTAMP
                UNION ALL
                SELECT 1 FROM content_topic WHERE cover_url IN (SELECT url FROM urls) AND status = 'PUBLISHED'
                UNION ALL
                SELECT 1 FROM activity WHERE cover_url IN (SELECT url FROM urls) AND status = 'PUBLISHED'
                UNION ALL
                SELECT 1 FROM activity_gallery_image g JOIN activity a ON a.id = g.activity_id
                    WHERE g.url IN (SELECT url FROM urls) AND a.status = 'PUBLISHED'
                UNION ALL
                SELECT 1 FROM venue WHERE cover_url IN (SELECT url FROM urls) AND status = 'PUBLISHED'
                UNION ALL
                SELECT 1 FROM venue_device d JOIN venue v ON v.id = d.venue_id
                    WHERE d.image_url IN (SELECT url FROM urls) AND d.status = 'ACTIVE' AND v.status = 'PUBLISHED'
                UNION ALL
                SELECT 1 FROM experience_item WHERE cover_url IN (SELECT url FROM urls) AND status = 'PUBLISHED'
                UNION ALL
                SELECT 1 FROM course c WHERE c.status = 'LISTED' AND c.delivery_mode = 'ONLINE'
                    AND (c.cover_url IN (SELECT url FROM urls)
                         OR EXISTS (SELECT 1 FROM urls WHERE c.gallery_urls @> jsonb_build_array(urls.url)))
                UNION ALL
                SELECT 1 FROM pro_service s JOIN pro_service_city c ON c.service_id = s.id
                    WHERE s.cover_url IN (SELECT url FROM urls) AND s.status = 'PUBLISHED' AND c.status = 'ACTIVE'
                UNION ALL
                SELECT 1 FROM inspection_portal_content p WHERE p.content_type='HERO' AND p.status='ACTIVE'
                    AND p.image_url IN (SELECT url FROM urls)
                    AND EXISTS (
                        SELECT 1 FROM inspection_service_city c
                        JOIN platform_city city ON city.city_code=c.city_code AND city.status='ACTIVE'
                        JOIN inspection_service s ON s.id=c.service_id AND s.status='ACTIVE'
                        WHERE c.status='ACTIVE' AND s.service_no IS NOT NULL AND s.category_code IS NOT NULL
                          AND s.category_name IS NOT NULL AND s.summary IS NOT NULL AND s.price_mode IS NOT NULL
                          AND s.currency IS NOT NULL AND s.availability IS NOT NULL AND s.primary_action IS NOT NULL
                          AND (s.price_mode='PROJECT_QUOTE' OR s.starting_amount_minor IS NOT NULL)
                          AND EXISTS (SELECT 1 FROM inspection_service_booking_item i
                                      WHERE i.service_id=s.id AND i.status='ACTIVE')
                          AND (p.city_code=c.city_code OR (p.city_code IS NULL AND NOT EXISTS (
                              SELECT 1 FROM inspection_portal_content own WHERE own.content_type='HERO'
                              AND own.city_code=c.city_code AND own.status='ACTIVE')))
                    )
                UNION ALL
                SELECT 1 FROM inspection_service s WHERE (s.cover_url IN (SELECT url FROM urls)
                    OR EXISTS (SELECT 1 FROM inspection_service_detail_item d WHERE d.service_id=s.id
                        AND d.section_type='DELIVERABLE' AND d.status='ACTIVE' AND d.image_url IN (SELECT url FROM urls)))
                    AND s.status = 'ACTIVE' AND s.service_no IS NOT NULL AND s.category_code IS NOT NULL
                    AND s.category_name IS NOT NULL AND s.summary IS NOT NULL AND s.price_mode IS NOT NULL
                    AND s.currency IS NOT NULL AND s.availability IS NOT NULL AND s.primary_action IS NOT NULL
                    AND (s.price_mode = 'PROJECT_QUOTE' OR s.starting_amount_minor IS NOT NULL)
                    AND EXISTS (SELECT 1 FROM inspection_service_city c WHERE c.service_id = s.id AND c.status = 'ACTIVE')
                    AND (EXISTS (SELECT 1 FROM inspection_service_booking_item i WHERE i.service_id = s.id AND i.status = 'ACTIVE')
                         OR EXISTS (SELECT 1 FROM identity_organization p WHERE p.id = s.provider_id
                                        AND p.organization_type = 'PROVIDER' AND p.status = 'ACTIVE'))
            )
            """;

    static final String PUBLISHED_VIDEO = """
            WITH urls(url) AS (VALUES (CAST(? AS text)), (CAST(? AS text)))
            SELECT EXISTS (
                SELECT 1 FROM content_article WHERE kind = 'VIDEO' AND status = 'PUBLISHED'
                  AND published_at <= CURRENT_TIMESTAMP AND city_code IS NOT NULL AND video_url IN (SELECT url FROM urls)
                UNION ALL
                SELECT 1 FROM experience_item WHERE type = 'RECORDED_DEMO' AND status = 'PUBLISHED'
                  AND video_url IN (SELECT url FROM urls)
            )
            """;

    private final OpsMediaStore store;
    private final JdbcTemplate database;

    public DevelopmentOpsMediaController(OpsMediaStore store, JdbcTemplate database) {
        this.store = store;
        this.database = database;
    }

    @GetMapping("/api/v1/dev-media/ops/covers/{fileName}")
    public ResponseEntity<Resource> cover(@PathVariable String fileName) throws IOException {
        return published(fileName, false);
    }

    @GetMapping("/api/v1/dev-media/ops/videos/{fileName}")
    public ResponseEntity<Resource> video(@PathVariable String fileName) throws IOException {
        return published(fileName, true);
    }

    private ResponseEntity<Resource> published(String fileName, boolean video) throws IOException {
        String key = "ops-media/" + fileName;
        if (!(store instanceof LocalOpsMediaStore local) || !OpsMediaService.KEY.matcher(key).matches()
                || fileName.endsWith(".mp4") != video) {
            return missing();
        }
        // Keep images from before a local public-base-url change usable under their original stored URL.
        String configuredUrl = local.publicUrl(key);
        String originalUrl = "https://media.dev.localhost/" + key;
        Boolean visible = video ? database.queryForObject(PUBLISHED_VIDEO, Boolean.class, configuredUrl, originalUrl)
                : database.queryForObject(DISPLAYED, Boolean.class, configuredUrl, originalUrl, key);
        if (!Boolean.TRUE.equals(visible)) {
            return missing();
        }
        Resource file = local.localResource(key);
        if (file == null) return missing();
        MediaType type = switch (fileName.substring(fileName.lastIndexOf('.') + 1)) {
            case "mp4" -> MediaType.parseMediaType("video/mp4");
            case "png" -> MediaType.IMAGE_PNG;
            case "webp" -> MediaType.parseMediaType("image/webp");
            default -> MediaType.IMAGE_JPEG;
        };
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(file);
    }

    private static ResponseEntity<Resource> missing() {
        return ResponseEntity.status(404).cacheControl(CacheControl.noStore()).build();
    }
}
