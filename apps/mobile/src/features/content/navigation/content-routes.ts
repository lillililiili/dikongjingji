import { buildActivityDetailUrl } from '../../activity/navigation/activity-routes'
import { buildVenueDetailUrl } from '../../experience/navigation/experience-routes'
import { buildInspectionDetailUrl } from '../../inspection/navigation/inspection-routes'
import { buildMallProductUrl } from '../../mall/navigation/mall-routes'
import { buildProServiceDetailUrl, buildProviderPageUrl } from '../../proservice/navigation/proservice-routes'

import type { ContentAuthor, ContentRefType } from '../domain/content'

export const CONTENT_ARTICLE_PATH = '/pages/content/article'
export const CONTENT_TOPIC_PATH = '/pages/content/topic'

// Only the stable id and the city travel; the page reads the article or topic again.
export function buildContentArticleUrl(articleId: string, cityCode: string): string {
  return `${CONTENT_ARTICLE_PATH}?articleId=${encodeURIComponent(articleId)}&cityCode=${encodeURIComponent(cityCode)}`
}

export function buildContentTopicUrl(topicId: string, cityCode: string): string {
  return `${CONTENT_TOPIC_PATH}?topicId=${encodeURIComponent(topicId)}&cityCode=${encodeURIComponent(cityCode)}`
}

function read(query: Record<string, unknown> | undefined, key: string): string {
  const value = query?.[key]
  if (typeof value !== 'string') return ''
  // H5 hands the query over decoded; an App deep link may still carry %-escapes.
  try {
    return decodeURIComponent(value).trim()
  } catch {
    return value.trim()
  }
}

export function parseContentArticleTarget(query: Record<string, unknown> | undefined) {
  return { articleId: read(query, 'articleId'), cityCode: read(query, 'cityCode') }
}

export function parseContentTopicTarget(query: Record<string, unknown> | undefined) {
  return { topicId: read(query, 'topicId'), cityCode: read(query, 'cityCode') }
}

/**
 * The real page of something an article mentions (内容中提到), by its type and stable id, in the
 * city the article is read in. These are the routes the modules already have.
 */
export function contentRefTargetUrl(ref: { type: ContentRefType; refId: string }, cityCode: string): string {
  switch (ref.type) {
    case 'INSPECTION_SERVICE': return buildInspectionDetailUrl(ref.refId, cityCode)
    case 'PRO_SERVICE': return buildProServiceDetailUrl(ref.refId, cityCode)
    case 'PRODUCT': return buildMallProductUrl(ref.refId, cityCode)
    case 'ACTIVITY': return buildActivityDetailUrl(ref.refId, cityCode)
    case 'VENUE': return buildVenueDetailUrl(ref.refId, cityCode)
  }
}

/**
 * Where 主页 leads for an author: a provider has its provider page; a merchant has no page yet, so
 * the article says so; the platform has none and shows no 主页.
 */
export type ContentAuthorHome =
  | { kind: 'PAGE'; url: string }
  | { kind: 'NOTICE'; message: string }
  | { kind: 'NONE' }

export const MERCHANT_HOME_NOTICE = '商家主页暂未开通'

export function contentAuthorHome(author: ContentAuthor, cityCode: string): ContentAuthorHome {
  if (author.kind === 'PROVIDER' && author.organizationId) {
    return { kind: 'PAGE', url: buildProviderPageUrl(author.organizationId, cityCode) }
  }
  if (author.kind === 'MERCHANT' && author.organizationId) return { kind: 'NOTICE', message: MERCHANT_HOME_NOTICE }
  return { kind: 'NONE' }
}
