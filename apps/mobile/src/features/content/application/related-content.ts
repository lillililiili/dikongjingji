import type { ContentApi } from '../data/content-api'
import type { ContentArticleCard, ContentRefType } from '../domain/content'
import { buildContentArticleUrl } from '../navigation/content-routes'

import { CONTENT_KIND_LABELS, articleCoverTone, type Tone } from './content-view'

// 相关内容 on a product page (R6-3, boards PdFull 相关内容与活动 and PdRental 相关内容与服务): the
// articles whose 内容中提到 names the product. Only articles; the boards' video and activity rows
// are not built here (videos are not in this step, activities are not mentions).

export const RELATED_CONTENT_LIMIT = 3

export interface RelatedContentRow {
  articleId: string
  // "案例", "知识 · 广告 · 示例": the kind in the board's small blue line, then what the
  // discover page marks on the same card.
  kindLine: string
  title: string
  // Missing covers use the content type’s tint.
  image: { url: string; alt: string; cutout: boolean } | null
  tone: Tone
}

export function toRelatedContentRows(
  cards: readonly ContentArticleCard[],
): RelatedContentRow[] {
  return cards.map((card) => {
    const marks = [CONTENT_KIND_LABELS[card.kind]]
    if (card.sponsored) marks.push('广告')
    if (card.sample) marks.push('示例')
    const image = card.coverImage
      ? { url: card.coverImage.url, alt: card.coverImage.alt, cutout: false }
      : null
    return {
      articleId: card.articleId,
      kindLine: marks.join(' · '),
      title: card.title,
      image,
      tone: articleCoverTone(card),
    }
  })
}

export function relatedContentArticleUrl(row: Pick<RelatedContentRow, 'articleId'>, cityCode: string): string {
  return buildContentArticleUrl(row.articleId, cityCode)
}

/**
 * Reads the rows for an object. Any failure leaves the block out: it is an extra, the page works
 * without it.
 */
export async function loadRelatedContentRows(
  api: Pick<ContentApi, 'loadRelated'>,
  refType: ContentRefType,
  refId: string,
  cityCode: string,
): Promise<RelatedContentRow[]> {
  if (!refId || !cityCode) return []
  try {
    return toRelatedContentRows(await api.loadRelated(refType, refId, cityCode, RELATED_CONTENT_LIMIT))
  } catch {
    return []
  }
}
