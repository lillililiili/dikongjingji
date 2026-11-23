import { describe, expect, it } from 'vitest'

import { jsonBody, recordingClient } from '@/core/http/recording-client'

import { createSystemNoticeApi, draftOf, draftProblems, emptyNoticeForm, paragraphsOf, type NoticeForm } from './system-notice-api'

function privacyForm(): NoticeForm {
  return {
    ...emptyNoticeForm(),
    title: ' 隐私政策更新摘要 ',
    summary: '查看主要变化与生效日期',
    paragraphsText: '我们更新了《隐私政策》。\n\n  完整内容以协议正文为准。  \n',
    facts: [{ label: '版本', value: 'v1.1' }],
    items: [
      { title: '权限用途', description: '逐项说明', linkType: 'PRIVACY_PERMISSIONS', agreement: 'USER_AGREEMENT' },
      { title: '说明', description: '没有链接', linkType: '', agreement: '' },
    ],
    actionLabel: '查看完整隐私政策',
    actionLinkType: 'AGREEMENT',
    actionAgreement: 'PRIVACY_POLICY',
    aboutAgreement: 'PRIVACY_POLICY',
  }
}

describe('系统通知（运营端）', () => {
  it('splits paragraphs on blank lines', () => {
    expect(paragraphsOf('一\n\n\n二\n三\n\n ')).toEqual(['一', '二\n三'])
  })

  it('turns the form into the contract draft', () => {
    expect(draftOf(privacyForm())).toEqual({
      title: '隐私政策更新摘要',
      summary: '查看主要变化与生效日期',
      paragraphs: ['我们更新了《隐私政策》。', '完整内容以协议正文为准。'],
      facts: [{ label: '版本', value: 'v1.1' }],
      items: [
        // 只有协议链接带协议。
        { title: '权限用途', description: '逐项说明', link: { type: 'PRIVACY_PERMISSIONS', agreement: null } },
        { title: '说明', description: '没有链接', link: null },
      ],
      footnote: null,
      action: { label: '查看完整隐私政策', link: { type: 'AGREEMENT', agreement: 'PRIVACY_POLICY' } },
      aboutAgreement: 'PRIVACY_POLICY',
      audience: { scope: 'ALL', cityCode: null },
      expiresAt: null,
    })
    expect(draftOf({ ...privacyForm(), scope: 'CITY', cityCode: '370500', actionLabel: '' }))
      .toMatchObject({ audience: { scope: 'CITY', cityCode: '370500' }, action: null })
  })

  it('names what is missing before publishing', () => {
    expect(draftProblems(privacyForm())).toEqual([])
    expect(draftProblems({ ...privacyForm(), paragraphsText: ' \n\n ', scope: 'CITY', actionAgreement: '' })).toEqual([
      '正文至少写一段',
      '按城市发布要选城市',
      '主按钮的协议链接要选哪份协议',
    ])
    expect(draftProblems({ ...privacyForm(), actionLinkType: '' })).toEqual(['主按钮的文字和去处要一起填'])
    expect(draftProblems({ ...privacyForm(), paragraphsText: '段\n\n'.repeat(11) })).toEqual(['正文最多 10 段'])
  })

  it('publishes with the idempotency key, lists and withdraws', async () => {
    const { http, requests } = recordingClient({ page: 1, size: 20, total: 0, hasMore: false, items: [] })
    const api = createSystemNoticeApi(http)

    await api.publish(privacyForm(), 'key-1')
    await api.list(2, 20)
    await api.withdraw('c5000000-0000-4000-8000-000000000001')

    expect(requests.map((request) => `${request.method} ${request.url}`)).toEqual([
      'POST /api/v1/ops/notifications/system-notices',
      'GET /api/v1/ops/notifications/system-notices?page=2&size=20',
      'POST /api/v1/ops/notifications/system-notices/c5000000-0000-4000-8000-000000000001/withdraw',
    ])
    expect(requests[0]?.headers['Idempotency-Key']).toBe('key-1')
    expect(jsonBody(requests[0])).toMatchObject({ title: '隐私政策更新摘要', aboutAgreement: 'PRIVACY_POLICY' })
  })
})
