// Mirrors contracts/openapi-content.yaml (1.0.0 step H1, 1.2.0 videos, 1.3.0 picture blocks and a topic's
// merchant items): the discover page's articles
// (cases and knowledge), videos and topics. What an article, a video or a topic points at is read
// again from its own module by type and stable id.

import type { ActivityCard } from '../../activity/domain/activity'

export type ContentArticleKind = 'CASE' | 'KNOWLEDGE' | 'VIDEO'

export type ContentAuthorKind = 'PROVIDER' | 'MERCHANT' | 'PLATFORM'

// The content channels of the server.
export type ContentFeedChannel = 'RECOMMEND' | 'CASE' | 'KNOWLEDGE' | 'VIDEO'

// The tabs of the discover page in the board's order: 推荐 / 视频 / 直播 / 赛事 / 研学 / 案例 / 知识.
// 赛事 and 研学 list real activities of the activity module. 直播 belongs to the live module, which
// is built separately: its place is kept here and it stays hidden until that module turns
// DISCOVER_LIVE_CHANNEL_ENABLED on and gives the tab its list.
export type DiscoverChannel = 'RECOMMEND' | 'VIDEO' | 'LIVE' | 'EVENTS' | 'STUDY' | 'CASE' | 'KNOWLEDGE'

export const DISCOVER_LIVE_CHANNEL_ENABLED = false

const ALL_DISCOVER_CHANNELS: readonly DiscoverChannel[] = ['RECOMMEND', 'VIDEO', 'LIVE', 'EVENTS', 'STUDY', 'CASE', 'KNOWLEDGE']

export function discoverChannels(liveEnabled: boolean = DISCOVER_LIVE_CHANNEL_ENABLED): readonly DiscoverChannel[] {
  return ALL_DISCOVER_CHANNELS.filter((channel) => channel !== 'LIVE' || liveEnabled)
}

export const DISCOVER_CHANNELS: readonly DiscoverChannel[] = discoverChannels()

export type ContentBlockType = 'PARAGRAPH' | 'HEADING' | 'FACTS' | 'STEPS' | 'LIST' | 'TIP' | 'NOTICE' | 'IMAGE'

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

export type ContentVideoOrientation = 'PORTRAIT' | 'LANDSCAPE'

// What a video card shows besides the article fields: the duration badge and the shape.
export interface ContentVideoSummary {
  durationSeconds: number
  orientation: ContentVideoOrientation
}

export interface ContentArticleCard {
  // The video's id on a video card.
  articleId: string
  kind: ContentArticleKind
  title: string
  author: ContentAuthor
  // Only on a video card.
  video: ContentVideoSummary | null
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

// (1.3.0) The picture of an IMAGE block. url is the server's controlled HTTPS address, null while the
// content has no licensed picture: preview builds then draw the board's picture, public builds leave
// the block out.
export interface ContentBlockImage {
  url: string | null
  alt: string
  caption: string | null
}

export interface ContentBlock {
  type: ContentBlockType
  text: string | null
  items: ContentBlockItem[]
  // Only on an IMAGE block.
  image: ContentBlockImage | null
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

// (1.3.0) 商家内容 of a topic: a merchant's product, read again from the mall by id; the merchant is the
// one who published it (广告).
export interface ContentTopicMerchantItem {
  productId: string
  merchant: ContentAuthor
}

export interface ContentTopic extends ContentTopicCard {
  cityCode: string
  activityIds: string[]
  venueIds: string[]
  articles: ContentArticleCard[]
  merchantItems: ContentTopicMerchantItem[]
}

// One entry of a discover channel: an article of the content module, or an activity of the
// activity module (赛事, 研学).
export type DiscoverEntry =
  | { kind: 'ARTICLE'; key: string; article: ContentArticleCard }
  | { kind: 'ACTIVITY'; key: string; activity: ActivityCard }

export const CONTENT_FEED_PAGE_SIZE = 10

// ---- 1.2.0 videos ----

export type ContentVideoFeed = 'RECOMMEND' | 'FOLLOWING'

export type ContentCommentReportReason = 'AD_SPAM' | 'ABUSE' | 'ILLEGAL' | 'OTHER'

export const CONTENT_COMMENT_REPORT_REASONS: readonly ContentCommentReportReason[] = ['AD_SPAM', 'ABUSE', 'ILLEGAL', 'OTHER']

// A video: its detail page and one entry of the vertical stream. playUrl is the server's controlled
// HTTPS address, null when there is none (preview builds then play a bundled sample).
export interface ContentVideo {
  videoId: string
  title: string
  author: ContentAuthor
  sponsored: boolean
  cityCode: string
  coverImage: ContentImage | null
  sample: boolean
  publishedAt: string
  durationSeconds: number
  orientation: ContentVideoOrientation
  playUrl: string | null
  caption: string | null
  tags: string[]
  // 内容中提到; the first one is the video's entry (the card above the caption in the stream).
  refs: ContentRef[]
  likeCount: number
  commentCount: number
  // The signed-in account's own marks; false when nobody is signed in.
  liked: boolean
  followingAuthor: boolean
}

export interface ContentVideoPage {
  feed: ContentVideoFeed
  items: ContentVideo[]
  // null: nothing after these.
  nextCursor: string | null
}

export interface ContentComment {
  commentId: string
  videoId: string
  // The masked display name, or the publishing organization's name (byAuthor).
  authorName: string
  byAuthor: boolean
  mine: boolean
  text: string
  createdAt: string
}

export interface ContentCommentPage {
  total: number
  hasMore: boolean
  items: ContentComment[]
}

export const CONTENT_VIDEO_STREAM_SIZE = 5
export const CONTENT_COMMENT_PAGE_SIZE = 20
export const CONTENT_COMMENT_LIMIT = 200
