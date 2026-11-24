import { describe, expect, it, vi } from 'vitest'

import {
  commentRefusal,
  createCommentSheet,
  createVideoFeedSession,
  emptyCommentSheet,
  emptyVideoFeed,
  sendableComment,
  type CommentSheetState,
  type VideoFeedState,
} from '../application/video-feed-session'
import type { ContentVideoApi } from '../data/content-video-api'
import type { ContentComment, ContentVideo, ContentVideoPage } from '../domain/content'
import { CITY } from './content-fixtures'

function video(id: string, overrides: Partial<ContentVideo> = {}): ContentVideo {
  return {
    videoId: id,
    title: `视频 ${id}`,
    author: { kind: 'PROVIDER', organizationId: 'org-1', name: '鹰眼智能巡检' },
    sponsored: false,
    cityCode: CITY,
    coverImage: null,
    sample: true,
    publishedAt: '2026-11-01T02:00:00Z',
    durationSeconds: 60,
    orientation: 'PORTRAIT',
    playUrl: null,
    caption: null,
    tags: [],
    refs: [],
    likeCount: 10,
    commentCount: 2,
    liked: false,
    followingAuthor: false,
    ...overrides,
  }
}

function comment(id: string, overrides: Partial<ContentComment> = {}): ContentComment {
  return { commentId: id, videoId: 'v1', authorName: '王**', byAuthor: false, mine: false, text: '好看',
    createdAt: '2026-11-20T02:00:00Z', ...overrides }
}

class HttpError extends Error {
  constructor(readonly status: number, readonly code: string | null = null) { super(`HTTP ${status}`) }
}

function feedHarness(api: Partial<ContentVideoApi>, token: string | null = 'token') {
  let state: VideoFeedState = emptyVideoFeed('RECOMMEND', CITY)
  const login = vi.fn()
  const notify = vi.fn()
  const session = createVideoFeedSession({
    api: api as ContentVideoApi,
    getAccessToken: () => token,
    getState: () => state,
    setState: (next) => { state = next },
    onLoginRequired: login,
    notify,
    pageSize: 2,
  })
  return { session, state: () => state, login, notify }
}

function page(items: ContentVideo[], nextCursor: string | null = null): ContentVideoPage {
  return { feed: 'RECOMMEND', items, nextCursor }
}

