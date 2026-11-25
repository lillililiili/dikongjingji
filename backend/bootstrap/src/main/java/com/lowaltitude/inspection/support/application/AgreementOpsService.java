package com.lowaltitude.inspection.support.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.common.OpsError;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.ops.common.OpsText;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
public class AgreementOpsService {
    private static final Set<String> TYPES = Set.of("PRIVACY_POLICY", "USER_AGREEMENT", "CHILD_PRIVACY_RULES", "TRADE_AGREEMENT", "ENROLLMENT_NOTICE");
    private static final String HEADER = """
        SELECT a.id, a.doc, a.version, a.title, coalesce(a.published_on, a.draft_effective_on) effective_on,
               a.revision, CASE
                 WHEN a.published_on IS NULL THEN 'DRAFT'
                 WHEN a.published_on > (now() AT TIME ZONE 'Asia/Shanghai')::date THEN 'SCHEDULED'
                 WHEN EXISTS (SELECT 1 FROM platform_agreement n WHERE n.doc=a.doc AND n.published_on>a.published_on
                   AND n.published_on <= (now() AT TIME ZONE 'Asia/Shanghai')::date
                   AND EXISTS (SELECT 1 FROM platform_agreement_section s WHERE s.agreement_id=n.id)) THEN 'ARCHIVED'
                 ELSE 'EFFECTIVE' END AS status
        FROM platform_agreement a
        """;
    private final JdbcClient db;

    public AgreementOpsService(JdbcClient db) { this.db = db; }

    public record Header(UUID id, String doc, String version, String title, LocalDate effectiveOn, int revision, String status) {}
    public record Section(String title, String body) {}
    public record Change(int revision, String action, String operatorName, OffsetDateTime changedAt) {}
    public record Detail(Header document, List<Section> sections, List<Change> history) {}
    public record Page(List<Header> items, int total, int page, int size, boolean hasMore) {}
    public record Draft(String doc, String version, String title, LocalDate effectiveOn, Integer revision, List<Section> sections) {}

