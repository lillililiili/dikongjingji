package com.lowaltitude.inspection.experience.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.ops.VenueOpsError;
import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.common.OpsText;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class OnlineExperienceOpsService {
    private static final String COLUMNS = """
            SELECT i.id, i.type, i.title, i.summary, i.cover_url, i.login_required, i.sort_order, i.status,
                   i.version, i.product_id, p.name AS product_name, i.simulation_template, i.trial_minutes,
                   i.video_url, c.id AS course_id, c.title AS course_name
            FROM experience_item i
            LEFT JOIN mall_product p ON p.id=i.product_id
            LEFT JOIN course c ON c.experience_item_id=i.id
            """;
    private final JdbcClient db;
    public OnlineExperienceOpsService(JdbcClient db) { this.db = db; }

    public enum Type { SIMULATION, RECORDED_DEMO, SOFTWARE_TRIAL, COURSE_TRIAL }
    public enum Status { DRAFT, PUBLISHED, OFFLINE }
    public enum Target { PRODUCT, COURSE, VIDEO }
    public record Item(UUID id, Type type, String title, String summary, String coverUrl, boolean loginRequired,
                       int sortOrder, Status status, int version, UUID productId, String productName,
                       String simulationTemplate, Integer trialMinutes, String videoUrl, UUID courseId, String courseName) {}
    public record Input(Type type, String title, String summary, String coverUrl, Boolean loginRequired,
                        Integer sortOrder, Status status, Integer version, UUID productId, String simulationTemplate,
                        Integer trialMinutes, String videoUrl, UUID courseId, UUID previousCourseId) {}
    public record Page(List<Item> items, long total, int page, int size, boolean hasMore) {}
    public record Choice(UUID id, String name, String mediaUrl) {}
    public record Change(int version, String action, String operatorName, OffsetDateTime changedAt) {}

    public Page list(String keyword, Status status, Type type, int page, int size) {
        OpsText.page(page, size, 50);
        String q = Objects.toString(OpsText.keyword(keyword, 60), "");
        String where = """
                WHERE i.status <> 'DELETED' AND i.title ILIKE :q
                  AND (:status='' OR i.status=:status) AND (:type='' OR i.type=:type)
                """;
        var params = java.util.Map.of("q", "%"+q+"%", "status", Objects.toString(status,""), "type", Objects.toString(type,""));
        long total = db.sql("SELECT count(*) FROM experience_item i " + where).params(params).query(Long.class).single();
        var items = db.sql(COLUMNS + where + " ORDER BY i.sort_order, i.id LIMIT :size OFFSET :offset")
                .params(params).param("size",size).param("offset",(long)(page-1)*size).query(Item.class).list();
        return new Page(items,total,page,size,(long)page*size < total);
    }

    public List<Choice> choices(Target target, String keyword) {
        String q = Objects.toString(OpsText.keyword(keyword, 60), "");
        String sql = switch (target) {
            case PRODUCT -> "SELECT id,name,NULL::text AS media_url FROM mall_product WHERE status='PUBLISHED' AND name ILIKE :q ORDER BY name,id";
            case COURSE -> "SELECT id,title AS name,NULL::text AS media_url FROM course WHERE delivery_mode='ONLINE' AND status='LISTED' AND title ILIKE :q ORDER BY title,id";
            case VIDEO -> """
                    SELECT id,title AS name,video_url AS media_url FROM content_article
                    WHERE kind='VIDEO' AND status='PUBLISHED' AND published_at <= CURRENT_TIMESTAMP
                      AND city_code IS NOT NULL AND video_url LIKE 'https://%' AND title ILIKE :q ORDER BY title,id
                    """;
        };
        return db.sql(sql+" LIMIT 40").param("q","%"+q+"%").query(Choice.class).list();
    }

    public List<Change> changes(UUID id) {
        item(id);
        return db.sql("SELECT version,action,operator_name,changed_at FROM experience_item_change WHERE experience_id=? ORDER BY version DESC")
                .param(id).query(Change.class).list();
    }
    public Item item(UUID id) {
        return db.sql(COLUMNS+" WHERE i.id=? AND i.status <> 'DELETED'").param(id)
                .query(Item.class).optional().orElseThrow(() -> new OpsException(VenueOpsError.NOT_FOUND));
    }

    @Transactional
    public Item save(UUID id, Input input, OpsActor actor) {
        if (input == null || input.type()==null || input.status()==null || input.loginRequired()==null
                || input.sortOrder()==null || input.sortOrder()<0 || input.sortOrder()>100_000) {
            throw new IllegalArgumentException("invalid experience fields");
        }
        Item old = db.sql(COLUMNS+" WHERE i.id=? AND i.status <> 'DELETED' FOR UPDATE OF i")
                .param(id).query(Item.class).optional().orElse(null);
        if ((old == null && input.version()!=null) || old!=null && (!Objects.equals(old.version(),input.version())
                || !Objects.equals(old.courseId(),input.previousCourseId()))) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        if (old == null && input.status()!=Status.DRAFT || old!=null && old.status()!=Status.DRAFT && input.status()==Status.DRAFT) {
            throw new OpsException(VenueOpsError.STATUS_CONFLICT);
        }
        String title = OpsText.required(input.title(),120,"title");
        String summary = OpsText.multiline(input.summary(),200,"summary");
        String cover = OpsText.mediaUrl(input.coverUrl(),old==null ? null : old.coverUrl(),"coverUrl");
        String video = OpsText.mediaUrl(input.videoUrl(),old==null ? null : old.videoUrl(),"videoUrl");
        String template = OpsText.optional(input.simulationTemplate(),24,"simulationTemplate");
        if (template!=null && (input.type()!=Type.SIMULATION || !List.of("ROBOT_OBSTACLE","DRONE_HOVER").contains(template))
                || input.trialMinutes()!=null && (input.type()!=Type.COURSE_TRIAL && input.type()!=Type.SOFTWARE_TRIAL
                || input.trialMinutes()<1 || input.trialMinutes()>43200)
                || video!=null && input.type()!=Type.RECORDED_DEMO || input.courseId()!=null && input.type()!=Type.COURSE_TRIAL) {
            throw new IllegalArgumentException("fields do not match experience type");
        }
        if (old!=null && input.type()!=old.type() && (old.status()!=Status.DRAFT || db.sql("""
                SELECT EXISTS(SELECT 1 FROM experience_record WHERE experience_id=?) OR
                       EXISTS(SELECT 1 FROM course WHERE simulation_item_id=?)
                """).params(id,id).query(Boolean.class).single())) throw new OpsException(VenueOpsError.STATUS_CONFLICT);
        if (input.productId()!=null && (old==null || !Objects.equals(input.productId(),old.productId()) || input.status()==Status.PUBLISHED)
                && !db.sql("SELECT EXISTS(SELECT 1 FROM mall_product WHERE id=? AND status='PUBLISHED')")
                    .param(input.productId()).query(Boolean.class).single()) throw new OpsException(VenueOpsError.PRODUCT_UNKNOWN);

        if (input.status()==Status.PUBLISHED) {
            var missing = new java.util.ArrayList<String>();
            if (cover==null) missing.add("coverUrl");
            if (summary==null) missing.add("summary");
            if (input.type()==Type.SIMULATION && template==null) missing.add("simulationTemplate");
            if (input.type()==Type.RECORDED_DEMO && video==null) missing.add("videoUrl");
            if ((input.type()==Type.SOFTWARE_TRIAL || input.type()==Type.COURSE_TRIAL) && input.trialMinutes()==null) missing.add("trialMinutes");
            if (input.type()==Type.COURSE_TRIAL && (input.courseId()==null || !db.sql("""
                    SELECT EXISTS (SELECT 1 FROM course c JOIN course_lesson l ON l.course_id=c.id
                      WHERE c.id=? AND c.status='LISTED' AND c.delivery_mode='ONLINE' AND l.trial AND l.video_url IS NOT NULL)
                    """).param(input.courseId()).query(Boolean.class).single())) missing.add("courseId");
            if (!missing.isEmpty()) throw OpsException.incomplete(VenueOpsError.PUBLISH_INCOMPLETE, missing);
        }
        String mutation = old==null ? """
                INSERT INTO experience_item(id,type,title,summary,cover_url,login_required,sort_order,status,
                    product_id,simulation_template,trial_minutes,video_url)
                VALUES(:id,:type,:title,:summary,:cover,:login,:sort,:status,:product,:template,:minutes,:video)
                ON CONFLICT (id) DO NOTHING
                """ : """
                UPDATE experience_item SET type=:type,title=:title,summary=:summary,cover_url=:cover,
                    login_required=:login,sort_order=:sort,status=:status,product_id=:product,
                    simulation_template=:template,trial_minutes=:minutes,video_url=:video,
                    version=version+1,updated_at=now() WHERE id=:id
                """;
        int written = db.sql(mutation).param("id",id).param("type",input.type().name()).param("title",title)
                .param("summary",summary).param("cover",cover).param("login",input.loginRequired())
                .param("sort",input.sortOrder()).param("status",input.status().name()).param("product",input.productId())
                .param("template",template).param("minutes",input.trialMinutes()).param("video",video).update();
        if (written!=1) throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        if (!Objects.equals(input.previousCourseId(),input.courseId())) {
            if (input.previousCourseId()!=null) changeCourse(input.previousCourseId(),id,false,actor);
            if (input.courseId()!=null) changeCourse(input.courseId(),id,true,actor);
        }
        String action = old==null ? "CREATE" : old.status()==input.status() ? "UPDATE"
                : input.status()==Status.PUBLISHED ? "PUBLISH" : "OFFLINE";
        db.sql("INSERT INTO experience_item_change(experience_id,version,action,operator_id,operator_name) VALUES(?,?,?,?,?)")
                .params(id,old==null ? 0 : old.version()+1,action,actor.accountId(),actor.displayName()).update();
        return item(id);
    }

    private void changeCourse(UUID course, UUID item, boolean link, OpsActor actor) {
        String mutation = link
                ? "UPDATE course SET experience_item_id=:item, version=version+1, updated_at=now() WHERE id=:course AND delivery_mode='ONLINE' AND status='LISTED' AND experience_item_id IS NULL"
                : "UPDATE course SET experience_item_id=NULL, version=version+1, updated_at=now() WHERE id=:course AND experience_item_id=:item";
        if (db.sql(mutation).param("item",item).param("course",course).update()!=1) {
            throw new OpsException(VenueOpsError.VERSION_CONFLICT);
        }
        db.sql("INSERT INTO course_ops_change(course_id,action,summary,operator_id,operator_name,changed_at) VALUES(?,'UPDATE',?,?,?,now())")
                .params(course,link ? "绑定线上试听项目" : "解除线上试听项目关联",actor.accountId(),actor.displayName()).update();
    }
}
