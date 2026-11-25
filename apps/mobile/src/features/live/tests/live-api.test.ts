import { describe, expect, it } from 'vitest'

import { createLiveApi, toLiveRoom, toLiveRoomCard } from '../data/live-api'

const ROOM = 'f4000000-0000-4000-8000-000000000001'
const PRODUCT = 'c2000000-0000-4000-8000-000000000401'

function roomDto(overrides: Record<string, unknown> = {}) {
  return {
    roomId: ROOM,
    title: '机器狗园区夜巡演示',
    subtitle: '看一台机器狗怎样走完园区夜间路线',
    coverUrl: null,
    host: { organizationId: 'f2000000-0000-4000-8000-000000000001', name: '鹰眼智能巡检' },
    cityCode: '370500',
    status: 'LIVE',
    scheduledAt: '2026-11-20T10:13:00Z',
    startedAt: '2026-11-20T10:18:00Z',
    endedAt: null,
    viewerCount: 12400,
    likeCount: 23600,
    speakerName: '王工',
    speakerTitle: '项目负责人',
    topics: ['夜间路线怎么定', ' '],
    items: [
      { seq: 1, type: 'INSPECTION_SERVICE', refId: '77777777-7777-7777-7777-777777777777', title: '园区安防巡检' },
      { seq: 2, type: 'PRODUCT', refId: PRODUCT, title: '巡检机器狗' },
      { seq: 3, type: 'ARTICLE', refId: 'x', title: '以后才有的类型' },
    ],
    currentItemSeq: 1,
    replayUrl: null,
    replayPlatformName: null,
    playUrl: 'https://media.example/live-night-patrol.mp4',
    streamInterrupted: false,
    ...overrides,
  }
}

describe('live DTO mapping', () => {
  it('keeps the known items, the current one and the playable address of a live room', () => {
    const room = toLiveRoom(roomDto())

    expect(room.items.map((item) => item.seq)).toEqual([1, 2])
    expect(room.currentItemSeq).toBe(1)
    expect(room.topics).toEqual(['夜间路线怎么定'])
    expect(room.playUrl).toBe('https://media.example/live-night-patrol.mp4')
    expect(room.host.name).toBe('鹰眼智能巡检')
  })

  it('plays nothing unless live, and drops a current item that is not on the list', () => {
    expect(toLiveRoom(roomDto({ status: 'SCHEDULED', streamInterrupted: true })))
      .toMatchObject({ playUrl: null, streamInterrupted: false })
    expect(toLiveRoom(roomDto({ currentItemSeq: 3 })).currentItemSeq).toBeNull()
    expect(toLiveRoom(roomDto({ status: 'PAUSED' })).status).toBe('ENDED')
  })

  it('takes only https covers and replays, and a replay without a link has no platform name', () => {
    expect(toLiveRoom(roomDto({ coverUrl: 'http://cover/1.jpg' })).coverUrl).toBeNull()
    expect(toLiveRoom(roomDto({ coverUrl: 'https://cover/1.jpg' })).coverUrl).toBe('https://cover/1.jpg')
    expect(toLiveRoom(roomDto({ status: 'ENDED', replayUrl: 'javascript:alert(1)', replayPlatformName: '示例直播平台' })))
      .toMatchObject({ replayUrl: null, replayPlatformName: null })
    expect(toLiveRoom(roomDto({ status: 'ENDED', replayUrl: 'https://live.example.com/r', replayPlatformName: '示例直播平台' })))
      .toMatchObject({ replayUrl: 'https://live.example.com/r', replayPlatformName: '示例直播平台' })
  })

  it('maps a card with its current item and replay flag', () => {
    const card = toLiveRoomCard({
      ...roomDto({ status: 'ENDED' }),
      currentItem: { seq: 2, type: 'PRODUCT', refId: PRODUCT, title: '巡检机器狗' },
      hasReplay: true,
    })
    expect(card).toMatchObject({ status: 'ENDED', hasReplay: true, currentItem: { seq: 2, type: 'PRODUCT' } })
    expect(toLiveRoomCard({ roomId: ROOM, viewerCount: -3 })).toMatchObject({ viewerCount: 0, title: '直播', hasReplay: false })
  })
})


