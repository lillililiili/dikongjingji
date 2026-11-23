import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { DeskConversation, DeskMessagePage, SupportDeskApi, SupportMessage } from './support-desk-api'

const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction: vi.fn(), showError, notifyRecorded: vi.fn() }))

const { default: ConversationThread } = await import('./ConversationThread.vue')

const CONVERSATION: DeskConversation = {
  conversationId: 'c-1',
  counterpartKind: 'MERCHANT',
  customer: { name: '王女士（示例）' },
  lastMessage: { senderSide: 'CUSTOMER', kind: 'TEXT', preview: '什么时候发货', sentAt: '2026-11-23T01:00:00Z' },
  unreadCount: 2,
  createdAt: '2026-11-22T01:00:00Z',
}

function message(seq: number, overrides: Partial<SupportMessage> = {}): SupportMessage {
  return {
    messageId: `m-${seq}`, seq, senderSide: 'CUSTOMER', mine: false, kind: 'TEXT', text: `客户第${seq}条`, card: null,
    sentAt: '2026-11-23T01:00:00Z', ...overrides,
  }
}

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

function button(wrapper: ReturnType<typeof mount>, text: string) {
  const found = wrapper.findAll('button').find((candidate) => candidate.text().includes(text))
  if (!found) throw new Error(`no button ${text}`)
  return found
}

function mountThread(api: Partial<SupportDeskApi>) {
  return mount(ConversationThread, {
    props: { api: api as SupportDeskApi, conversationId: 'c-1' },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  })
}

describe('客服会话', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    showError.mockReset()
  })

  it('进来读最新一页并标已读；卡片显示类型和标题；更早的消息按 before 往前翻', async () => {
    const latest: DeskMessagePage = {
      conversation: CONVERSATION,
      items: [
        message(31, { kind: 'CARD', text: null, card: { type: 'MALL_ORDER', refId: 'o-1', title: '编程机器人套装', subtitle: '待发货' } }),
        message(32),
      ],
      hasMore: true,
      nextBefore: 31,
    }
    const earlier: DeskMessagePage = { conversation: CONVERSATION, items: [message(30)], hasMore: false, nextBefore: null }
    const messages = vi.fn().mockResolvedValueOnce(latest).mockResolvedValueOnce(earlier)
    const read = vi.fn().mockResolvedValue({ ...CONVERSATION, unreadCount: 0 })
    const wrapper = mountThread({ messages, read })
    await settle()

    expect(messages).toHaveBeenCalledWith('c-1')
    expect(read).toHaveBeenCalledWith('c-1')
    expect(wrapper.text()).toContain('商城订单')
    expect(wrapper.text()).toContain('编程机器人套装')

    await button(wrapper, '更早的消息').trigger('click')
    await settle()
    expect(messages).toHaveBeenLastCalledWith('c-1', 31)
    expect(wrapper.text()).toContain('客户第30条')
    expect(wrapper.text()).toContain('没有更早的消息了')
    wrapper.unmount()
  })

  it('没有未读不调 read；留手机号的回复不发；正常回复带幂等键，发完换新键', async () => {
    const page: DeskMessagePage = {
      conversation: { ...CONVERSATION, unreadCount: 0 }, items: [message(1)], hasMore: false, nextBefore: null,
    }
    const messages = vi.fn().mockResolvedValue(page)
    const read = vi.fn()
    const reply = vi.fn()
      .mockResolvedValueOnce(message(2, { senderSide: 'COUNTERPART', mine: true, text: '明天发货' }))
      .mockResolvedValueOnce(message(3, { senderSide: 'COUNTERPART', mine: true, text: '已发出' }))
    const wrapper = mountThread({ messages, read, reply })
    await settle()
    expect(read).not.toHaveBeenCalled()

    const textarea = wrapper.find('textarea')
    await textarea.setValue('加我 13812345678')
    await button(wrapper, '发送').trigger('click')
    await settle()
    expect(reply).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('不要留手机号')

    await textarea.setValue('明天发货')
    await button(wrapper, '发送').trigger('click')
    await settle()
    expect(reply).toHaveBeenCalledWith('c-1', '明天发货', expect.any(String))
    expect(wrapper.text()).toContain('明天发货')
    expect((textarea.element as HTMLTextAreaElement).value).toBe('')

    await textarea.setValue('已发出')
    await button(wrapper, '发送').trigger('click')
    await settle()
    expect(reply.mock.calls[1]![2]).not.toBe(reply.mock.calls[0]![2])
    wrapper.unmount()
  })
})
