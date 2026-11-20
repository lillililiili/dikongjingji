import { describe, expect, it, vi } from 'vitest'

import { createContentApi } from '../data/content-api'
import { createContentVideoApi } from '../data/content-video-api'
import {
  resolveVideoPreviewCover,
  resolveVideoPreviewSource,
  videoPlaySource,
  videoPoster,
} from '../data/preview/content-preview-videos'
import { CITY, articleCardDto } from './content-fixtures'

const VIDEO = 'f3200000-0000-4000-8000-000000000001'

function videoDto(overrides: Record<string, unknown> = {}) {
  return {
    videoId: VIDEO,
    title: '3 分钟看懂编程机器人',
    author: { kind: 'MERCHANT', organizationId: 'c1000000-0000-4000-8000-000000000001', name: '示例机器人商家' },
    sponsored: false,
    cityCode: CITY,
    coverImage: null,
    sample: true,
    publishedAt: '2026-11-04T02:00:00Z',
    durationSeconds: 192,
    orientation: 'LANDSCAPE',
    playUrl: null,
    caption: ' 它是怎么“看见”障碍的？ ',
    tags: ['编程机器人', '#少儿编程', ' '],
    refs: [
      { type: 'PRODUCT', refId: 'c2000000-0000-4000-8000-000000000101', title: '编程机器人探索套装' },
      { type: 'PRODUCT', refId: 'c2000000-0000-4000-8000-000000000101', title: '重复' },
    ],
    likeCount: 12,
    commentCount: 3,
    liked: true,
    followingAuthor: true,
    ...overrides,
  }
}

class HttpError extends Error {
  constructor(readonly status: number, readonly code: string | null = null) { super(`HTTP ${status}`) }
}

describe('content video api', () => {
  it('maps a video: merchants are ads, only HTTPS plays, tags without #, one copy of each mention', async () => {
    const request = vi.fn().mockResolvedValue(videoDto({ playUrl: 'http://media.example.com/a.mp4' }))
    const api = createContentVideoApi(request)

    const video = await api.loadVideo(VIDEO, CITY, null)

    expect(request).toHaveBeenCalledWith({ method: 'GET', url: `/api/v1/content/videos/${VIDEO}?cityCode=${CITY}` })
    expect(video.sponsored).toBe(true)
    expect(video.playUrl).toBeNull()
    expect(video.caption).toBe('它是怎么“看见”障碍的？')
    expect(video.tags).toEqual(['编程机器人', '少儿编程'])
    expect(video.refs).toHaveLength(1)
    expect(video.orientation).toBe('LANDSCAPE')
    expect(video.liked).toBe(true)
    expect(video.followingAuthor).toBe(true)

    request.mockResolvedValue(videoDto({
      playUrl: 'https://media.example.com/a.mp4',
      author: { kind: 'PLATFORM', organizationId: null, name: '东营城市运营中心' },
      followingAuthor: true,
      likeCount: -1,
    }))
    const platform = await api.loadVideo(VIDEO, CITY, null)
    expect(platform.playUrl).toBe('https://media.example.com/a.mp4')
    // The platform has nobody to follow.
    expect(platform.followingAuthor).toBe(false)
    expect(platform.likeCount).toBe(0)
  })

  it('reads with the token and again without it when the server no longer takes it', async () => {
    const request = vi.fn()
      .mockRejectedValueOnce(new HttpError(401))
      .mockResolvedValueOnce({ cityCode: CITY, feed: 'RECOMMEND', items: [videoDto(), videoDto()], nextCursor: 'c2' })
    const api = createContentVideoApi(request)

    const page = await api.loadStream({ cityCode: CITY, feed: 'RECOMMEND', startVideoId: VIDEO, cursor: null, size: 5 }, 'old')

    expect(request.mock.calls[0][0]).toMatchObject({
      url: `/api/v1/content/videos?cityCode=${CITY}&feed=RECOMMEND&startVideoId=${VIDEO}&size=5`,
      headers: { Authorization: 'Bearer old' },
    })
    expect(request.mock.calls[1][0]).not.toHaveProperty('headers')
    expect(page.items).toHaveLength(1)
    expect(page.nextCursor).toBe('c2')
  })

  it('sends the cursor instead of the start and never retries the following stream without the token', async () => {
    const request = vi.fn().mockRejectedValue(new HttpError(401))
    const api = createContentVideoApi(request)

    await expect(api.loadStream({ cityCode: CITY, feed: 'FOLLOWING', startVideoId: VIDEO, cursor: 'c1', size: 5 }, 'token'))
      .rejects.toMatchObject({ status: 401 })
    expect(request).toHaveBeenCalledTimes(1)
    expect(request.mock.calls[0][0].url).toBe(`/api/v1/content/videos?cityCode=${CITY}&feed=FOLLOWING&cursor=c1&size=5`)
  })

  it('likes, follows, comments and reports with the token and the idempotency key', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce({ videoId: VIDEO, liked: true, likeCount: 13 })
      .mockResolvedValueOnce({ organizationId: 'o1', following: false })
      .mockResolvedValueOnce({ commentId: 'c1', videoId: VIDEO, authorName: '王**', byAuthor: false, mine: true,
        text: '好看', createdAt: '2026-11-20T02:00:00Z' })
      .mockResolvedValueOnce({ commentId: 'c1', reason: 'ABUSE', reportedAt: '2026-11-20T02:00:00Z' })
      .mockResolvedValueOnce({ total: 2, hasMore: false, items: [
        { commentId: 'c1', videoId: VIDEO, authorName: '', text: '好看', createdAt: '2026-11-20T02:00:00Z' },
        { commentId: 'c2', videoId: VIDEO, authorName: '李**', text: ' ', createdAt: '2026-11-20T02:00:00Z' },
      ] })
    const api = createContentVideoApi(request)

    expect(await api.setLiked(VIDEO, true, 't')).toEqual({ liked: true, likeCount: 13 })
    expect(request.mock.calls[0][0]).toMatchObject({ method: 'PUT', url: `/api/v1/content/videos/${VIDEO}/like` })
    expect(await api.setFollowing('o1', false, 't')).toBe(false)
    expect(request.mock.calls[1][0]).toMatchObject({ method: 'DELETE', url: '/api/v1/content/follows/o1' })
    expect((await api.postComment(VIDEO, 'k1', '好看', 't')).mine).toBe(true)
    expect(request.mock.calls[2][0]).toMatchObject({
      method: 'POST',
      data: { text: '好看' },
      headers: { Authorization: 'Bearer t', 'Idempotency-Key': 'k1' },
    })
    expect(await api.reportComment('c1', 'ABUSE', 't')).toBe('ABUSE')
    expect(request.mock.calls[3][0]).toMatchObject({ url: '/api/v1/content/comments/c1/reports', data: { reason: 'ABUSE' } })
    // A comment with no text is dropped; a missing name reads 用户.
    const comments = await api.loadComments(VIDEO, 1, 20, null)
    expect(comments.items).toEqual([expect.objectContaining({ commentId: 'c1', authorName: '用户' })])
    expect(request.mock.calls[4][0].url).toBe(`/api/v1/content/videos/${VIDEO}/comments?page=1&size=20`)
  })

  it('reads a video card of the feed with its duration and shape; articles have none', async () => {
    const request = vi.fn().mockResolvedValue({
      channel: 'RECOMMEND', cityCode: CITY, page: 1, size: 10, total: 3, hasMore: false, topics: [],
      items: [
        articleCardDto({ articleId: VIDEO, kind: 'VIDEO', video: { durationSeconds: 192, orientation: 'LANDSCAPE' } }),
        articleCardDto({ articleId: 'v2', kind: 'VIDEO', video: { durationSeconds: 0, orientation: 'PORTRAIT' } }),
        articleCardDto({ video: { durationSeconds: 10, orientation: 'PORTRAIT' } }),
      ],
    })
    const page = await createContentApi({ request }).loadFeed('RECOMMEND', CITY, 1, 10)

    expect(page.items.map((item) => item.video)).toEqual([
      { durationSeconds: 192, orientation: 'LANDSCAPE' },
      null,
      null,
    ])
  })
})

