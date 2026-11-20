import { describe, expect, it } from 'vitest'

import {
  chatLines,
  formatElapsed,
  formatLiveCount,
  formatLiveDate,
  formatLiveDay,
  formatLiveDuration,
  formatLiveShort,
  hostAvatarColor,
  hostInitial,
  liveBarLine,
  noticeView,
  scheduleChip,
  splitLiveChannel,
  withLiveTab,
} from '../application/live-view'
import type { LiveComment, LiveRoomCard } from '../domain/live'

function card(roomId: string, status: LiveRoomCard['status'], hasReplay = false): LiveRoomCard {
  return {
    roomId,
    title: roomId,
    subtitle: null,
    coverUrl: null,
    host: { organizationId: 'o', name: '鹰眼智能巡检' },
    cityCode: '370500',
    status,
    scheduledAt: '2026-11-22T11:30:00Z',
    startedAt: null,
    endedAt: null,
    viewerCount: 0,
    likeCount: 0,
    currentItem: null,
    hasReplay,
  }
}

describe('live numbers and times', () => {
  it('writes counts like the boards', () => {
    expect(formatLiveCount(12400)).toBe('1.2万')
    expect(formatLiveCount(23600)).toBe('2.4万')
    expect(formatLiveCount(3860)).toBe('3860')
  })

  it('counts the time since going live', () => {
    const now = new Date('2026-11-20T11:00:17Z')
    expect(formatElapsed('2026-11-20T10:18:00Z', now)).toBe('42:17')
    expect(formatElapsed('2026-11-20T09:57:14Z', now)).toBe('1:03:03')
    expect(formatElapsed(null, now)).toBe('0:00')
  })

  it('writes Beijing days and times', () => {
    // 2026-11-08 19:30 in Beijing is a Sunday (board LiveNotice).
    expect(formatLiveDay('2026-11-08T11:30:00Z')).toBe('11/08 周日 19:30')
    expect(formatLiveShort('2026-11-08T11:30:00Z')).toBe('11/08 19:30')
    expect(formatLiveDate('2026-10-30T06:54:00Z')).toBe('10/30')
    expect(formatLiveDate(null)).toBe('')
    expect(formatLiveDuration('2026-10-30T06:02:00Z', '2026-10-30T06:54:00Z')).toBe('52 分钟')
    expect(formatLiveDuration(null, '2026-10-30T06:54:00Z')).toBeNull()
  })

  it('counts days to the session by the Beijing calendar', () => {
    const now = new Date('2026-11-03T04:00:00Z')
    expect(scheduleChip('2026-11-08T11:30:00Z', now)).toBe('还有 5 天')
    expect(scheduleChip('2026-11-04T11:30:00Z', now)).toBe('明天开播')
    expect(scheduleChip('2026-11-03T11:30:00Z', now)).toBe('今天开播')
    expect(scheduleChip('2026-11-03T01:00:00Z', now)).toBe('即将开播')
  })
})

describe('直播预告 page', () => {
  const now = new Date('2026-11-03T04:00:00Z')

  it('offers 提醒我 before, 进直播间 during and the partner replay after', () => {
    const before = noticeView({ status: 'SCHEDULED', scheduledAt: '2026-11-08T11:30:00Z', replayUrl: null, replayPlatformName: null }, now)
    expect(before).toMatchObject({ stage: 0, action: 'REMIND', tag: { text: '直播预告', background: '#6a4fe0' }, chip: { text: '还有 5 天' } })
    expect(before.steps.map((step) => step.state)).toEqual(['now', 'later', 'later'])

    const during = noticeView({ status: 'LIVE', scheduledAt: '2026-11-08T11:30:00Z', replayUrl: null, replayPlatformName: null }, now)
    expect(during).toMatchObject({ stage: 1, action: 'ENTER', actionLabel: '进直播间', tag: { text: '直播中' } })
    expect(during.steps.map((step) => step.state)).toEqual(['past', 'now', 'later'])

    const replay = noticeView({ status: 'ENDED', scheduledAt: '', replayUrl: 'https://r', replayPlatformName: '示例直播平台' }, now)
    expect(replay).toMatchObject({ action: 'REPLAY', actionLabel: '去合作平台回看', platformName: '示例直播平台', tag: { text: '已结束 · 可回看' } })

    const gone = noticeView({ status: 'ENDED', scheduledAt: '', replayUrl: null, replayPlatformName: null }, now)
    expect(gone).toMatchObject({ action: null, platformName: null, tag: { text: '已结束' } })
    expect(gone.steps[2].note).toBe('本场没有回看')
  })
})