describe('video stream session', () => {
  it('starts at the tapped video and loads the next part before the end', async () => {
    const loadStream = vi.fn()
      .mockResolvedValueOnce(page([video('v1'), video('v2')], 'c1'))
      .mockResolvedValueOnce(page([video('v3')], null))
    const { session, state } = feedHarness({ loadStream })

    await session.load('RECOMMEND', CITY, 'v1')
    expect(loadStream).toHaveBeenCalledWith({ cityCode: CITY, feed: 'RECOMMEND', startVideoId: 'v1', cursor: null, size: 2 }, 'token')
    expect(state().status).toBe('ready')
    expect(state().items.map((item) => item.videoId)).toEqual(['v1', 'v2'])

    await session.next()
    expect(state().index).toBe(1)
    // One left: the next part comes in the background.
    await vi.waitFor(() => expect(state().items).toHaveLength(3))
    expect(loadStream.mock.calls[1][0]).toMatchObject({ cursor: 'c1', startVideoId: null })
    await session.next()
    expect(state().index).toBe(2)
    await session.next()
    expect(state().index).toBe(2)
    session.prev()
    session.prev()
    session.prev()
    expect(state().index).toBe(0)
  })

  it('advances once when next is tapped at the end of a still-loading page', async () => {
    let finish!: (result: ContentVideoPage) => void
    const loadStream = vi.fn()
      .mockResolvedValueOnce(page([video('v1'), video('v2')], 'c1'))
      .mockReturnValueOnce(new Promise<ContentVideoPage>((resolve) => { finish = resolve }))
    const { session, state } = feedHarness({ loadStream })
    await session.load('RECOMMEND', CITY, null)
    await session.next()
    expect(state().loadingMore).toBe(true)

    const next = session.next()
    const repeated = session.next()
    await Promise.resolve()
    finish(page([video('v3'), video('v4')]))
    await Promise.all([next, repeated])
    expect(loadStream).toHaveBeenCalledTimes(2)
    expect(state().items[state().index].videoId).toBe('v3')
  })

  it('says when the start is gone, when the following stream needs a login, and when nothing is there', async () => {
    const loadStream = vi.fn()
      .mockRejectedValueOnce(new HttpError(404))
      .mockResolvedValueOnce(page([]))
      .mockRejectedValueOnce(new Error('offline'))
    const { session, state } = feedHarness({ loadStream })

    await session.load('RECOMMEND', CITY, 'gone')
    expect(state().status).toBe('missing')
    await session.load('FOLLOWING', CITY, null)
    expect(state().status).toBe('empty')
    expect(state().feed).toBe('FOLLOWING')
    await session.load('RECOMMEND', CITY, null)
    expect(state().status).toBe('error')

    const anonymous = feedHarness({ loadStream }, null)
    await anonymous.session.load('FOLLOWING', CITY, null)
    expect(anonymous.state().status).toBe('login')
    expect(loadStream).toHaveBeenCalledTimes(3)
  })

  it('drops the answer of an older load', async () => {
    let resolveFirst!: (value: ContentVideoPage) => void
    const loadStream = vi.fn()
      .mockReturnValueOnce(new Promise<ContentVideoPage>((resolve) => { resolveFirst = resolve }))
      .mockResolvedValueOnce(page([video('f1')]))
    const { session, state } = feedHarness({ loadStream })

    const first = session.load('RECOMMEND', CITY, null)
    await session.load('FOLLOWING', CITY, null)
    resolveFirst(page([video('r1')]))
    await first
    expect(state().items.map((item) => item.videoId)).toEqual(['f1'])
  })

  it('likes at once, keeps the server count, and goes back when refused', async () => {
    const setLiked = vi.fn()
      .mockResolvedValueOnce({ liked: true, likeCount: 15 })
      .mockRejectedValueOnce(new Error('offline'))
    const { session, state, notify } = feedHarness({ loadStream: vi.fn().mockResolvedValue(page([video('v1')])), setLiked })
    await session.load('RECOMMEND', CITY, null)

    const pending = session.toggleLike()
    expect(state().items[0]).toMatchObject({ liked: true, likeCount: 11 })
    await pending
    expect(state().items[0]).toMatchObject({ liked: true, likeCount: 15 })

    await session.toggleLike()
    expect(state().items[0]).toMatchObject({ liked: true, likeCount: 15 })
    expect(notify).toHaveBeenCalledWith('操作失败，请稍后重试')
  })

  it('asks for a login before a like or a follow, and after a 401', async () => {
    const loadStream = vi.fn().mockResolvedValue(page([video('v1')]))
    const anonymous = feedHarness({ loadStream, setLiked: vi.fn(), setFollowing: vi.fn() }, null)
    await anonymous.session.load('RECOMMEND', CITY, null)
    await anonymous.session.toggleLike()
    await anonymous.session.toggleFollow()
    expect(anonymous.login).toHaveBeenCalledTimes(2)

    const expired = feedHarness({ loadStream, setLiked: vi.fn().mockRejectedValue(new HttpError(401)) })
    await expired.session.load('RECOMMEND', CITY, null)
    await expired.session.toggleLike()
    expect(expired.login).toHaveBeenCalledTimes(1)
    expect(expired.state().items[0].liked).toBe(false)
  })

  it('follows the author on every video of theirs and never the platform', async () => {
    const setFollowing = vi.fn().mockResolvedValue(true)
    const { session, state, notify } = feedHarness({
      loadStream: vi.fn().mockResolvedValue(page([
        video('v1'),
        video('v2', { author: { kind: 'MERCHANT', organizationId: 'org-2', name: '示例机器人商家' } }),
        video('v3'),
        video('v4', { author: { kind: 'PLATFORM', organizationId: null, name: '东营城市运营中心' } }),
      ])),
      setFollowing,
    })
    await session.load('RECOMMEND', CITY, null)

    await session.toggleFollow()
    expect(setFollowing).toHaveBeenCalledWith('org-1', true, 'token')
    expect(state().items.map((item) => item.followingAuthor)).toEqual([true, false, true, false])
    expect(notify).toHaveBeenCalledWith('已关注')

    state().index = 3
    await session.toggleFollow()
    expect(setFollowing).toHaveBeenCalledTimes(1)
  })

  it('counts a posted comment on the side', async () => {
    const { session, state } = feedHarness({ loadStream: vi.fn().mockResolvedValue(page([video('v1')])) })
    await session.load('RECOMMEND', CITY, null)
    session.commentAdded('v1')
    expect(state().items[0].commentCount).toBe(3)
  })
})

