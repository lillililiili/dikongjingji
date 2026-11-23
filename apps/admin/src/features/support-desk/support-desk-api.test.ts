import { describe, expect, it, vi } from 'vitest'

import { createApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'

import {
  createSupportDeskApi,
  lastMessageLabel,
  mergeMessages,
  replyError,
  type SupportMessage,
} from './support-desk-api'

function fakeHttp() {
  const fetchImpl = vi.fn<typeof fetch>().mockImplementation(async () =>
    new Response('{}', { status: 200, headers: { 'Content-Type': 'application/json' } }))
  const http = createApiClient({ getToken: () => 't', onUnauthorized: vi.fn(), fetchImpl, newRequestId: () => 'r' })
  return { api: createSupportDeskApi(http), fetchImpl }
}

function message(seq: number, overrides: Partial<SupportMessage> = {}): SupportMessage {
  return {
    messageId: `m-${seq}`, seq, senderSide: 'CUSTOMER', mine: false, kind: 'TEXT', text: `第${seq}条`, card: null,
    sentAt: '2026-11-23T01:00:00Z', ...overrides,
  }
}

describe('客服会话接口', () => {
  it('会话分页、消息按 before 往前翻、回复带幂等键、标已读', async () => {
    const { api, fetchImpl } = fakeHttp()
    await api.conversations({}, 2, 20)
    await api.messages('c-1')
    await api.messages('c-1', 31, 30)
    await api.reply('c-1', '  好的，明天发货 ', 'key-1')
    await api.read('c-1')

    const urls = fetchImpl.mock.calls.map(([url]) => String(url))
    expect(urls).toEqual([
      '/api/v1/provider/conversations?page=2&size=20',
      '/api/v1/provider/conversations/c-1/messages?size=30',
      '/api/v1/provider/conversations/c-1/messages?before=31&size=30',
      '/api/v1/provider/conversations/c-1/messages',
      '/api/v1/provider/conversations/c-1/read',
    ])
    const reply = fetchImpl.mock.calls[3]![1]!
    expect(reply.method).toBe('POST')
    expect(JSON.parse(String(reply.body))).toEqual({ text: '好的，明天发货' })
    expect(reply.headers).toMatchObject({ 'Idempotency-Key': 'key-1' })
    expect(fetchImpl.mock.calls[4]![1]!.method).toBe('POST')
  })

  it('业务错误码有中文说明', () => {
    expect(errorMessage(new ApiRequestError(409, 'SUPPORT_IDEMPOTENCY_KEY_REUSED', 'x', 't'))).toContain('重新发送')
  })
})

describe('回复规则', () => {
  it('不能空、最多 500 字（按码点）、不含控制字符、不留手机号', () => {
    expect(replyError('   ')).toBe('请先写回复内容')
    expect(replyError('好'.repeat(500))).toBeNull()
    expect(replyError('好'.repeat(501))).toContain('500')
    expect(replyError('第一行\n第二行')).toBeNull()
    expect(replyError('a\u0007b')).toContain('不能显示的字符')
    expect(replyError('打我电话 13812345678')).toContain('不要留手机号')
    expect(replyError('电话13812345678吧')).toContain('不要留手机号')
    // 订单号里的长数字不是手机号。
    expect(replyError('订单 MO2026112313812345678 已发货')).toBeNull()
    expect(replyError('运单 SF1234567890')).toBeNull()
  })
})

describe('列表和消息', () => {
  it('最后一条写清谁说的，卡片标出来', () => {
    expect(lastMessageLabel(null)).toBe('—')
    expect(lastMessageLabel({ senderSide: 'CUSTOMER', kind: 'TEXT', preview: '什么时候发货', sentAt: '' }))
      .toBe('客户：什么时候发货')
    expect(lastMessageLabel({ senderSide: 'COUNTERPART', kind: 'CARD', preview: '巡检机器狗', sentAt: '' }))
      .toBe('我方：[卡片] 巡检机器狗')
  })

  it('更早的消息接在前面，按序号去重排序', () => {
    const merged = mergeMessages([message(3), message(4)], [message(1), message(2), message(3, { text: '新' })])
    expect(merged.map((item) => item.seq)).toEqual([1, 2, 3, 4])
    expect(merged[2]!.text).toBe('新')
  })
})
