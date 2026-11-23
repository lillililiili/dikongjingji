import type { ActivityType } from '../../activity/domain/activity'
import { padTwo, toBeijingParts } from '../../../shared/time/beijing-time'

import type {
  ContentArticleCard,
  ContentArticleKind,
  ContentAuthorKind,
  ContentTopicCard,
  DiscoverChannel,
  DiscoverEntry,
} from '../domain/content'

export interface Tone {
  background: string
  color: string
}

export const DISCOVER_CHANNEL_LABELS: Record<DiscoverChannel, string> = {
  RECOMMEND: '推荐',
  VIDEO: '视频',
  LIVE: '直播',
  EVENTS: '赛事',
  STUDY: '研学',
  CASE: '案例',
  KNOWLEDGE: '知识',
}

// The line under the tabs (board Discover). 推荐 is sorted by publishing time only: there is no
// editors' pick yet.
export const DISCOVER_CHANNEL_NOTES: Record<DiscoverChannel, string> = {
  RECOMMEND: '内容由城市运营、服务商和商家发布，按发布时间排序。',
  VIDEO: '点任意视频进入竖屏播放，上下切换下一条。',
  LIVE: '平台内直播可互动、看讲解清单，直播间不收款。',
  EVENTS: '赛事由主办方发布，报名、退改和成绩以活动详情为准。',
  STUDY: '研学活动面向未成年人，由监护人代报名；行程和退改以活动详情为准。',
  CASE: '案例由服务商提供，实际范围、周期和费用以报价方案为准。',
  KNOWLEDGE: '知识内容仅作入门参考，登记和飞行要求以主管部门的最新规定为准。',
}

export const DISCOVER_EMPTY_TEXT: Record<DiscoverChannel, string> = {
  RECOMMEND: '这个城市还没有发布内容',
  VIDEO: '这个城市还没有视频',
  LIVE: '这个城市暂时没有直播',
  EVENTS: '这个城市近期没有赛事和活动',
  STUDY: '这个城市近期没有研学活动',
  CASE: '这个城市还没有案例',
  KNOWLEDGE: '还没有知识内容',
}

export const CONTENT_KIND_LABELS: Record<ContentArticleKind, string> = {
  CASE: '案例',
  KNOWLEDGE: '知识',
  VIDEO: '视频',
}

// The plain block of an article without a licensed picture: its title on the kind's tint.
export const CONTENT_KIND_TONES: Record<ContentArticleKind, Tone> = {
  CASE: { background: '#e2f6ed', color: '#11804a' },
  KNOWLEDGE: { background: '#e6f0ff', color: '#1d63c4' },
  // A video without a poster: the board's dark cover.
  VIDEO: { background: '#202c42', color: '#ffffff' },
}

const SPONSORED_TONE: Tone = { background: '#fff0e3', color: '#ba470b' }

export function articleCoverTone(article: Pick<ContentArticleCard, 'kind' | 'sponsored'>): Tone {
  return article.sponsored ? SPONSORED_TONE : CONTENT_KIND_TONES[article.kind]
}

// The tag on an activity's picture, coloured as on the board's 赛事 and 研学 tabs.
export const ACTIVITY_TAG_TONES: Record<ActivityType, Tone> = {
  CONTEST: { background: '#eee7ff', color: '#6a4fe0' },
  STUDY_TOUR: { background: '#eee7ff', color: '#6a4fe0' },
  OPEN_DAY: { background: '#e2f6ed', color: '#11804a' },
  TRAINING: { background: '#e6f0ff', color: '#1d63c4' },
}

// The round initial of the author on a card.
export const AUTHOR_AVATAR_COLORS: Record<ContentAuthorKind, string> = {
  PROVIDER: '#11804a',
  MERCHANT: '#ff6a00',
  PLATFORM: '#1d63c4',
}

export function authorInitial(name: string): string {
  return Array.from(name.trim())[0] ?? '·'
}

// "10/28", Beijing time.
export function formatContentDay(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${padTwo(parts.month)}/${padTwo(parts.day)}`
}

// "东营市" → "东营"
export function shortCityName(name: string | null | undefined): string {
  return (name ?? '').trim().replace(/市$/, '')
}

/**
 * "10/28 发布 · 东营" for a city's article, "10/26 发布 · 知识" for one shown in every city. The city
 * name is the App's current city: an article is only shown in its own city.
 */
export function articleMetaLine(
  article: Pick<ContentArticleCard, 'publishedAt' | 'cityCode' | 'kind'>,
  currentCity: { code: string; name: string } | null,
): string {
  const where = article.cityCode && currentCity?.code === article.cityCode
    ? shortCityName(currentCity.name)
    : CONTENT_KIND_LABELS[article.kind]
  return `${formatContentDay(article.publishedAt)} 发布 · ${where}`
}

function dayOf(date: string): string {
  const match = /^\d{4}-(\d{2})-(\d{2})$/.exec(date)
  return match ? `${match[1]}/${match[2]}` : date
}

// "11/01–11/30", with （示例） for sample topics.
export function topicDateRange(topic: Pick<ContentTopicCard, 'startsOn' | 'endsOn' | 'sample'>): string {
  return `${dayOf(topic.startsOn)}–${dayOf(topic.endsOn)}${topic.sample ? '（示例）' : ''}`
}

/** Two columns of the waterfall, filled in turn as on the board. */
export function splitColumns<T>(items: readonly T[]): [T[], T[]] {
  const left: T[] = []
  const right: T[] = []
  items.forEach((item, index) => (index % 2 === 0 ? left : right).push(item))
  return [left, right]
}

// 推荐 and 视频 are two-column waterfalls; 案例 and 知识 rows of articles; 赛事 and 研学 rows of activities.
export function isWaterfallChannel(channel: DiscoverChannel): boolean {
  return channel === 'RECOMMEND' || channel === 'VIDEO'
}

export function articleEntries(entries: readonly DiscoverEntry[]): ContentArticleCard[] {
  return entries.flatMap((entry) => (entry.kind === 'ARTICLE' ? [entry.article] : []))
}