describe('live requests', () => {
  function recorder(answer: unknown) {
    const calls: Array<{ url: string; method: string; data?: unknown; headers?: Record<string, string> }> = []
    const api = createLiveApi(async <T>(input: { url: string; method: string; data?: unknown; headers?: Record<string, string> }) => {
      calls.push(input)
      return answer as T
    })
    return { api, calls }
  }

  it('reads the public lists and the room without a token', async () => {
    const { api, calls } = recorder({ hasMore: false, total: 1, items: [roomDto()] })
    const page = await api.loadRooms('370500', 'LIVE', 1, 1)
    await api.loadRoom(ROOM, '370500')
    expect(page.items[0].roomId).toBe(ROOM)
    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      'GET /api/v1/live/rooms?cityCode=370500&status=LIVE&page=1&size=1',
      `GET /api/v1/live/rooms/${ROOM}?cityCode=370500`,
    ])
    expect(calls.every((call) => !call.headers?.Authorization)).toBe(true)
  })

  it('polls comments by cursor and drops what it cannot draw', async () => {
    const { api, calls } = recorder({
      items: [
        { commentId: 'c1', seq: 4, side: 'HOST', authorName: '鹰眼智能巡检', text: '一圈 40 分钟', sentAt: 'x' },
        { commentId: 'c2', seq: 5, side: 'VIEWER', authorName: '王**' },
      ],
      cursor: 5,
      hiddenSeqs: [2],
    })
    const page = await api.loadComments(ROOM, 3)
    await api.loadComments(ROOM, null)
    expect(page).toMatchObject({ cursor: 5, hiddenSeqs: [2], items: [{ seq: 4, side: 'HOST' }] })
    expect(calls.map((call) => call.url)).toEqual([
      `/api/v1/live/rooms/${ROOM}/comments?since=3`,
      `/api/v1/live/rooms/${ROOM}/comments`,
    ])
  })

  it('sends the token, the idempotency key and only what the server takes', async () => {
    const { api, calls } = recorder({ commentId: 'c9', seq: 9, side: 'VIEWER', authorName: '王**', text: '在吗', sentAt: 'x', likeCount: 7, following: true, reminded: true, viewerCount: 3 })
    await api.sendComment('t', ROOM, 'k1', '在吗')
    await api.like('t', ROOM, 7)
    await api.setReminder('t', ROOM, true)
    await api.setReminder('t', ROOM, false)
    await api.setFollow('t', 'org 1', true)
    await api.setFollow('t', 'org 1', false)
    await api.report('t', ROOM, 'c1')
    await api.recordView(ROOM, 'd1')

    expect(calls.map((call) => `${call.method} ${call.url}`)).toEqual([
      `POST /api/v1/live/rooms/${ROOM}/comments`,
      `POST /api/v1/live/rooms/${ROOM}/likes`,
      `PUT /api/v1/live/rooms/${ROOM}/reminder`,
      `DELETE /api/v1/live/rooms/${ROOM}/reminder`,
      'PUT /api/v1/live/hosts/org%201/follow',
      'DELETE /api/v1/live/hosts/org%201/follow',
      `POST /api/v1/live/rooms/${ROOM}/comments/c1/report`,
      `POST /api/v1/live/rooms/${ROOM}/views`,
    ])
    expect(calls[0]).toMatchObject({ headers: { Authorization: 'Bearer t', 'Idempotency-Key': 'k1' }, data: { text: '在吗' } })
    expect(calls[1].data).toEqual({ count: 7 })
    expect(calls[7]).toMatchObject({ data: { deviceId: 'd1' } })
    expect(calls[7].headers).toBeUndefined()
  })
})
