package com.lowaltitude.inspection.content.application;

// 发现页图文内容和专题的稳定编码（契约 contracts/openapi-content.yaml），与 V001 迁移里的 CHECK 约束一致。

/** CASE 案例（服务商、商家发布）；KNOWLEDGE 知识（平台或机构发布）。 */
enum ContentArticleKind {
    CASE,
    KNOWLEDGE
}

/** 作者或专题发布方：PROVIDER 服务商、MERCHANT 商家（有机构）；PLATFORM 平台或城市运营（没有机构）。 */
enum ContentAuthorKind {
    PROVIDER,
    MERCHANT,
    PLATFORM
}

/** 发现页的图文频道：RECOMMEND 推荐（两类都有，另带当前的专题）、CASE 案例、KNOWLEDGE 知识。赛事、研学由活动列表提供。 */
enum ContentFeedChannel {
    RECOMMEND,
    CASE,
    KNOWLEDGE
}

/**
 * 正文块：PARAGRAPH 段落、HEADING 小标题、TIP 提示、NOTICE 文末说明（带 text）；
 * FACTS 要点表（条目 title = 名称，text = 值）、STEPS 编号步骤、LIST 条目列表（带 items）。
 */
enum ContentBlockType {
    PARAGRAPH,
    HEADING,
    FACTS,
    STEPS,
    LIST,
    TIP,
    NOTICE
}

/** “内容中提到”的对象，refId 是它的稳定 ID：巡检服务、专业服务、商品、活动、场馆。 */
enum ContentRefType {
    INSPECTION_SERVICE,
    PRO_SERVICE,
    PRODUCT,
    ACTIVITY,
    VENUE
}
