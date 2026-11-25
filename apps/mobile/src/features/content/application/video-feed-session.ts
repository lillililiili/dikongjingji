import type { ContentVideoApi } from '../data/content-video-api'
import {
  CONTENT_COMMENT_LIMIT,
  CONTENT_COMMENT_PAGE_SIZE,
  CONTENT_VIDEO_STREAM_SIZE,
  type ContentComment,
  type ContentCommentReportReason,
  type ContentVideo,
  type ContentVideoFeed,
} from '../domain/content'

// The vertical stream (board VideoFeed) and its comment sheet, without the view: what is on
// screen, which one is current, and what tapping 点赞 / 关注 / 发送 / 举报 does. Likes and follows
// change on screen at once and go back if the server says no.

const statusOf = (error: unknown): number | null =>
  typeof error === 'object' && error !== null && typeof (error as { status?: unknown }).status === 'number'
    ? (error as { status: number }).status
    : null

const codeOf = (error: unknown): string | null =>
  typeof error === 'object' && error !== null && typeof (error as { code?: unknown }).code === 'string'
    ? (error as { code: string }).code
    : null

export type VideoFeedStatus =
  | 'loading'
  | 'ready'
  // Nothing in this stream: no videos in the city, or no followed authors yet.
  | 'empty'
  // 关注 without a login.
  | 'login'
  // The video the stream was opened at is gone, or not shown in this city.
  | 'missing'
  | 'error'

export interface VideoFeedState {
  status: VideoFeedStatus
  feed: ContentVideoFeed
  cityCode: string
  items: ContentVideo[]
  index: number
  nextCursor: string | null
  loadingMore: boolean
}

export function emptyVideoFeed(feed: ContentVideoFeed, cityCode: string): VideoFeedState {
  return { status: 'loading', feed, cityCode, items: [], index: 0, nextCursor: null, loadingMore: false }
}

export function currentVideo(state: VideoFeedState): ContentVideo | null {
  return state.status === 'ready' ? state.items[state.index] ?? null : null
}

export interface VideoFeedOptions {
  api: Pick<ContentVideoApi, 'loadStream' | 'setLiked' | 'setFollowing'>
  getAccessToken(): string | null
  getState(): VideoFeedState
  setState(state: VideoFeedState): void
  /** A write needs a login: the page goes to sign in and comes back to the current video. */
  onLoginRequired(): void
  /** A short message for a toast. */
  notify(message: string): void
  pageSize?: number
}

export interface VideoFeedSession {
  load(feed: ContentVideoFeed, cityCode: string, startVideoId: string | null): Promise<void>
  next(): Promise<void>
  prev(): void
  toggleLike(): Promise<void>
  toggleFollow(): Promise<void>
  /** A comment was posted from the sheet: the count on the side follows. */
  commentAdded(videoId: string): void
}

/** Loads more when the current one is this close to the end. */
const PRELOAD_DISTANCE = 1

