import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { LiveHostRoom } from './live-host-api'

const get = vi.fn()
const put = vi.fn()
const post = vi.fn()
vi.mock('@/core/http', () => ({ http: { get, put, post } }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { roomId: 'room-1' }, query: {} }),
  useRouter: () => ({ push: vi.fn() }),
}))
const confirmAction = vi.fn()
const showError = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, showError, notifyRecorded: vi.fn() }))

const { default: LiveRoomDetailPage } = await import('./LiveRoomDetailPage.vue')

const PRODUCT = 'c2000000-0000-4000-8000-000000000101'

const HOST_ROOM: LiveHostRoom = {
  pushUrl: 'rtmp://127.0.0.1:1935/live/fake-seed-robot-intro',
  room: {
    roomId: 'room-1',
    title: '编程机器人入门',
    subtitle: null,
    coverUrl: null,
    host: { organizationId: 'org-1', name: '示例机器人商家' },
    cityCode: '370500',
    status: 'SCHEDULED',
    scheduledAt: '2099-11-25T11:30:00Z',
    startedAt: null,
    endedAt: null,
    viewerCount: 0,
    likeCount: 0,
    speakerName: '李老师',
    speakerTitle: '课程讲师',
    topics: ['套装里有哪些零件'],
    items: [{ seq: 1, type: 'PRODUCT', refId: PRODUCT, title: '编程机器人探索套装' }],
    currentItemSeq: null,
    replayUrl: null,
    replayPlatformName: null,
    playUrl: null,
    streamInterrupted: false,
  },
}

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

function button(text: string): HTMLButtonElement {
  const found = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
    .find((candidate) => candidate.textContent?.trim() === text)
  if (!found) throw new Error(`no button ${text}`)
  return found
}

describe('直播间详情', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    get.mockReset()
    put.mockReset()
    post.mockReset()
    confirmAction.mockReset()
    get.mockImplementation(async (path: string) => (path.endsWith('/comments')
      ? { items: [{ commentId: 'c1', seq: 1, side: 'VIEWER', authorName: '王**', text: '能飞多久', sentAt: '2099-11-25T11:40:00Z' }], cursor: 1, hiddenSeqs: [] }
      : HOST_ROOM))
  })

  it('推流地址默认打码，点“显示”才出现原文', async () => {
    const wrapper = mount(LiveRoomDetailPage, { global: { plugins: [ElementPlus] }, attachTo: document.body })
    await settle()

    const shown = () => document.body.querySelector('[data-test="push-url"]')!.textContent
    expect(shown()).toBe('rtmp://127.0.0.1:1935/********')
    expect(document.body.textContent).not.toContain('fake-seed-robot-intro')
    expect(document.body.textContent).toContain('不要外传')

    button('显示').click()
    await settle()
    expect(shown()).toBe('rtmp://127.0.0.1:1935/live/fake-seed-robot-intro')
    wrapper.unmount()
  })

  it('换当前讲解项、隐藏弹幕都先确认再调受权接口', async () => {
    confirmAction.mockResolvedValue(true)
    put.mockResolvedValue({ ...HOST_ROOM, room: { ...HOST_ROOM.room, currentItemSeq: 1 } })
    post.mockResolvedValue({ commentId: 'c1', seq: 1, hiddenAt: '2099-11-25T11:41:00Z' })
    const wrapper = mount(LiveRoomDetailPage, { global: { plugins: [ElementPlus] }, attachTo: document.body })
    await settle()

    button('1. 商品 · 编程机器人探索套装').click()
    await settle()
    expect(put).toHaveBeenCalledWith('/api/v1/live-host/rooms/room-1/current-item', { seq: 1 })

    button('隐藏').click()
    await settle()
    expect(confirmAction).toHaveBeenCalledTimes(2)
    expect(post).toHaveBeenCalledWith('/api/v1/live-host/rooms/room-1/comments/c1/hide')
    wrapper.unmount()
  })
})
