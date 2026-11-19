import type { PublicApiHttpClient } from '@/shared/http/public-api-request'

import type {
  ContentArticle,
  ContentArticleCard,
  ContentArticleKind,
  ContentAuthor,
  ContentAuthorKind,
  ContentBlock,
  ContentBlockType,
  ContentFeedChannel,
  ContentFeedPage,
  ContentImage,
  ContentRef,
  ContentRefType,
  ContentTopic,
  ContentTopicCard,
} from '../domain/content'

interface MediaDto {
  url: string
  alt: string
}

interface AuthorDto {
  kind: ContentAuthorKind
  organizationId: string | null
  name: string
}

interface ArticleCardDto {
  articleId: string
  kind: ContentArticleKind
  title: string
  author: AuthorDto
  sponsored: boolean
  cityCode: string | null
  coverImage: MediaDto | null
  sample: boolean
  publishedAt: string
}

interface TopicCardDto {
  topicId: string
  title: string
  subtitle: string | null
  publisher: AuthorDto
  startsOn: string
  endsOn: string
  coverImage: MediaDto | null
  sample: boolean
}

interface FeedDto {
  channel: ContentFeedChannel
  cityCode: string
  page: number
  size: number
  total: number
  hasMore: boolean
  topics: TopicCardDto[]
  items: ArticleCardDto[]
}

interface ArticleDto extends ArticleCardDto {
  blocks: Array<{ type: ContentBlockType; text: string | null; items: Array<{ title: string; text: string | null }> }>
  refs: Array<{ type: ContentRefType; refId: string; title: string }>
}

interface TopicDto extends TopicCardDto {
  cityCode: string
  activityIds: string[]
  venueIds: string[]
  articles: ArticleCardDto[]
}

export interface ContentApi {
  loadFeed(channel: ContentFeedChannel, cityCode: string, page: number, size: number): Promise<ContentFeedPage>
  loadArticle(articleId: string, cityCode: string): Promise<ContentArticle>
  loadTopic(topicId: string, cityCode: string): Promise<ContentTopic>
}

const BLOCK_TYPES: readonly ContentBlockType[] = ['PARAGRAPH', 'HEADING', 'FACTS', 'STEPS', 'LIST', 'TIP', 'NOTICE']
const REF_TYPES: readonly ContentRefType[] = ['INSPECTION_SERVICE', 'PRO_SERVICE', 'PRODUCT', 'ACTIVITY', 'VENUE']

// The server only hands out HTTPS pictures; this keeps a bad row away from an <image> anyway.
function toImage(media: MediaDto | null): ContentImage | null {
  return media && /^https:\/\//i.test(media.url) ? { url: media.url, alt: media.alt } : null
}

function toAuthor(dto: AuthorDto): ContentAuthor {
  // A provider or merchant without its organization cannot open a page or a conversation.
  const organizationId = dto.kind === 'PLATFORM' ? null : dto.organizationId?.trim() || null
  return { kind: dto.kind, organizationId, name: dto.name.trim() }
}

export function toContentArticleCard(dto: ArticleCardDto): ContentArticleCard {
  return {
    articleId: dto.articleId,
    kind: dto.kind,
    title: dto.title,
    author: toAuthor(dto.author),
    // Merchants' content is always an advertisement, whatever a row says.
    sponsored: dto.sponsored || dto.author.kind === 'MERCHANT',
    cityCode: dto.cityCode?.trim() || null,
    coverImage: toImage(dto.coverImage),
    sample: dto.sample,
    publishedAt: dto.publishedAt,
  }
}

function toTopicCard(dto: TopicCardDto): ContentTopicCard {
  return {
    topicId: dto.topicId,
    title: dto.title,
    subtitle: dto.subtitle?.trim() || null,
    publisher: toAuthor(dto.publisher),
    startsOn: dto.startsOn,
    endsOn: dto.endsOn,
    coverImage: toImage(dto.coverImage),
    sample: dto.sample,
  }
}

// A block of a type this App does not know yet is left out rather than shown wrongly; so is an
// empty one.
function toBlocks(dto: ArticleDto['blocks']): ContentBlock[] {
  return dto
    .filter((block) => BLOCK_TYPES.includes(block.type))
    .map((block) => ({
      type: block.type,
      text: block.text?.trim() || null,
      items: block.items
        .filter((item) => item.title.trim())
        .map((item) => ({ title: item.title.trim(), text: item.text?.trim() || null })),
    }))
    .filter((block) => block.text !== null || block.items.length > 0)
}

function toRefs(dto: ArticleDto['refs']): ContentRef[] {
  const seen = new Set<string>()
  return dto
    .filter((ref) => REF_TYPES.includes(ref.type))
    .filter((ref) => {
      const key = `${ref.type}:${ref.refId}`
      if (seen.has(key)) return false
      seen.add(key)
      return true
    })
    .map((ref) => ({ type: ref.type, refId: ref.refId, title: ref.title }))
}

function query(parameters: Record<string, string | number>): string {
  return Object.entries(parameters).map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`).join('&')
}

export function createContentApi(http: PublicApiHttpClient): ContentApi {
  return {
    async loadFeed(channel, cityCode, page, size) {
      const dto = await http.request<FeedDto>({
        method: 'GET',
        url: `/api/v1/content/feed?${query({ cityCode, channel, page, size })}`,
      })
      return {
        hasMore: dto.hasMore,
        total: dto.total,
        topics: dto.topics.map(toTopicCard),
        items: dto.items.map(toContentArticleCard),
      }
    },

    async loadArticle(articleId, cityCode) {
      const dto = await http.request<ArticleDto>({
        method: 'GET',
        url: `/api/v1/content/articles/${encodeURIComponent(articleId)}?${query({ cityCode })}`,
      })
      return { ...toContentArticleCard(dto), blocks: toBlocks(dto.blocks), refs: toRefs(dto.refs) }
    },

    async loadTopic(topicId, cityCode) {
      const dto = await http.request<TopicDto>({
        method: 'GET',
        url: `/api/v1/content/topics/${encodeURIComponent(topicId)}?${query({ cityCode })}`,
      })
      return {
        ...toTopicCard(dto),
        cityCode: dto.cityCode,
        activityIds: [...new Set(dto.activityIds)],
        venueIds: [...new Set(dto.venueIds)],
        articles: dto.articles.map(toContentArticleCard),
      }
    },
  }
}