export function createVideoFeedSession(options: VideoFeedOptions): VideoFeedSession {
  const size = options.pageSize ?? CONTENT_VIDEO_STREAM_SIZE
  let revision = 0
  let pendingPage: Promise<void> = Promise.resolve()
  const pendingLikes = new Set<string>()
  const pendingFollows = new Set<string>()

  function patch(change: (state: VideoFeedState) => VideoFeedState): void {
    options.setState(change(options.getState()))
  }

  function mapVideos(change: (video: ContentVideo) => ContentVideo): void {
    patch((state) => ({ ...state, items: state.items.map(change) }))
  }

  async function loadMore(): Promise<void> {
    const state = options.getState()
    if (state.status !== 'ready' || !state.nextCursor || state.loadingMore) return
    const requested = revision
    options.setState({ ...state, loadingMore: true })
    try {
      const page = await options.api.loadStream({
        cityCode: state.cityCode,
        feed: state.feed,
        startVideoId: null,
        cursor: state.nextCursor,
        size,
      }, options.getAccessToken())
      if (requested !== revision) return
      patch((current) => {
        const known = new Set(current.items.map((item) => item.videoId))
        return {
          ...current,
          items: [...current.items, ...page.items.filter((item) => !known.has(item.videoId))],
          nextCursor: page.nextCursor,
          loadingMore: false,
        }
      })
    } catch {
      if (requested !== revision) return
      // The next swipe tries again.
      patch((current) => ({ ...current, loadingMore: false }))
    }
  }

  function requestMore(): Promise<void> {
    if (!options.getState().loadingMore) pendingPage = loadMore()
    return pendingPage
  }

  return {
    async load(feed, cityCode, startVideoId) {
      const requested = ++revision
      const token = options.getAccessToken()
      if (feed === 'FOLLOWING' && !token) {
        options.setState({ ...emptyVideoFeed(feed, cityCode), status: 'login' })
        return
      }
      options.setState(emptyVideoFeed(feed, cityCode))
      try {
        const page = await options.api.loadStream({ cityCode, feed, startVideoId, cursor: null, size }, token)
        if (requested !== revision) return
        options.setState({
          ...emptyVideoFeed(feed, cityCode),
          status: page.items.length ? 'ready' : 'empty',
          items: page.items,
          nextCursor: page.nextCursor,
        })
      } catch (error) {
        if (requested !== revision) return
        const status = statusOf(error)
        options.setState({
          ...emptyVideoFeed(feed, cityCode),
          status: status === 401 ? 'login' : status === 404 && startVideoId ? 'missing' : 'error',
        })
      }
    },

    async next() {
      const state = options.getState()
      if (state.status !== 'ready') return
      if (state.index < state.items.length - 1) {
        options.setState({ ...state, index: state.index + 1 })
      } else if (state.nextCursor) {
        const requested = revision
        await requestMore()
        const after = options.getState()
        if (requested === revision && after.index === state.index && after.index < after.items.length - 1) {
          options.setState({ ...after, index: after.index + 1 })
        }
        return
      }
      const moved = options.getState()
      if (moved.items.length - 1 - moved.index <= PRELOAD_DISTANCE) void requestMore()
    },

    prev() {
      const state = options.getState()
      if (state.status === 'ready' && state.index > 0) options.setState({ ...state, index: state.index - 1 })
    },

    async toggleLike() {
      const video = currentVideo(options.getState())
      if (!video || pendingLikes.has(video.videoId)) return
      const token = options.getAccessToken()
      if (!token) {
        options.onLoginRequired()
        return
      }
      const want = !video.liked
      const id = video.videoId
      pendingLikes.add(id)
      mapVideos((item) => (item.videoId === id
        ? { ...item, liked: want, likeCount: Math.max(0, item.likeCount + (want ? 1 : -1)) }
        : item))
      try {
        const result = await options.api.setLiked(id, want, token)
        mapVideos((item) => (item.videoId === id ? { ...item, liked: result.liked, likeCount: result.likeCount } : item))
      } catch (error) {
        mapVideos((item) => (item.videoId === id ? { ...item, liked: video.liked, likeCount: video.likeCount } : item))
        if (statusOf(error) === 401) options.onLoginRequired()
        else options.notify(statusOf(error) === 404 ? '视频已下线' : '操作失败，请稍后重试')
      } finally {
        pendingLikes.delete(id)
      }
    },

    async toggleFollow() {
      const video = currentVideo(options.getState())
      const organizationId = video?.author.organizationId
      if (!video || !organizationId || pendingFollows.has(organizationId)) return
      const token = options.getAccessToken()
      if (!token) {
        options.onLoginRequired()
        return
      }
      const want = !video.followingAuthor
      pendingFollows.add(organizationId)
      // Every video of this author on screen follows along.
      const setFollowing = (following: boolean) => mapVideos((item) => (
        item.author.organizationId === organizationId ? { ...item, followingAuthor: following } : item))
      setFollowing(want)
      try {
        setFollowing(await options.api.setFollowing(organizationId, want, token))
        options.notify(want ? '已关注' : '已取消关注')
      } catch (error) {
        setFollowing(!want)
        if (statusOf(error) === 401) options.onLoginRequired()
        else options.notify('操作失败，请稍后重试')
      } finally {
        pendingFollows.delete(organizationId)
      }
    },

    commentAdded(videoId) {
      mapVideos((item) => (item.videoId === videoId ? { ...item, commentCount: item.commentCount + 1 } : item))
    },
  }
}

// ---- The comment sheet ----

export interface CommentSheetState {
  status: 'loading' | 'ready' | 'error'
  videoId: string
  items: ContentComment[]
  total: number
  hasMore: boolean
  page: number
  loadingMore: boolean
  sending: boolean
  // The reason the last comment was not taken, shown above the input.
  sendError: string | null
}

export function emptyCommentSheet(videoId: string): CommentSheetState {
  return {
    status: 'loading',
    videoId,
    items: [],
    total: 0,
    hasMore: false,
    page: 0,
    loadingMore: false,
    sending: false,
    sendError: null,
  }
}

/** Text the input may send: trimmed, not empty, at most 200 characters, one line. */
export function sendableComment(draft: string): string | null {
  const text = draft.replace(/[\r\n]+/g, ' ').trim()
  return text && Array.from(text).length <= CONTENT_COMMENT_LIMIT ? text : null
}

