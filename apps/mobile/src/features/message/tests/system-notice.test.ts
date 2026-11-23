import { describe, expect, it } from 'vitest'

import { toSystemNotice } from '../data/notification-extras-api'
import { toSystemNoticeLink } from '../domain/system-notice'
import { buildNoticeDetailUrl, parseNoticeId } from '../navigation/message-routes'
import { NOTICE_ICONS, formatNoticeTime, noticeItemIcon } from '../presentation/notice-format'

const NOTICE_ID = 'c5000000-0000-4000-8000-000000000001'

describe('系统通知 (S5-9)', () => {
  it('maps the board notice and drops links this version does not know', () => {
    const notice = toSystemNotice({
      id: NOTICE_ID,
      title: '隐私政策更新摘要',
      summary: '查看主要变化与生效日期',
      paragraphs: ['我们更新了《隐私政策》。'],
      facts: [{ label: '版本', value: 'v1.1' }, { label: '生效日期', value: '2026-11-08' }],
      items: [
        { title: '参与人信息', description: '只用于核验和凭证', link: { type: 'ACTIVITY_PARTICIPANTS', agreement: null } },
        { title: '新页面', description: '以后的版本才有', link: { type: 'WEB_PAGE', agreement: null } },
        { title: '没有链接', description: '只是说明', link: null },
      ],
      footnote: '生效前可以阅读全文。',
      action: { label: '查看完整隐私政策', link: { type: 'AGREEMENT', agreement: 'PRIVACY_POLICY' } },
      publishedAt: '2026-11-01T01:00:00Z',
    })

    expect(notice.items.map((item) => item.link)).toEqual([{ type: 'ACTIVITY_PARTICIPANTS' }, null, null])
    expect(notice.action).toEqual({ label: '查看完整隐私政策', link: { type: 'AGREEMENT', agreement: 'PRIVACY_POLICY' } })
    expect(notice.facts).toHaveLength(2)
    // An action whose link is unknown is left out rather than shown dead.
    expect(toSystemNotice({ ...notice, action: { label: '去看看', link: { type: 'WEB_PAGE' } } }).action).toBeNull()
    expect(toSystemNotice({ id: NOTICE_ID, title: 't', summary: 's', publishedAt: '2026-11-01T01:00:00Z' }))
      .toMatchObject({ paragraphs: [], facts: [], items: [], footnote: null, action: null })
  })

  it('only accepts an agreement link that names a known agreement', () => {
    expect(toSystemNoticeLink({ type: 'AGREEMENT', agreement: 'USER_AGREEMENT' }))
      .toEqual({ type: 'AGREEMENT', agreement: 'USER_AGREEMENT' })
    expect(toSystemNoticeLink({ type: 'AGREEMENT', agreement: null })).toBeNull()
    expect(toSystemNoticeLink({ type: 'AGREEMENT', agreement: 'COOKIE_POLICY' })).toBeNull()
    expect(toSystemNoticeLink({ type: 'PLATFORM_SUPPORT' })).toEqual({ type: 'PLATFORM_SUPPORT' })
    expect(toSystemNoticeLink(undefined)).toBeNull()
  })

  it('shows the time in Beijing as on the board and picks the row icons by link', () => {
    expect(formatNoticeTime('2026-11-01T01:00:00Z')).toBe('11/01 09:00')
    expect(formatNoticeTime('nonsense')).toBe('')
    expect(noticeItemIcon({ type: 'PRIVACY_PERMISSIONS' })).toBe(NOTICE_ICONS.permissions)
    expect(noticeItemIcon({ type: 'PERSONAL_DATA_EXPORT' })).toBe(NOTICE_ICONS.exportData)
    expect(noticeItemIcon(null)).toBe(NOTICE_ICONS.document)
  })

  it('opens by the stable id only', () => {
    expect(buildNoticeDetailUrl(NOTICE_ID)).toBe(`/pages/message/notice?id=${NOTICE_ID}`)
    expect(parseNoticeId(NOTICE_ID)).toBe(NOTICE_ID)
    expect(parseNoticeId('../evil')).toBeNull()
    expect(parseNoticeId(undefined)).toBeNull()
  })
})
