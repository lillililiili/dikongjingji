import { describe, expect, it } from 'vitest'

import type { ContentRefView } from '../application/content-refs'
import { createDiscoverFeedSource } from '../application/discover-feed'
import { isWaterfallChannel } from '../application/content-view'
import {
  formatCommentTime,
  formatPlayerClock,
  formatVideoCount,
  formatVideoDuration,
  formatVideoTags,
  videoEntry,
} from '../application/video-view'
import type { ContentFeedPage } from '../domain/content'
import { CITY, articleCard } from './content-fixtures'

function refView(overrides: Partial<ContentRefView> = {}): ContentRefView {
  return {
    key: 'PRODUCT:p1',
    type: 'PRODUCT',
    refId: 'p1',
    tag: '商品',
    tagTone: { background: '#fff0e3', color: '#ba470b' },
    icon: 'shop',
    title: '编程机器人探索套装',
    price: { amount: '¥1,299', unit: '起' },
    actionLabel: '看商品',
    primary: false,
    imageUrl: null,
    url: '/pages/mall/product?productId=p1&cityCode=370500',
    ...overrides,
  }
}

describe('video view', () => {
  it('prints durations, the player clock and counts as the boards do', () => {
    expect(formatVideoDuration(192)).toBe('3:12')
    expect(formatVideoDuration(58)).toBe('0:58')
    expect(formatVideoDuration(3725)).toBe('1:02:05')
    expect(formatPlayerClock(46)).toBe('00:46')
    expect(formatVideoCount(2380)).toBe('2380')
    expect(formatVideoCount(12000)).toBe('1.2万')
    expect(formatVideoCount(10000)).toBe('1万')
    expect(formatVideoTags(['编程机器人', '少儿编程'])).toBe('#编程机器人 #少儿编程')
  })

  it('makes the entry card from the first mention as its module reads it now', () => {
    expect(videoEntry(refView())).toEqual({
      kind: '关联商品',
      icon: expect.any(String),
      text: '编程机器人探索套装 ¥1,299起',
      cta: '看商品',
      url: '/pages/mall/product?productId=p1&cityCode=370500',
    })
    expect(videoEntry(refView({ type: 'INSPECTION_SERVICE', title: '园区综合巡检服务',
      price: { amount: '按项目报价', unit: '' }, actionLabel: '看服务' }))?.text).toBe('园区综合巡检服务 · 按项目报价')
    expect(videoEntry(refView({ type: 'ACTIVITY', price: null, actionLabel: '去报名' }))).toMatchObject({
      kind: '关联活动', text: '编程机器人探索套装', cta: '去报名',
    })
    expect(videoEntry(null)).toBeNull()
  })

  it('writes when a comment was posted, in Beijing time', () => {
    const now = new Date('2026-11-20T10:00:00Z')
    expect(formatCommentTime('2026-11-20T09:59:40Z', now)).toBe('刚刚')
    expect(formatCommentTime('2026-11-20T09:15:00Z', now)).toBe('45 分钟前')
    expect(formatCommentTime('2026-11-20T08:00:00Z', now)).toBe('2 小时前')
    expect(formatCommentTime('2026-11-01T18:00:00Z', now)).toBe('11/02')
  })
})

describe('discover video channel', () => {
  it('reads 视频 from the content feed as a waterfall and never asks for the hidden 直播', async () => {
    const calls: string[] = []
    const feed: ContentFeedPage = {
      hasMore: false,
      total: 1,
      topics: [],
      items: [articleCard({ articleId: 'v1', kind: 'VIDEO', video: { durationSeconds: 192, orientation: 'LANDSCAPE' } })],
    }
    const source = createDiscoverFeedSource({
      content: { loadFeed: async (channel) => { calls.push(channel); return feed } },
      activity: { loadPage: async () => { throw new Error('not used') } },
    })

    const videos = await source.loadPage('VIDEO', CITY, 1)
    expect(calls).toEqual(['VIDEO'])
    expect(videos.entries).toEqual([{ kind: 'ARTICLE', key: 'article:v1', article: feed.items[0] }])
    expect(await source.loadPage('LIVE', CITY, 1)).toEqual({ entries: [], hasMore: false, topics: [] })
    expect(calls).toEqual(['VIDEO'])
    expect(isWaterfallChannel('VIDEO')).toBe(true)
    expect(isWaterfallChannel('RECOMMEND')).toBe(true)
    expect(isWaterfallChannel('CASE')).toBe(false)
  })
})
