import type { ApiClient } from '@/core/http/api-client'
import type { OpsChange, PublishStatus } from '@/core/ops/ops-common'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-content.yaml 1.0.0：运营端“发现内容”（图文、视频、专题）。

export type ArticleKind = 'CASE' | 'KNOWLEDGE'
export type AuthorKind = 'PROVIDER' | 'MERCHANT' | 'PLATFORM'
export type BlockType = 'PARAGRAPH' | 'HEADING' | 'FACTS' | 'STEPS' | 'LIST' | 'TIP' | 'NOTICE' | 'IMAGE'
export type RefType = 'INSPECTION_SERVICE' | 'PRO_SERVICE' | 'PRODUCT' | 'ACTIVITY' | 'VENUE'
export type VideoOrientation = 'PORTRAIT' | 'LANDSCAPE'
export type ContentStatus = PublishStatus

export interface OpsContentAuthor {
  kind: AuthorKind
  organizationId: string | null
  name: string
}

export interface OpsBlockItem {
  title: string
  text: string | null
}

export interface OpsBlockImage {
  url: string | null
  alt: string
  caption: string | null
}

export interface OpsBlock {
  type: BlockType
  text: string | null
  items: OpsBlockItem[]
  image: OpsBlockImage | null
}

export interface OpsRef {
  type: RefType
  refId: string
  title: string
}

export interface OpsArticle {
  articleId: string
  kind: ArticleKind
  title: string
  author: OpsContentAuthor
  sponsored: boolean
  cityCode: string | null
  coverUrl: string | null
  sample: boolean
  status: ContentStatus
  publishedAt: string | null
  blocks: OpsBlock[]
  refs: OpsRef[]
  publishMissing: string[]
  version: number
  updatedAt: string
}

export interface OpsVideo {
  videoId: string
  title: string
  author: OpsContentAuthor
  sponsored: boolean
  cityCode: string
  coverUrl: string | null
  sample: boolean
  status: ContentStatus
  publishedAt: string | null
  durationSeconds: number
  orientation: VideoOrientation
  playUrl: string | null
  caption: string | null
  tags: string[]
  rank: number
  refs: OpsRef[]
  publishMissing: string[]
  version: number
  updatedAt: string
}

export interface OpsTopicMerchantItem {
  productId: string
  merchant: OpsContentAuthor
}

export interface OpsTopic {
  topicId: string
  title: string
  subtitle: string | null
  publisher: OpsContentAuthor
  cityCode: string
  startsOn: string
  endsOn: string
  coverUrl: string | null
  sample: boolean
  status: ContentStatus
  activityIds: string[]
  venueIds: string[]
  articleIds: string[]
  merchantItems: OpsTopicMerchantItem[]
  publishMissing: string[]
  version: number
  updatedAt: string
}

export interface OpsContentSummary {
  contentId: string
  kind: ArticleKind | 'VIDEO'
  title: string
  authorName: string
  sponsored: boolean
  cityCode: string | null
  coverUrl: string | null
  sample: boolean
  status: ContentStatus
  publishedAt: string | null
  version: number
  updatedAt: string
}

export interface OpsTopicSummary {
  topicId: string
  title: string
  publisherName: string
  cityCode: string
  startsOn: string
  endsOn: string
  sample: boolean
  status: ContentStatus
  version: number
  updatedAt: string
}

// ---- 请求体 ----

export interface BlockRequest {
  type: BlockType
  text: string | null
  items: OpsBlockItem[] | null
  imageUrl: string | null
  imageAlt: string | null
  imageCaption: string | null
}

export interface ArticleRequest {
  kind: ArticleKind
  title: string
  authorKind: AuthorKind
  authorOrganizationId: string | null
  authorName: string | null
  cityCode: string | null
  coverUrl: string | null
  sample: boolean
  blocks: BlockRequest[]
  refs: OpsRef[]
  version: number | null
}

export interface VideoRequest {
  title: string
  authorKind: AuthorKind
  authorOrganizationId: string | null
  authorName: string | null
  cityCode: string
  coverUrl: string | null
  sample: boolean
  durationSeconds: number
  orientation: VideoOrientation
  playUrl: string | null
  caption: string | null
  tags: string[]
  rank: number
  refs: OpsRef[]
  version: number | null
}

