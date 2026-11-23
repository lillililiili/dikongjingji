-- 仅开发环境：由 @Profile("dev") 的 notificationDevelopmentDataFlyway 加载，不在 db/migration 下，生产不会执行。
-- 一份发给全体的系统通知“隐私政策更新摘要”（画板 NoticeDetail、Messages 的系统通知行、Agreement 的“有更新”）。
-- 版本、日期和三条变化是示意，协议定稿后按真实变更替换；没有发布人和幂等键（不是经运营接口发的）。
-- 账号打开“消息”时由服务补记成它的一条站内信，这里不写站内信。
INSERT INTO notification_system_notice (
    id, title, summary, content, about_agreement, audience_scope, city_code,
    published_by, published_at, expires_at, publish_key, status
)
VALUES (
    'c5000000-0000-4000-8000-000000000001',
    '隐私政策更新摘要',
    '查看主要变化与生效日期',
    $json${
      "paragraphs": ["我们更新了《隐私政策》。下面是这次的主要变化，完整内容以协议正文为准。"],
      "facts": [
        {"label": "版本", "value": "v1.1"},
        {"label": "生效日期", "value": "2026-11-08"}
      ],
      "items": [
        {"title": "参与人信息", "description": "写明报名填写的信息只用于核验和凭证",
         "link": {"type": "ACTIVITY_PARTICIPANTS", "agreement": null}},
        {"title": "权限用途", "description": "逐项说明定位、相机、通知用在哪里",
         "link": {"type": "PRIVACY_PERMISSIONS", "agreement": null}},
        {"title": "查看与导出", "description": "你可以在设置里查看和导出个人信息",
         "link": {"type": "PERSONAL_DATA_EXPORT", "agreement": null}}
      ],
      "footnote": "生效前可以阅读全文。有疑问可以联系平台客服；不再使用平台时，可以在设置里申请注销账户。",
      "action": {"label": "查看完整隐私政策", "link": {"type": "AGREEMENT", "agreement": "PRIVACY_POLICY"}}
    }$json$::jsonb,
    'PRIVACY_POLICY',
    'ALL',
    NULL,
    NULL,
    TIMESTAMPTZ '2026-11-01 09:00:00+08',
    NULL,
    NULL,
    'PUBLISHED'
)
ON CONFLICT (id) DO UPDATE
SET title = EXCLUDED.title,
    summary = EXCLUDED.summary,
    content = EXCLUDED.content,
    about_agreement = EXCLUDED.about_agreement,
    audience_scope = EXCLUDED.audience_scope,
    city_code = EXCLUDED.city_code,
    published_at = EXCLUDED.published_at,
    expires_at = EXCLUDED.expires_at,
    status = 'PUBLISHED',
    withdrawn_at = NULL,
    updated_at = now();
