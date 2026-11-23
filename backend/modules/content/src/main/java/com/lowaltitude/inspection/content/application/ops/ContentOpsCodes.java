package com.lowaltitude.inspection.content.application.ops;

/**
 * 运营端“发现内容”的稳定编码（契约 contracts/openapi-ops-content.yaml 1.0.0），和 V001、V003、V004 迁移里的 CHECK 约束一致。
 * 和公开读取的枚举值相同，另放一份是因为公开读取的枚举只在它自己的包里可见。
 */
public final class ContentOpsCodes {

    private ContentOpsCodes() {
    }

    /** 图文的两类：CASE 案例（服务商、商家发布，要有城市）；KNOWLEDGE 知识（可以不分城市）。视频走自己的接口。 */
    public enum OpsArticleKind {
        CASE,
        KNOWLEDGE
    }

    /** 作者或专题发布方：PROVIDER 服务商、MERCHANT 商家（都是身份模块里在用的服务商类机构）；PLATFORM 平台（写署名）。 */
    public enum OpsAuthorKind {
        PROVIDER,
        MERCHANT,
        PLATFORM
    }

    /** DRAFT 草稿；PUBLISHED 已上线（到了发布时间 App 才看得到）；OFFLINE 已下线。 */
    public enum OpsContentStatus {
        DRAFT,
        PUBLISHED,
        OFFLINE
    }

    /** 正文块（IMAGE 是 V004 加的配图块）。 */
    public enum OpsBlockType {
        PARAGRAPH,
        HEADING,
        FACTS,
        STEPS,
        LIST,
        TIP,
        NOTICE,
        IMAGE;

        /** 带一段文字的块。 */
        boolean hasText() {
            return this == PARAGRAPH || this == HEADING || this == TIP || this == NOTICE;
        }

        /** 带条目的块。 */
        boolean hasItems() {
            return this == FACTS || this == STEPS || this == LIST;
        }
    }

    /** “内容中提到”的对象。 */
    public enum OpsRefType {
        INSPECTION_SERVICE,
        PRO_SERVICE,
        PRODUCT,
        ACTIVITY,
        VENUE
    }

    /** 视频画面方向。 */
    public enum OpsVideoOrientation {
        PORTRAIT,
        LANDSCAPE
    }

    /** 改动记录的对象类型。 */
    public enum OpsContentSubject {
        ARTICLE,
        VIDEO,
        TOPIC
    }

    /** 改动记录的动作。 */
    public enum OpsContentAction {
        CREATE,
        UPDATE,
        PUBLISH,
        OFFLINE
    }
}