describe('发现 · 直播', () => {
  it('puts 直播 after 视频 when there is one, otherwise after 推荐', () => {
    expect(withLiveTab(['RECOMMEND', 'EVENTS', 'STUDY', 'CASE', 'KNOWLEDGE']))
      .toEqual(['RECOMMEND', 'LIVE', 'EVENTS', 'STUDY', 'CASE', 'KNOWLEDGE'])
    expect(withLiveTab(['RECOMMEND', 'VIDEO', 'EVENTS'])).toEqual(['RECOMMEND', 'VIDEO', 'LIVE', 'EVENTS'])
    expect(withLiveTab(['RECOMMEND', 'LIVE'])).toEqual(['RECOMMEND', 'LIVE'])
  })

  it('splits the channel into live rooms, previews and replays in the server order', () => {
    const sections = splitLiveChannel([card('a', 'LIVE'), card('b', 'SCHEDULED'), card('c', 'ENDED', true),
      card('d', 'ENDED', false), card('e', 'LIVE')])
    expect(sections.live.map((room) => room.roomId)).toEqual(['a', 'e'])
    expect(sections.scheduled.map((room) => room.roomId)).toEqual(['b'])
    expect(sections.replays.map((room) => room.roomId)).toEqual(['c'])
  })

  it('says what is explained on the live bar and colours the host like the board', () => {
    expect(liveBarLine({ host: { organizationId: 'o', name: '鹰眼智能巡检' }, currentItem: { seq: 1, type: 'INSPECTION_SERVICE', refId: 'x', title: '园区安防巡检' } }))
      .toBe('鹰眼智能巡检 · 正在讲解 园区安防巡检')
    expect(liveBarLine({ host: { organizationId: 'o', name: '示例无人机厂家' }, currentItem: null })).toBe('示例无人机厂家')
    expect(hostInitial('鹰眼智能巡检')).toBe('鹰')
    expect(hostAvatarColor('鹰眼智能巡检')).toBe('#11804a')
    expect(hostAvatarColor('示例无人机厂家')).toBe('#ff6a00')
    expect(hostAvatarColor('云翼科技')).toBe('#6a4fe0')
  })
})

describe('直播间 comment lines', () => {
  const comment = (seq: number, side: LiveComment['side'], authorName: string): LiveComment => ({
    commentId: `c${seq}`, seq, side, authorName, text: `第${seq}条`, sentAt: '',
  })

  it('opens with the welcome line, names the host 主播 and the viewer 我, five lines in all', () => {
    const lines = chatLines([comment(1, 'VIEWER', '王**'), comment(2, 'HOST', '鹰眼智能巡检')], new Set())
    expect(lines.map((line) => line.name)).toEqual(['系统', '王**', '主播'])
    expect(lines.map((line) => line.color)).toEqual(['#ffc53d', '#9fe1cb', '#ff8a5b'])

    const many = chatLines([1, 2, 3, 4, 5, 6].map((seq) => comment(seq, 'VIEWER', '张**')), new Set(['c6']))
    expect(many).toHaveLength(5)
    expect(many[4]).toMatchObject({ name: '我', color: '#7dd3fc' })
    expect(many[0].name).not.toBe('系统')
  })
})