describe('preview sample videos', () => {
  it('stand in for the seeded videos on preview builds only; a server address wins', () => {
    expect(resolveVideoPreviewSource(VIDEO, true)).toBe('/static/videos/preview/robot-coding.mp4')
    expect(resolveVideoPreviewCover(VIDEO, true)?.url).toBe('/static/images/preview/video-robot-coding.jpg')
    expect(resolveVideoPreviewSource(VIDEO, false)).toBeNull()
    expect(resolveVideoPreviewSource('unknown', true)).toBeNull()
    expect(videoPlaySource({ videoId: VIDEO, playUrl: 'https://media.example.com/a.mp4' }, true))
      .toBe('https://media.example.com/a.mp4')
    expect(videoPlaySource({ videoId: VIDEO, playUrl: null }, false)).toBeNull()
    expect(videoPoster({ videoId: VIDEO, coverImage: { url: 'https://img.example.com/a.jpg', alt: '封面' } }, true)?.url)
      .toBe('https://img.example.com/a.jpg')
  })

  it('cover all six seeded videos with files that exist', () => {
    const files = import.meta.glob('../../../static/videos/preview/*.mp4', { eager: true, query: '?url', import: 'default' })
    const posters = import.meta.glob('../../../static/images/preview/video-*.jpg', { eager: true, query: '?url', import: 'default' })
    for (let index = 1; index <= 6; index += 1) {
      const id = `f3200000-0000-4000-8000-00000000000${index}`
      const clip = resolveVideoPreviewSource(id, true)?.split('/').pop()
      const poster = resolveVideoPreviewCover(id, true)?.url.split('/').pop()
      expect(Object.keys(files).some((path) => path.endsWith(`/${clip}`)), clip).toBe(true)
      expect(Object.keys(posters).some((path) => path.endsWith(`/${poster}`)), poster).toBe(true)
    }
  })
})
