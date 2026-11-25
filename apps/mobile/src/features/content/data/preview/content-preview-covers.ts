import { resolveActivityPreviewCover } from '../../../activity/data/preview/activity-preview-covers'
import { resolveExperiencePreviewCover } from '../../../experience/data/preview/experience-preview-covers'
import { resolveMallPreviewCover } from '../../../mall/data/preview/mall-preview-covers'
import type { ContentImage, ContentRefType } from '../../domain/content'

const PREVIEW_SAMPLES = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__

// Pictures for the sample articles and the topic of the development seed (R__dev_content.sql),
// keyed by id. They come from the 2026-11-14 prototype boards (Discover, ContentArticle,
// ContentCase2–3, ContentKnow1–3, Topic) and only stand in until content carries licensed pictures
// of its own; a picture the server returns always wins. Public builds do not show these pages.
const DIRECTORY = '/static/images/preview'

export interface ContentPreviewCover extends ContentImage {
  /** A product shot on a plain backdrop: shown whole on a tint instead of cropped. */
  cutout: boolean
}

const ARTICLES: Record<string, { file: string; alt: string; cutout?: boolean }> = {
  'f3000000-0000-4000-8000-000000000001': { file: 'content-park-patrol.webp', alt: '园区外景' },
  'f3000000-0000-4000-8000-000000000002': { file: 'content-survey.jpg', alt: '无人机测绘作业' },
  'f3000000-0000-4000-8000-000000000003': { file: 'content-solar.webp', alt: '光伏电站现场' },
  'f3000000-0000-4000-8000-000000000004': { file: 'content-drone-buy.jpg', alt: '便携航拍无人机' },
  'f3000000-0000-4000-8000-000000000005': { file: 'content-robot-kit.jpg', alt: '编程机器人探索套装', cutout: true },
  'f3000000-0000-4000-8000-000000000006': { file: 'content-flight-check.jpg', alt: '户外飞行' },
}

const TOPICS: Record<string, { file: string; alt: string }> = {
  'f3100000-0000-4000-8000-000000000001': { file: 'content-topic-workshop.jpg', alt: '孩子们和老师一起体验编程机器人' },
}

// (1.3.0) The picture blocks of the sample articles, keyed by article id, in the order of the article's
// IMAGE blocks: the board ContentArticle's drone picture after 怎么巡.
const ARTICLE_FIGURES: Record<string, readonly string[]> = {
  'f3000000-0000-4000-8000-000000000001': ['content-patrol-drone.jpg'],
}

// (1.3.0) 商家内容 of the sample topic (board Topic): its own product shot, otherwise the mall's.
const TOPIC_MERCHANT_PRODUCTS: Record<string, string> = {
  'c2000000-0000-4000-8000-000000000101': 'content-topic-robot-kit.jpg',
}

// 内容中提到 of the services, which have no preview pictures of their own here. The park
// security one is the board's illustration, drawn whole on a tint (`icon`).
const SERVICE_REFS: Record<string, { file: string; icon?: boolean }> = {
  '77777777-7777-7777-7777-777777777777': { file: 'content-ref-park-security.webp', icon: true },
  '66666666-6666-6666-6666-666666666666': { file: 'content-solar.webp' },
  'f2100000-0000-4000-8000-000000000005': { file: 'content-ref-survey-model.jpg' },
  'f2100000-0000-4000-8000-000000000007': { file: 'content-solar.webp' },
  'f2100000-0000-4000-8000-000000000014': { file: 'content-ref-flight-guide.jpg' },
}

export function resolveArticlePreviewCover(
  articleId: string,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): ContentPreviewCover | null {
  const entry = previewEnabled ? ARTICLES[articleId] : undefined
  return entry ? { url: `${DIRECTORY}/${entry.file}`, alt: entry.alt, cutout: Boolean(entry.cutout) } : null
}

export function resolveTopicPreviewCover(
  topicId: string,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): ContentPreviewCover | null {
  const entry = previewEnabled ? TOPICS[topicId] : undefined
  return entry ? { url: `${DIRECTORY}/${entry.file}`, alt: entry.alt, cutout: false } : null
}

/**
 * (1.3.0) The picture of an article's IMAGE block without a licensed picture: `ordinal` counts the
 * article's IMAGE blocks from 0. Public builds get null and leave the block out.
 */
export function resolveArticleFigurePreview(
  articleId: string,
  ordinal: number,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): string | null {
  const file = previewEnabled ? ARTICLE_FIGURES[articleId]?.[ordinal] : undefined
  return file ? `${DIRECTORY}/${file}` : null
}

/** (1.3.0) A topic's merchant product without a picture of its own from the mall. */
export function resolveTopicMerchantPreview(
  productId: string,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): string | null {
  if (!previewEnabled) return null
  const file = TOPIC_MERCHANT_PRODUCTS[productId]
  return file ? `${DIRECTORY}/${file}` : resolveMallPreviewCover(productId, previewEnabled)?.url ?? null
}

export interface ContentRefPreviewCover {
  url: string
  // Drawn whole on a tint: a product cut-out or an illustration.
  contain: boolean
  // An illustration, drawn smaller on the orange tint (board ContentArticle).
  padded?: boolean
}

/**
 * A mention's picture: the services from this table, goods, activities and venues from the preview
 * pictures their own modules already use.
 */
export function resolveRefPreviewCover(
  type: ContentRefType,
  refId: string,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): ContentRefPreviewCover | null {
  if (!previewEnabled) return null
  if (type === 'INSPECTION_SERVICE' || type === 'PRO_SERVICE') {
    const entry = SERVICE_REFS[refId]
    return entry ? { url: `${DIRECTORY}/${entry.file}`, contain: Boolean(entry.icon), padded: Boolean(entry.icon) } : null
  }
  if (type === 'PRODUCT') {
    const cover = resolveMallPreviewCover(refId, previewEnabled)
    return cover ? { url: cover.url, contain: cover.cutout } : null
  }
  const url = type === 'ACTIVITY'
    ? resolveActivityPreviewCover(refId, previewEnabled)
    : resolveExperiencePreviewCover(refId, previewEnabled)
  return url ? { url, contain: false } : null
}

/** An activity of the topic page (本季活动). */
export const resolveTopicActivityPreviewCover = (activityId: string): string | null =>
  resolveActivityPreviewCover(activityId, PREVIEW_SAMPLES)