function sheetHarness(api: Partial<ContentVideoApi>, token: string | null = 'token') {
  let state: CommentSheetState = emptyCommentSheet('v1')
  const login = vi.fn()
  const notify = vi.fn()
  const posted = vi.fn()
  let keys = 0
  const sheet = createCommentSheet({
    api: api as ContentVideoApi,
    getAccessToken: () => token,
    getState: () => state,
    setState: (next) => { state = next },
    createKey: () => `key-${++keys}`,
    onLoginRequired: login,
    notify,
    onPosted: posted,
    pageSize: 2,
  })
  return { sheet, state: () => state, login, notify, posted }
}

describe('comment sheet', () => {
  it('lists newest first page by page and puts a posted comment on top', async () => {
    const loadComments = vi.fn()
      .mockResolvedValueOnce({ total: 3, hasMore: true, items: [comment('c3'), comment('c2')] })
      .mockResolvedValueOnce({ total: 3, hasMore: false, items: [comment('c2'), comment('c1')] })
    const postComment = vi.fn().mockResolvedValue(comment('c4', { mine: true, text: '我也想去' }))
    const { sheet, state, posted } = sheetHarness({ loadComments, postComment })

    await sheet.open('v1')
    await sheet.loadMore()
    expect(state().items.map((item) => item.commentId)).toEqual(['c3', 'c2', 'c1'])
    expect(state().hasMore).toBe(false)

    expect(await sheet.send('  我也想去 ')).toBe(true)
    expect(postComment).toHaveBeenCalledWith('v1', 'key-1', '我也想去', 'token')
    expect(state().items[0].commentId).toBe('c4')
    expect(state().total).toBe(4)
    expect(posted).toHaveBeenCalledWith('v1')
  })

  it('keeps the key for a retry of the same text and says why a comment was refused', async () => {
    const postComment = vi.fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(comment('c9', { mine: true }))
      .mockRejectedValueOnce(new HttpError(422, 'CONTENT_COMMENT_BLOCKED'))
      .mockRejectedValueOnce(new HttpError(429, 'CONTENT_COMMENT_TOO_FREQUENT'))
    const { sheet, state } = sheetHarness({
      loadComments: vi.fn().mockResolvedValue({ total: 0, hasMore: false, items: [] }),
      postComment,
    })
    await sheet.open('v1')

    expect(await sheet.send('好看')).toBe(false)
    expect(state().sendError).toBe('发送失败，请检查网络后重试')
    expect(await sheet.send('好看')).toBe(true)
    expect(postComment.mock.calls[0][1]).toBe(postComment.mock.calls[1][1])

    expect(await sheet.send('加微信')).toBe(false)
    expect(state().sendError).toContain('不允许发布')
    expect(await sheet.send('再来一条')).toBe(false)
    expect(state().sendError).toBe('评论太频繁，请稍后再试')
    expect(postComment.mock.calls[3][1]).not.toBe(postComment.mock.calls[2][1])
  })

  it('asks for a login to comment or report, and reports once with the reason', async () => {
    const reportComment = vi.fn().mockResolvedValue('ABUSE')
    const anonymous = sheetHarness({
      loadComments: vi.fn().mockResolvedValue({ total: 0, hasMore: false, items: [] }),
      postComment: vi.fn(),
      reportComment,
    }, null)
    await anonymous.sheet.open('v1')
    expect(await anonymous.sheet.send('好看')).toBe(false)
    await anonymous.sheet.report('c1', 'ABUSE')
    expect(anonymous.login).toHaveBeenCalledTimes(2)
    expect(reportComment).not.toHaveBeenCalled()

    const signedIn = sheetHarness({ reportComment })
    await signedIn.sheet.report('c1', 'ABUSE')
    expect(reportComment).toHaveBeenCalledWith('c1', 'ABUSE', 'token')
    expect(signedIn.notify).toHaveBeenCalledWith('已收到举报，平台会尽快核实')
  })

  it('sends one line of at most 200 characters', () => {
    expect(sendableComment('  好看\n真好看 ')).toBe('好看 真好看')
    expect(sendableComment('   ')).toBeNull()
    expect(sendableComment('好'.repeat(200))).toHaveLength(200)
    expect(sendableComment('好'.repeat(201))).toBeNull()
    expect(commentRefusal(new HttpError(404))).toBe('视频已下线，不能再评论')
  })
})
