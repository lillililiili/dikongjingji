import {
  CONTENT_COMMENT_REPORT_REASONS,
  type ContentComment,
  type ContentCommentPage,
  type ContentCommentReportReason,
  type ContentRefType,
  type ContentVideo,
  type ContentVideoFeed,
  type ContentVideoOrientation,
  type ContentVideoPage,
} from '../domain/content'
import { toAuthor, toImage, toRefs } from './content-api'

// contracts/openapi-content.yaml 1.2.0: video detail, the vertical stream, likes, follows and
// comments. Reads work without a login and say more with one; writes need one.

type VideoRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST' | 'PUT' | 'DELETE'
  data?: unknown
  headers?: Record<string, string>
}) => Promise<T>

interface MediaDto { url: string; alt: string }
interface AuthorDto { kind: 'PROVIDER' | 'MERCHANT' | 'PLATFORM'; organizationId: string | null; name: string }

interface VideoDto {
  videoId: string
  title: string
  author: AuthorDto
  sponsored: boolean
  cityCode: string
  coverImage: MediaDto | null
  sample: boolean
  publishedAt: string
  durationSeconds: number
  orientation: ContentVideoOrientation
  playUrl: string | null
  caption: string | null
  tags?: string[]
  refs?: Array<{ type: ContentRefType; refId: string; title: string }>
  likeCount?: number
  commentCount?: number
  liked?: boolean
  followingAuthor?: boolean
}

interface VideoPageDto { cityCode: string; feed: ContentVideoFeed; items?: VideoDto[]; nextCursor?: string | null }
interface LikeDto { videoId: string; liked: boolean; likeCount: number }
interface FollowDto { organizationId: string; following: boolean }
interface CommentDto {
  commentId: string
  videoId: string
  authorName?: string
  byAuthor?: boolean
  mine?: boolean
  text?: string
  createdAt?: string
}
interface CommentPageDto { total?: number; hasMore?: boolean; items?: CommentDto[] }
interface ReportDto { commentId: string; reason: ContentCommentReportReason; reportedAt: string }

export interface StreamQuery {
  cityCode: string
  feed: ContentVideoFeed
  // Where the stream starts (the card tapped on 发现); ignored when a cursor is given.
  startVideoId: string | null
  cursor: string | null
  size: number
}

export interface ContentVideoApi {
  loadStream(query: StreamQuery, accessToken: string | null): Promise<ContentVideoPage>
  loadVideo(videoId: string, cityCode: string, accessToken: string | null): Promise<ContentVideo>
  setLiked(videoId: string, liked: boolean, accessToken: string): Promise<{ liked: boolean; likeCount: number }>
  setFollowing(organizationId: string, following: boolean, accessToken: string): Promise<boolean>
  loadComments(videoId: string, page: number, size: number, accessToken: string | null): Promise<ContentCommentPage>
  postComment(videoId: string, idempotencyKey: string, text: string, accessToken: string): Promise<ContentComment>
  reportComment(commentId: string, reason: ContentCommentReportReason, accessToken: string): Promise<ContentCommentReportReason>
}

const count = (value: unknown): number => (typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0)

// Only an HTTPS address with a host may reach a <video>; anything else is no address.
function httpsUrl(url: string | null | undefined): string | null {
  return url && /^https:\/\/[^/\s]+/i.test(url.trim()) ? url.trim() : null
}