    public Page list(String type, int page, int size) {
        OpsText.page(page, size, 100);
        String doc = type == null || type.isBlank() ? "" : type(type);
        String filter = " WHERE (:doc='' OR a.doc=:doc)";
        int total = db.sql("SELECT count(*) FROM platform_agreement a" + filter).param("doc", doc).query(Integer.class).single();
        var items = db.sql(HEADER + filter + " ORDER BY a.doc, a.published_on DESC NULLS FIRST, a.version DESC, a.id LIMIT :size OFFSET :offset")
                .param("doc", doc).param("size", size).param("offset", (long)(page - 1) * size).query(Header.class).list();
        return new Page(items, total, page, size, (long)page * size < total);
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Detail detail(UUID id) {
        Header document = db.sql(HEADER + " WHERE a.id=?").param(id).query(Header.class).optional()
                .orElseThrow(() -> new OpsException(Error.MISSING));
        List<Section> sections = db.sql("SELECT title, body FROM platform_agreement_section WHERE agreement_id=? ORDER BY position")
                .param(id).query(Section.class).list();
        List<Change> history = db.sql("SELECT revision, action, operator_name, changed_at FROM platform_agreement_change WHERE agreement_id=? ORDER BY revision DESC")
                .param(id).query(Change.class).list();
        return new Detail(document, sections, history);
    }

    @Transactional
    public Detail save(UUID id, Draft input, OpsActor actor) {
        String doc = type(input.doc());
        String version = OpsText.required(input.version(), 40, "version");
        String title = OpsText.required(input.title(), 160, "title");
        if (input.sections() == null || input.sections().size() > 80) throw new IllegalArgumentException("章节应在 80 项以内");
        boolean creating = id == null;
        UUID target = creating ? UUID.randomUUID() : id;
        int revision = 0;
        try {
            if (creating) {
                db.sql("INSERT INTO platform_agreement(id,doc,version,title,draft_effective_on) VALUES(?,?,?,?,?)")
                        .param(target).param(doc).param(version).param(title).param(input.effectiveOn()).update();
            } else {
                Header current = lockDraft(target, input.revision());
                if (!current.doc().equals(doc)) throw new IllegalArgumentException("已有协议不能更换类型");
                revision = current.revision() + 1;
                db.sql("UPDATE platform_agreement SET version=?,title=?,draft_effective_on=?,revision=? WHERE id=?")
                        .param(version).param(title).param(input.effectiveOn()).param(revision).param(target).update();
                db.sql("DELETE FROM platform_agreement_section WHERE agreement_id=?").param(target).update();
            }
        } catch (DuplicateKeyException duplicate) {
            throw new OpsException(Error.VERSION_TAKEN);
        }
        int position = 0;
        for (Section section : input.sections()) {
            if (section == null) throw new IllegalArgumentException("章节不能为空");
            String heading = OpsText.required(section.title(), 200, "sectionTitle");
            String body = OpsText.multiline(section.body(), 20000, "sectionBody");
            if (body == null) throw new IllegalArgumentException("请填写章节正文");
            db.sql("INSERT INTO platform_agreement_section(agreement_id,position,title,body) VALUES(?,?,?,?)")
                    .param(target).param(++position).param(heading).param(body).update();
        }
        audit(target, revision, creating ? "CREATE" : "UPDATE", actor);
        return detail(target);
    }

    @Transactional
    public Detail publish(UUID id, Integer expected, OpsActor actor) {
        Header current = lockDraft(id, expected);
        int count = db.sql("SELECT count(*) FROM platform_agreement_section WHERE agreement_id=?").param(id).query(Integer.class).single();
        if (current.effectiveOn() == null || count == 0) throw new OpsException(Error.INCOMPLETE);
        try {
            db.sql("UPDATE platform_agreement SET published_on=draft_effective_on, revision=revision+1 WHERE id=?").param(id).update();
        } catch (DuplicateKeyException duplicate) {
            throw new OpsException(Error.DATE_TAKEN);
        }
        audit(id, current.revision() + 1, "PUBLISH", actor);
        return detail(id);
    }

    private Header lockDraft(UUID id, Integer expected) {
        if (expected == null || expected < 0) throw new IllegalArgumentException("请提供编辑版本");
        Header row = db.sql(HEADER + " WHERE a.id=? FOR UPDATE OF a").param(id).query(Header.class).optional()
                .orElseThrow(() -> new OpsException(Error.MISSING));
        if (row.revision() != expected) throw new OpsException(Error.CONFLICT);
        if (!"DRAFT".equals(row.status())) throw new OpsException(Error.PUBLISHED);
        return row;
    }

    private static String type(String value) {
        if (value == null || !TYPES.contains(value)) throw new IllegalArgumentException("未知协议类型");
        return value;
    }

    private void audit(UUID id, int revision, String action, OpsActor actor) {
        db.sql("INSERT INTO platform_agreement_change(agreement_id,revision,action,operator_id,operator_name) VALUES(?,?,?,?,?)")
                .param(id).param(revision).param(action).param(actor.accountId()).param(actor.displayName()).update();
    }

    enum Error implements OpsError {
        MISSING(404, OpsError.NOT_FOUND, "协议版本不存在"),
        CONFLICT(409, OpsError.VERSION_CONFLICT, "协议已被修改，请刷新后重试"),
        PUBLISHED(409, "OPS_AGREEMENT_IMMUTABLE", "已发布版本已留档，请新建版本"),
        VERSION_TAKEN(409, "OPS_AGREEMENT_VERSION_TAKEN", "该类型已使用这个版本号"),
        DATE_TAKEN(409, "OPS_AGREEMENT_DATE_TAKEN", "该类型在同一生效日已有发布版本"),
        INCOMPLETE(422, OpsError.PUBLISH_INCOMPLETE, "请先保存生效日期和至少一段正文");
        private final int status;
        private final String code;
        private final String message;
        Error(int status, String code, String message) { this.status = status; this.code = code; this.message = message; }
        public int httpStatus() { return status; }
        public String code() { return code; }
        public String message() { return message; }
    }
}
