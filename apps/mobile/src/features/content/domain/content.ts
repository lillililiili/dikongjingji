// Mirrors contracts/openapi-content.yaml 1.0.0 (step H1): the discover page's articles (cases and
// knowledge) and topics. Public and read-only; what an article or a topic points at is read again
// from its own module by type and stable id.

import type { ActivityCard } from '../../activity/domain/activity'

export type ContentArticleKind = 'CASE' | 'KNOWLEDGE'

export type ContentAuthorKind = 'PROVIDER' | 'MERCHANT' | 'PLATFORM'

// The article channels of the server.
export type ContentFeedChannel = 'RECOMMEND' | 'CASE' | 'KNOWLEDGE'

// The tabs of the discover page: 推荐 / 赛事 / 研学 / 案例 / 知识. 赛事 and 研学 list real activities
// of the activity module; 视频 and 直播 are not in this step.
export type DiscoverChannel = 'RECOMMEND' | 'EVENTS' | 'STUDY' | 'CASE' | 'KNOWLEDGE'

export const DISCOVER_CHANNELS: readonly DiscoverChannel[] = ['RECOMMEND', 'EVENTS', 'STUDY', 'CASE', 'KNOWLEDGE']

export type ContentBlockType = 'PARAGRAPH' | 'HEADING' | 'FACTS' | 'STEPS' | 'LIST' | 'TIP' | 'NOTICE'

export type ContentRefType = 'INSPECTION_SERVICE' | 'PRO_SERVICE' | 'PRODUCT' | 'ACTIVITY' | 'VENUE'

export interface ContentImage {
  url: string
  alt: string
}

export interface ContentAuthor {
  kind: ContentAuthorKind
  // The provider's or merchant's organization; null for the platform.
  organizationId: string | null
  name: string
}

export interface ContentArticleCard {
  articleId: string
  kind: ContentArticleKind
  title: string
  author: ContentAuthor
  // 广告: everything a merchant publishes.
  sponsored: boolean
  // null: shown in every city (knowledge only).
  cityCode: string | null
  coverImage: ContentImage | null
  sample: boolean
  publishedAt: string
}

export interface ContentTopicCard {
  topicId: string
  title: string
  subtitle: string | null
  publisher: ContentAuthor
  // YYYY-MM-DD, Beijing time, both days included.
  startsOn: string
  endsOn: string
  coverImage: ContentImage | null
  sample: boolean
}

export interface ContentFeedPage {
  hasMore: boolean
  total: number
  // Only on the first page of 推荐: the city's topics running today.
  topics: ContentTopicCard[]
  items: ContentArticleCard[]
}

export interface ContentBlockItem {
  title: string
  text: string | null
}

export interface ContentBlock {
  type: ContentBlockType
  text: string | null
  items: ContentBlockItem[]
}

export interface ContentRef {
  type: ContentRefType
  refId: string
  // The name typed in when the article was written; the page shows the real one it reads.
  title: string
}

export interface ContentArticle extends ContentArticleCard {
  blocks: ContentBlock[]
  refs: ContentRef[]
}

export interface ContentTopic extends ContentTopicCard {
  cityCode: string
  activityIds: string[]
  venueIds: string[]
  articles: ContentArticleCard[]
}

// One entry of a discover channel: an article of the content module, or an activity of the
// activity module (赛事, 研学).
export type DiscoverEntry =
  | { kind: 'ARTICLE'; key: string; article: ContentArticleCard }
  | { kind: 'ACTIVITY'; key: string; activity: ActivityCard }

export const CONTENT_FEED_PAGE_SIZE = 10