export function commentRefusal(error: unknown): string {
  switch (codeOf(error)) {
    case 'CONTENT_COMMENT_BLOCKED': return '评论里有不允许发布的内容（如联系方式），换个说法试试'
    case 'CONTENT_COMMENT_TOO_FREQUENT': return '评论太频繁，请稍后再试'
    default: break
  }
  if (statusOf(error) === 404) return '视频已下线，不能再评论'
  if (statusOf(error) === 400) return '评论不能为空，最多 200 字'
  return '发送失败，请检查网络后重试'
}

export interface CommentSheetOptions {
  api: Pick<ContentVideoApi, 'loadComments' | 'postComment' | 'reportComment'>
  getAccessToken(): string | null
  getState(): CommentSheetState
  setState(state: CommentSheetState): void
  createKey(): string
  onLoginRequired(): void
  notify(message: string): void
  /** Told after a comment was posted (the stream's count goes up). */
  onPosted?(videoId: string): void
  pageSize?: number
}

export interface CommentSheet {
  open(videoId: string): Promise<void>
  loadMore(): Promise<void>
  /** True when the comment was posted (the input clears). */
  send(draft: string): Promise<boolean>
  report(commentId: string, reason: ContentCommentReportReason): Promise<void>
}

export function createCommentSheet(options: CommentSheetOptions): CommentSheet {
  const size = options.pageSize ?? CONTENT_COMMENT_PAGE_SIZE
  let revision = 0
  // A comment that failed keeps its key: sending the same text again cannot post it twice.
  let pending: { videoId: string; text: string; key: string } | null = null

  return {
    async open(videoId) {
      const requested = ++revision
      options.setState(emptyCommentSheet(videoId))
      try {
        const page = await options.api.loadComments(videoId, 1, size, options.getAccessToken())
        if (requested !== revision) return
        options.setState({ ...emptyCommentSheet(videoId), status: 'ready', items: page.items, total: page.total,
          hasMore: page.hasMore, page: 1 })
      } catch {
        if (requested !== revision) return
        options.setState({ ...emptyCommentSheet(videoId), status: 'error' })
      }
    },

    async loadMore() {
      const state = options.getState()
      if (state.status !== 'ready' || !state.hasMore || state.loadingMore) return
      const requested = revision
      options.setState({ ...state, loadingMore: true })
      try {
        const page = await options.api.loadComments(state.videoId, state.page + 1, size, options.getAccessToken())
        if (requested !== revision) return
        const current = options.getState()
        const known = new Set(current.items.map((item) => item.commentId))
        options.setState({
          ...current,
          items: [...current.items, ...page.items.filter((item) => !known.has(item.commentId))],
          hasMore: page.hasMore,
          page: state.page + 1,
          loadingMore: false,
        })
      } catch {
        if (requested !== revision) return
        options.setState({ ...options.getState(), loadingMore: false })
      }
    },

    async send(draft) {
      const state = options.getState()
      const text = sendableComment(draft)
      if (state.status !== 'ready' || state.sending || !text) return false
      const token = options.getAccessToken()
      if (!token) {
        options.onLoginRequired()
        return false
      }
      if (!pending || pending.videoId !== state.videoId || pending.text !== text) {
        pending = { videoId: state.videoId, text, key: options.createKey() }
      }
      const requested = revision
      options.setState({ ...state, sending: true, sendError: null })
      try {
        const comment = await options.api.postComment(state.videoId, pending.key, text, token)
        pending = null
        if (requested !== revision) return true
        const current = options.getState()
        const fresh = !current.items.some((item) => item.commentId === comment.commentId)
        options.setState({
          ...current,
          items: fresh ? [comment, ...current.items] : current.items,
          total: current.total + (fresh ? 1 : 0),
          sending: false,
          sendError: null,
        })
        if (fresh) options.onPosted?.(state.videoId)
        return true
      } catch (error) {
        // Refused for what it says: a new text gets a new key anyway; the same text is refused again.
        if (codeOf(error) === 'CONTENT_COMMENT_BLOCKED') pending = null
        if (requested !== revision) return false
        if (statusOf(error) === 401) {
          options.setState({ ...options.getState(), sending: false })
          options.onLoginRequired()
          return false
        }
        options.setState({ ...options.getState(), sending: false, sendError: commentRefusal(error) })
        return false
      }
    },

    async report(commentId, reason) {
      const token = options.getAccessToken()
      if (!token) {
        options.onLoginRequired()
        return
      }
      try {
        await options.api.reportComment(commentId, reason, token)
        options.notify('已收到举报，平台会尽快核实')
      } catch (error) {
        if (statusOf(error) === 401) options.onLoginRequired()
        else options.notify(statusOf(error) === 404 ? '这条评论已不在了' : '举报失败，请稍后重试')
      }
    },
  }
}