export function toContentVideo(dto: VideoDto): ContentVideo {
  const author = toAuthor(dto.author)
  return {
    videoId: dto.videoId,
    title: dto.title,
    author,
    // Merchants' content is always an advertisement, whatever a row says.
    sponsored: dto.sponsored || dto.author.kind === 'MERCHANT',
    cityCode: dto.cityCode,
    coverImage: toImage(dto.coverImage),
    sample: dto.sample,
    publishedAt: dto.publishedAt,
    durationSeconds: Math.max(1, Math.floor(dto.durationSeconds || 1)),
    orientation: dto.orientation === 'LANDSCAPE' ? 'LANDSCAPE' : 'PORTRAIT',
    playUrl: httpsUrl(dto.playUrl),
    caption: dto.caption?.trim() || null,
    tags: (dto.tags ?? []).map((tag) => tag.replace(/^#+/, '').trim()).filter(Boolean),
    refs: toRefs(dto.refs ?? []),
    likeCount: count(dto.likeCount),
    commentCount: count(dto.commentCount),
    liked: dto.liked === true,
    // The platform has no organization to follow.
    followingAuthor: dto.followingAuthor === true && author.organizationId !== null,
  }
}

export function toContentComment(dto: CommentDto): ContentComment | null {
  const text = dto.text?.trim()
  if (!dto.commentId || !text || !dto.createdAt) return null
  return {
    commentId: dto.commentId,
    videoId: dto.videoId,
    authorName: dto.authorName?.trim() || '用户',
    byAuthor: dto.byAuthor === true,
    mine: dto.mine === true,
    text,
    createdAt: dto.createdAt,
  }
}

const statusOf = (error: unknown): number | null =>
  typeof error === 'object' && error !== null && typeof (error as { status?: unknown }).status === 'number'
    ? (error as { status: number }).status
    : null

function query(parameters: Record<string, string | number | null>): string {
  return Object.entries(parameters)
    .filter(([, value]) => value !== null && value !== '')
    .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
    .join('&')
}

export function createContentVideoApi(request: VideoRequest): ContentVideoApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })

  // A public read sent with a token the server no longer accepts answers 401; the same read
  // without it still works, it only loses the viewer's own marks.
  async function read<T>(url: string, accessToken: string | null): Promise<T> {
    if (!accessToken) return request<T>({ method: 'GET', url })
    try {
      return await request<T>({ method: 'GET', url, headers: auth(accessToken) })
    } catch (error) {
      if (statusOf(error) !== 401) throw error
      return request<T>({ method: 'GET', url })
    }
  }

  return {
    async loadStream(stream, accessToken) {
      const parameters = query({
        cityCode: stream.cityCode,
        feed: stream.feed,
        startVideoId: stream.cursor ? null : stream.startVideoId,
        cursor: stream.cursor,
        size: stream.size,
      })
      // The following stream is the signed-in account's own: never retried without the token.
      const dto = stream.feed === 'FOLLOWING' && accessToken
        ? await request<VideoPageDto>({ method: 'GET', url: `/api/v1/content/videos?${parameters}`, headers: auth(accessToken) })
        : await read<VideoPageDto>(`/api/v1/content/videos?${parameters}`, accessToken)
      const seen = new Set<string>()
      return {
        feed: dto.feed === 'FOLLOWING' ? 'FOLLOWING' : 'RECOMMEND',
        items: (dto.items ?? []).filter((item) => {
          if (seen.has(item.videoId)) return false
          seen.add(item.videoId)
          return true
        }).map(toContentVideo),
        nextCursor: dto.nextCursor?.trim() || null,
      }
    },

    async loadVideo(videoId, cityCode, accessToken) {
      return toContentVideo(await read<VideoDto>(
        `/api/v1/content/videos/${encodeURIComponent(videoId)}?${query({ cityCode })}`, accessToken))
    },

    async setLiked(videoId, liked, accessToken) {
      const dto = await request<LikeDto>({
        method: liked ? 'PUT' : 'DELETE',
        url: `/api/v1/content/videos/${encodeURIComponent(videoId)}/like`,
        headers: auth(accessToken),
      })
      return { liked: dto.liked === true, likeCount: count(dto.likeCount) }
    },

    async setFollowing(organizationId, following, accessToken) {
      const dto = await request<FollowDto>({
        method: following ? 'PUT' : 'DELETE',
        url: `/api/v1/content/follows/${encodeURIComponent(organizationId)}`,
        headers: auth(accessToken),
      })
      return dto.following === true
    },

    async loadComments(videoId, page, size, accessToken) {
      const dto = await read<CommentPageDto>(
        `/api/v1/content/videos/${encodeURIComponent(videoId)}/comments?${query({ page, size })}`, accessToken)
      return {
        total: count(dto.total),
        hasMore: dto.hasMore === true,
        items: (dto.items ?? []).flatMap((item) => {
          const comment = toContentComment(item)
          return comment ? [comment] : []
        }),
      }
    },

    async postComment(videoId, idempotencyKey, text, accessToken) {
      const dto = await request<CommentDto>({
        method: 'POST',
        url: `/api/v1/content/videos/${encodeURIComponent(videoId)}/comments`,
        data: { text },
        headers: { ...auth(accessToken), 'Idempotency-Key': idempotencyKey },
      })
      const comment = toContentComment(dto)
      if (!comment) throw new Error('评论响应无效')
      return comment
    },

    async reportComment(commentId, reason, accessToken) {
      const dto = await request<ReportDto>({
        method: 'POST',
        url: `/api/v1/content/comments/${encodeURIComponent(commentId)}/reports`,
        data: { reason },
        headers: auth(accessToken),
      })
      return CONTENT_COMMENT_REPORT_REASONS.includes(dto.reason) ? dto.reason : reason
    },
  }
}