export interface TopicRequest {
  title: string
  subtitle: string | null
  publisherKind: AuthorKind
  publisherOrganizationId: string | null
  publisherName: string | null
  cityCode: string
  startsOn: string
  endsOn: string
  coverUrl: string | null
  sample: boolean
  activityIds: string[]
  venueIds: string[]
  articleIds: string[]
  merchantItems: Array<{ productId: string, merchantOrganizationId: string }>
  version: number | null
}

export interface ContentFilters {
  keyword: string
  kind: ArticleKind | ''
  cityCode: string
  status: ContentStatus | ''
}

export interface TopicFilters {
  keyword: string
  cityCode: string
  status: ContentStatus | ''
}

export function createContentOpsApi(http: ApiClient) {
  const articles = '/api/v1/ops/content/articles'
  const videos = '/api/v1/ops/content/videos'
  const topics = '/api/v1/ops/content/topics'
  const id = (value: string) => encodeURIComponent(value)
  return {
    articles(filters: ContentFilters, page: number, size: number): Promise<Page<OpsContentSummary>> {
      return http.get<Page<OpsContentSummary>>(articles, {
        keyword: filters.keyword.trim(), kind: filters.kind, cityCode: filters.cityCode, status: filters.status, page, size,
      })
    },
    article: (articleId: string) => http.get<OpsArticle>(`${articles}/${id(articleId)}`),
    createArticle: (body: ArticleRequest) => http.post<OpsArticle>(articles, body),
    updateArticle: (articleId: string, body: ArticleRequest) => http.put<OpsArticle>(`${articles}/${id(articleId)}`, body),
    publishArticle: (articleId: string, version: number, publishAt: string | null) =>
      http.post<OpsArticle>(`${articles}/${id(articleId)}/publish`, { version, publishAt }),
    offlineArticle: (articleId: string, version: number) =>
      http.post<OpsArticle>(`${articles}/${id(articleId)}/offline`, { version }),
    articleChanges: (articleId: string) => http.get<OpsChange[]>(`${articles}/${id(articleId)}/changes`),

    videos(filters: Omit<ContentFilters, 'kind'>, page: number, size: number): Promise<Page<OpsContentSummary>> {
      return http.get<Page<OpsContentSummary>>(videos, {
        keyword: filters.keyword.trim(), cityCode: filters.cityCode, status: filters.status, page, size,
      })
    },
    video: (videoId: string) => http.get<OpsVideo>(`${videos}/${id(videoId)}`),
    createVideo: (body: VideoRequest) => http.post<OpsVideo>(videos, body),
    updateVideo: (videoId: string, body: VideoRequest) => http.put<OpsVideo>(`${videos}/${id(videoId)}`, body),
    publishVideo: (videoId: string, version: number, publishAt: string | null) =>
      http.post<OpsVideo>(`${videos}/${id(videoId)}/publish`, { version, publishAt }),
    offlineVideo: (videoId: string, version: number) =>
      http.post<OpsVideo>(`${videos}/${id(videoId)}/offline`, { version }),
    videoChanges: (videoId: string) => http.get<OpsChange[]>(`${videos}/${id(videoId)}/changes`),

    topics(filters: TopicFilters, page: number, size: number): Promise<Page<OpsTopicSummary>> {
      return http.get<Page<OpsTopicSummary>>(topics, {
        keyword: filters.keyword.trim(), cityCode: filters.cityCode, status: filters.status, page, size,
      })
    },
    topic: (topicId: string) => http.get<OpsTopic>(`${topics}/${id(topicId)}`),
    createTopic: (body: TopicRequest) => http.post<OpsTopic>(topics, body),
    updateTopic: (topicId: string, body: TopicRequest) => http.put<OpsTopic>(`${topics}/${id(topicId)}`, body),
    publishTopic: (topicId: string, version: number) =>
      http.post<OpsTopic>(`${topics}/${id(topicId)}/publish`, { version }),
    offlineTopic: (topicId: string, version: number) =>
      http.post<OpsTopic>(`${topics}/${id(topicId)}/offline`, { version }),
    topicChanges: (topicId: string) => http.get<OpsChange[]>(`${topics}/${id(topicId)}/changes`),
  }
}

export type ContentOpsApi = ReturnType<typeof createContentOpsApi>
