package com.lowaltitude.inspection.ops.pagecontent;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsError;
import com.lowaltitude.inspection.media.DevelopmentMediaPath;
import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.common.OpsText;
import com.lowaltitude.inspection.ops.media.OpsMediaService;
import com.lowaltitude.inspection.ops.media.OpsMediaStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class PageContentOpsService {
    private static final Pattern UPLOAD = Pattern.compile("/(ops-media/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.(?:jpg|webp|png))$");
    private static final String SLIDES = """
            SELECT city_code, id, tag, title_first, title_second, description_first, description_second, action_label,
                   image_ref, image_alt, tone, target, status, sort_order, version FROM content_home_slide
            """;
    private static final String PORTAL = """
            SELECT id, city_code, content_type, item_code, title, subtitle, highlighted_text, image_url, status,
                   sort_order, version FROM inspection_portal_content
            """;
    private final JdbcClient db;
    private final OpsMediaStore media;

    public PageContentOpsService(JdbcClient db, OpsMediaStore media) {
        this.db = db;
        this.media = media;
    }

    public record Page<T>(List<T> items, int total, int page, int size, boolean hasMore) {}
    public record Change(int version, String operatorName, String status, OffsetDateTime changedAt) {}
    public record SlideInput(String tag, String titleFirst, String titleSecond, String descriptionFirst,
                             String descriptionSecond, String actionLabel, String imageUrl, String imageAlt,
                             String tone, String target, String status, Integer sortOrder, Integer version) {}
    public record Slide(String cityCode, String id, String tag, String titleFirst, String titleSecond,
                        String descriptionFirst, String descriptionSecond, String actionLabel, String imageUrl,
                        String imageAlt, String tone, String target, String status, int sortOrder, int version) {}
    public record PortalInput(String cityCode, String contentType, String itemCode, String title, String subtitle,
                              String highlightedText, String imageUrl, String status, Integer sortOrder, Integer version) {}
    public record Portal(UUID id, String cityCode, String contentType, String itemCode, String title, String subtitle,
                         String highlightedText, String imageUrl, String status, int sortOrder, int version) {}

    public Page<Slide> slides(String city, int page, int size) {
        OpsText.page(page, size, 100);
        String filter = city == null || city.isBlank() ? "" : " WHERE city_code=:city";
        String code = filter.isEmpty() ? "" : cityCode(city);
        int count = db.sql("SELECT count(*) FROM content_home_slide" + filter).param("city", code)
                .query(Integer.class).single();
        var items = db.sql(SLIDES + filter + " ORDER BY city_code, sort_order, id LIMIT :limit OFFSET :start")
                .param("city", code).param("limit", size).param("start", (long)(page - 1) * size)
                .query((rs, n) -> new Slide(rs.getString("city_code"), rs.getString("id"), rs.getString("tag"),
                        rs.getString("title_first"), rs.getString("title_second"), rs.getString("description_first"),
                        rs.getString("description_second"), rs.getString("action_label"), imageUrl(rs.getString("image_ref")),
                        rs.getString("image_alt"), rs.getString("tone"), rs.getString("target"), rs.getString("status"),
                        rs.getInt("sort_order"), rs.getInt("version"))).list();
        return new Page<>(items, count, page, size, (long) page * size < count);
    }

    @Transactional
    public String saveSlide(String city, String id, SlideInput input, OpsActor actor) {
        lockCity(city);
        boolean adding = id == null;
        String slideId = adding ? UUID.randomUUID().toString() : id;
        String currentImage = adding ? null : db.sql("SELECT image_ref FROM content_home_slide WHERE city_code=? AND id=?")
                .params(city, slideId).query(String.class).optional().orElseThrow(PageContentOpsService::missing);
        String status = adding ? "DRAFT" : choice(input.status(), Set.of("DRAFT", "PUBLISHED", "OFFLINE"), "status");
        if ("PUBLISHED".equals(status) && db.sql("""
                SELECT count(*) FROM content_home_slide WHERE city_code=? AND status='PUBLISHED' AND id<>?
                """).params(city, slideId).query(Integer.class).single() >= 12) {
            throw new IllegalArgumentException("每个城市最多同时发布 12 张轮播");
        }
        var sql = adding ? """
                INSERT INTO content_home_slide(city_code,id,tag,title_first,title_second,description_first,
                    description_second,action_label,image_ref,image_alt,tone,target,status,sort_order)
                VALUES(:city,:id,:tag,:title1,:title2,:desc1,:desc2,:action,:image,:alt,:tone,:target,:status,:sort)
                """ : """
                UPDATE content_home_slide SET tag=:tag,title_first=:title1,title_second=:title2,
                    description_first=:desc1,description_second=:desc2,action_label=:action,image_ref=:image,
                    image_alt=:alt,tone=:tone,target=:target,status=:status,sort_order=:sort,version=version+1
                WHERE city_code=:city AND id=:id AND version=:version
                """;
        var statement = db.sql(sql).param("city", city).param("id", slideId)
                .param("tag", OpsText.required(input.tag(), 60, "tag"))
                .param("title1", OpsText.required(input.titleFirst(), 60, "titleFirst"))
                .param("title2", OpsText.required(input.titleSecond(), 60, "titleSecond"))
                .param("desc1", OpsText.required(input.descriptionFirst(), 120, "descriptionFirst"))
                .param("desc2", OpsText.required(input.descriptionSecond(), 120, "descriptionSecond"))
                .param("action", OpsText.required(input.actionLabel(), 60, "actionLabel"))
                .param("image", slideImage(input.imageUrl(), currentImage))
                .param("alt", OpsText.required(input.imageAlt(), 120, "imageAlt"))
                .param("tone", choice(input.tone(), Set.of("SKY","SUN","VIOLET","MINT"), "tone"))
                .param("target", choice(input.target(), Set.of("ACTIVITY","EXPERIENCE","PLAY_GAMES"), "target"))
                .param("status", status).param("sort", sort(input.sortOrder()));
        if (!adding) statement.param("version", version(input.version()));
        if (statement.update() != 1) throw conflict();
        db.sql("""
                INSERT INTO content_home_slide_change(city_code,slide_id,version,operator_id,operator_name,status)
                VALUES(?,?,?,?,?,?)
                """).params(city, slideId, adding ? 0 : input.version() + 1, actor.accountId(),actor.displayName(),status).update();
        return slideId;
    }

    public List<Change> slideChanges(String city, String id) {
        return db.sql("""
                SELECT version,operator_name,status,changed_at FROM content_home_slide_change
                WHERE city_code=? AND slide_id=? ORDER BY version DESC
                """).params(cityCode(city), id).query(Change.class).list();
    }

    public List<Portal> portal(String city) {
        String scope = city == null || city.isBlank() ? null : cityCode(city);
        return db.sql(PORTAL + " WHERE city_code IS NOT DISTINCT FROM CAST(? AS varchar) ORDER BY content_type,sort_order,item_code")
                .param(scope).query(Portal.class).list();
    }

    @Transactional
    public UUID savePortal(UUID id, PortalInput input, OpsActor actor) {
        boolean adding = id == null;
        Portal original = adding ? null : db.sql(PORTAL + " WHERE id=? FOR UPDATE").param(id).query(Portal.class)
                .optional().orElseThrow(PageContentOpsService::missing);
        String city = adding ? OpsText.optional(input.cityCode(), 16, "cityCode") : original.cityCode();
        if (city != null) lockCity(city);
        String type = adding ? choice(input.contentType(), Set.of("HERO","GUARANTEE","PROCESS","SCENE"), "contentType")
                : original.contentType();
        String code = adding ? "HERO".equals(type) ? "DEFAULT" : OpsText.required(input.itemCode(),64,"itemCode")
                : original.itemCode();
        if (!code.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("itemCode is invalid");
        if ("SCENE".equals(type) && !db.sql("""
                SELECT EXISTS(SELECT 1 FROM inspection_category WHERE code=? AND status='ACTIVE' AND parent_code IS NULL)
                """).param(code).query(Boolean.class).single()) throw new OpsException(InspectionCatalogOpsError.CATEGORY_UNKNOWN);
        String status = choice(input.status(), Set.of("ACTIVE","INACTIVE"), "status");
        if (city == null && "HERO".equals(type) && !"ACTIVE".equals(status)) {
            throw new IllegalArgumentException("全国默认横幅须保持启用");
        }
        String image = OpsText.mediaUrl(input.imageUrl(), adding ? null : original.imageUrl(), "imageUrl");
        if (image != null && !"HERO".equals(type)) throw new IllegalArgumentException("只有入口横幅使用图片");
        String highlight = OpsText.optional(input.highlightedText(),64,"highlightedText");
        if (highlight != null && !"HERO".equals(type)) throw new IllegalArgumentException("只有入口横幅使用强调文字");
        UUID contentId = adding ? UUID.randomUUID() : id;
        String sql = adding ? """
                INSERT INTO inspection_portal_content(id,city_code,content_type,item_code,title,subtitle,
                    highlighted_text,image_url,sort_order,status)
                VALUES(:id,:city,:type,:code,:title,:subtitle,:highlight,:image,:sort,:status)
                ON CONFLICT DO NOTHING
                """ : """
                UPDATE inspection_portal_content SET title=:title,subtitle=:subtitle,highlighted_text=:highlight,
                    image_url=:image,sort_order=:sort,status=:status,version=version+1,updated_at=now()
                WHERE id=:id AND version=:version
                """;
        var statement = db.sql(sql).param("id",contentId).param("city",city).param("type",type).param("code",code)
                .param("title",OpsText.required(input.title(),128,"title")).param("subtitle",OpsText.optional(input.subtitle(),256,"subtitle"))
                .param("highlight",highlight).param("image",image).param("sort",sort(input.sortOrder())).param("status",status);
        if (!adding) statement.param("version",version(input.version()));
        if (statement.update() != 1) throw conflict();
        db.sql("INSERT INTO inspection_portal_change(content_id,version,operator_id,operator_name,status) VALUES(?,?,?,?,?)")
                .params(contentId,adding ? 0 : input.version()+1,actor.accountId(),actor.displayName(),status).update();
        return contentId;
    }

    public List<Change> portalChanges(UUID id) {
        return db.sql("SELECT version,operator_name,status,changed_at FROM inspection_portal_change WHERE content_id=? ORDER BY version DESC")
                .param(id).query(Change.class).list();
    }

    public record Deliverable(String code, String title, String imageUrl) {}
    public record DeliverableImage(String imageUrl, Integer version) {}

    public List<Deliverable> deliverables(UUID serviceId) {
        return db.sql("""
                SELECT item_code AS code,title,image_url FROM inspection_service_detail_item
                WHERE service_id=? AND section_type='DELIVERABLE' AND status='ACTIVE' ORDER BY display_order,id
                """).param(serviceId).query(Deliverable.class).list();
    }

    @Transactional
    public void saveDeliverable(UUID serviceId, String code, DeliverableImage input, OpsActor actor) {
        int expected = version(input.version());
        int current = db.sql("SELECT version FROM inspection_service WHERE id=? FOR UPDATE").param(serviceId)
                .query(Integer.class).optional().orElseThrow(PageContentOpsService::missing);
        if (current != expected) throw conflict();
        Deliverable original = deliverables(serviceId).stream().filter(item -> item.code().equals(code)).findFirst()
                .orElseThrow(PageContentOpsService::missing);
        String image = OpsText.mediaUrl(input.imageUrl(), original.imageUrl(), "imageUrl");
        db.sql("""
                UPDATE inspection_service_detail_item SET image_url=?,updated_at=now(),version=version+1
                WHERE service_id=? AND section_type='DELIVERABLE' AND item_code=? AND status='ACTIVE'
                """).param(image).param(serviceId).param(code).update();
        db.sql("UPDATE inspection_service SET version=version+1,updated_at=now() WHERE id=?").param(serviceId).update();
        db.sql("""
                INSERT INTO inspection_catalog_change(subject_type,subject_id,action,summary,operator_id,operator_name,changed_at)
                VALUES('SERVICE',?,'UPDATE',?,?,?,now())
                """).params(serviceId,"更新交付样图 · "+original.title(), actor.accountId(), actor.displayName()).update();
    }

    private String slideImage(String input, String existing) {
        if (existing != null && (input != null && input.equals(imageUrl(existing)))) return existing;
        String url = OpsText.required(input,500,"imageUrl");
        Matcher match = UPLOAD.matcher(url);
        if (match.find() && url.equals(imageUrl(match.group(1)))) return match.group(1);
        throw new IllegalArgumentException("请上传平台图片作为轮播图");
    }

    private String imageUrl(String ref) {
        try {
            return DevelopmentMediaPath.matches(ref) ? ref : media.publicUrl(ref);
        } catch (OpsMediaStore.UnavailableException unavailable) {
            throw new OpsException(OpsMediaService.Error.STORE_UNAVAILABLE);
        }
    }

    private void lockCity(String city) {
        db.sql("SELECT city_code FROM platform_city WHERE city_code=? FOR UPDATE").param(cityCode(city))
                .query(String.class).optional().orElseThrow(() -> new OpsException(InspectionCatalogOpsError.CITY_UNKNOWN));
    }
    private static String cityCode(String city) {
        if (city == null || !city.matches("[0-9]{6}")) throw new IllegalArgumentException("cityCode is invalid");
        return city;
    }
    private static String choice(String value, Set<String> values, String field) {
        if (value == null || !values.contains(value)) throw new IllegalArgumentException(field+" is invalid");
        return value;
    }
    private static int version(Integer value) {
        if (value == null || value < 0) throw new IllegalArgumentException("version is required");
        return value;
    }
    private static int sort(Integer value) {
        if (value == null || value < 0) throw new IllegalArgumentException("sortOrder is invalid");
        return value;
    }
    private static OpsException missing() { return new OpsException(InspectionCatalogOpsError.NOT_FOUND); }
    private static OpsException conflict() { return new OpsException(InspectionCatalogOpsError.VERSION_CONFLICT); }
}
