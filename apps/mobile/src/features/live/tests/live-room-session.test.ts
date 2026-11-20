import { describe, expect, it, vi } from 'vitest'

import {
  addOwnComment,
  createLikeBatcher,
  createLiveKey,
  emptyCommentFeed,
  liveDeviceId,
  mergeCommentPage,
  sendableComment,
} from '../application/live-room-session'
import type { LiveComment } from '../domain/live'

const comment = (seq: number): LiveComment => ({
  commentId: `c${seq}`, seq, side: 'VIEWER', authorName: '王**', text: `第${seq}条`, sentAt: '',
})

describe('comment polling', () => {
  it('keeps one copy per number in order, drops hidden ones and keeps the newest few', () => {
    let feed = mergeCommentPage(emptyCommentFeed(), { items: [comment(1), comment(2), comment(3)], cursor: 3, hiddenSeqs: [] })
    feed = mergeCommentPage(feed, { items: [comment(3), comment(4)], cursor: 4, hiddenSeqs: [2] }, 3)

    expect(feed.items.map((item) => item.seq)).toEqual([1, 3, 4])
    expect(feed.cursor).toBe(4)
    feed = mergeCommentPage(feed, { items: [comment(5), comment(6)], cursor: 6, hiddenSeqs: [] }, 3)
    expect(feed.items.map((item) => item.seq)).toEqual([4, 5, 6])
  })

  it('never moves the cursor back and shows the own comment at once without a second copy', () => {
    let feed = mergeCommentPage(emptyCommentFeed(), { items: [], cursor: 9, hiddenSeqs: [] })
    feed = mergeCommentPage(feed, { items: [], cursor: 4, hiddenSeqs: [] })
    expect(feed.cursor).toBe(9)

    feed = addOwnComment(feed, comment(10))
    feed = mergeCommentPage(feed, { items: [comment(10)], cursor: 10, hiddenSeqs: [] })
    expect(feed.items.map((item) => item.seq)).toEqual([10])
    expect(feed.cursor).toBe(10)
  })

  it('sends one line of 1–50 characters', () => {
    expect(sendableComment('  机器狗一晚上能巡几圈？ ')).toBe('机器狗一晚上能巡几圈？')
    expect(sendableComment('两\n行')).toBe('两 行')
    expect(sendableComment('   ')).toBeNull()
    expect(sendableComment('一'.repeat(51))).toBeNull()
    expect(sendableComment('一'.repeat(50))).toHaveLength(50)
  })
})

describe('likes', () => {
  it('merges taps, sends after the pause and never more than 30 at once', async () => {
    const sent: number[] = []
    const timers: { run: (() => void) | null } = { run: null }
    const batcher = createLikeBatcher({
      send: async (count) => { sent.push(count) },
      setTimer: (callback) => { timers.run = callback; return 1 },
      clearTimer: () => { timers.run = null },
    })

    batcher.tap()
    batcher.tap()
    batcher.tap()
    expect(sent).toEqual([])
    expect(batcher.pending()).toBe(3)
    timers.run?.()
    await vi.waitFor(() => expect(sent).toEqual([3]))

    for (let index = 0; index < 31; index += 1) batcher.tap()
    await vi.waitFor(() => expect(sent).toEqual([3, 30]))
    await batcher.flush()
    expect(sent).toEqual([3, 30, 1])
    expect(batcher.pending()).toBe(0)
  })

  it('drops a failed batch instead of counting it twice', async () => {
    const send = vi.fn(async () => { throw new Error('offline') })
    const batcher = createLikeBatcher({ send, setTimer: () => 1, clearTimer: () => undefined })
    batcher.tap()
    await batcher.flush()
    await batcher.flush()
    expect(send).toHaveBeenCalledTimes(1)
  })
})

describe('device id and keys', () => {
  it('makes v4 UUIDs', () => {
    expect(createLiveKey()).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  })

  it('keeps one random id per device and replaces anything that is not one', () => {
    const store = new Map<string, string>()
    const storage = { get: (key: string) => store.get(key), set: (key: string, value: string) => { store.set(key, value) } }
    const first = liveDeviceId(storage)
    expect(liveDeviceId(storage)).toBe(first)
    store.set('live.deviceId', 'not-a-uuid')
    expect(liveDeviceId(storage)).not.toBe('not-a-uuid')
    const failing = { get: () => null, set: () => { throw new Error('full') } }
    expect(liveDeviceId(failing)).toMatch(/^[0-9a-f-]{36}$/)
  })
})
