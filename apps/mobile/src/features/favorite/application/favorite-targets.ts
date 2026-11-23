import { resolveActivityPreviewCover } from '../../activity/data/preview/activity-preview-covers'
import { buildActivityDetailUrl } from '../../activity/navigation/activity-routes'
import { resolveArticlePreviewCover, resolveRefPreviewCover } from '../../content/data/preview/content-preview-covers'
import { resolveVideoPreviewCover } from '../../content/data/preview/content-preview-videos'
import { buildContentArticleUrl, buildContentVideoUrl } from '../../content/navigation/content-routes'
import { resolveExperiencePreviewCover } from '../../experience/data/preview/experience-preview-covers'
import { buildVenueDetailUrl } from '../../experience/navigation/experience-routes'
import { buildInspectionDetailUrl } from '../../inspection/navigation/inspection-routes'
import { resolveMallPreviewCover } from '../../mall/data/preview/mall-preview-covers'
import { formatMallAmount } from '../../mall/presentation/shared/mall-format'
import { buildMallProductUrl } from '../../mall/navigation/mall-routes'
import { buildProServiceDetailUrl } from '../../proservice/navigation/proservice-routes'
import type { FavoriteItem, FavoritePrice, FavoriteTargetType } from '../domain/favorite'

import { FAVORITE_PREVIEW_ENABLED } from './favorite-preview'

// Where a favorite opens, and what picture and price it shows on the list (board Favorites).

export const VIDEO_TAG = '视频'

/** The detail page of a target. A content favorite is a video when its tag says so. */
export function favoriteTargetUrl(
  target: { targetType: FavoriteTargetType; targetId: string; tag?: string | null },
  cityCode: string,
): string {
  switch (target.targetType) {
    case 'PRODUCT': return buildMallProductUrl(target.targetId, cityCode)
    case 'INSPECTION_SERVICE': return buildInspectionDetailUrl(target.targetId, cityCode)
    case 'PRO_SERVICE': return buildProServiceDetailUrl(target.targetId, cityCode)
    case 'ACTIVITY': return buildActivityDetailUrl(target.targetId, cityCode)
    case 'VENUE': return buildVenueDetailUrl(target.targetId, cityCode)
    case 'CONTENT': return target.tag === VIDEO_TAG
      ? buildContentVideoUrl(target.targetId, cityCode)
      : buildContentArticleUrl(target.targetId, cityCode)
  }
}

export interface FavoritePicture {
  url: string
  // A product cut-out, drawn whole on a tint.
  contain: boolean
}

/**
 * The target's own picture when the server has one; on preview builds the prototype picture its
 * own module already uses; else none (the row shows the grey 待授权素材 tile of the board).
 */
export function resolveFavoritePicture(
  item: Pick<FavoriteItem, 'targetType' | 'targetId' | 'coverUrl'>,
  previewEnabled: boolean = FAVORITE_PREVIEW_ENABLED,
): FavoritePicture | null {
  if (item.coverUrl) return { url: item.coverUrl, contain: false }
  if (!previewEnabled) return null
  switch (item.targetType) {
    case 'PRODUCT': {
      const cover = resolveMallPreviewCover(item.targetId, previewEnabled)
      return cover ? { url: cover.url, contain: cover.cutout } : null
    }
    case 'ACTIVITY': {
      const url = resolveActivityPreviewCover(item.targetId, previewEnabled)
      return url ? { url, contain: false } : null
    }
    case 'VENUE': {
      const url = resolveExperiencePreviewCover(item.targetId, previewEnabled)
      return url ? { url, contain: false } : null
    }
    case 'CONTENT': {
      const article = resolveArticlePreviewCover(item.targetId, previewEnabled)
      if (article) return { url: article.url, contain: article.cutout }
      const video = resolveVideoPreviewCover(item.targetId, previewEnabled)
      return video ? { url: video.url, contain: false } : null
    }
    case 'INSPECTION_SERVICE':
    case 'PRO_SERVICE': {
      const cover = resolveRefPreviewCover(item.targetType, item.targetId, previewEnabled)
      return cover ? { url: cover.url, contain: cover.contain } : null
    }
  }
}

export interface FavoritePriceText {
  // “¥1,299”, “按项目报价” or “免费”
  amount: string
  // “/天 起”, “/人”, “起” or ""
  unit: string
}

export function formatFavoritePrice(price: FavoritePrice | null): FavoritePriceText | null {
  if (!price) return null
  if (price.mode === 'QUOTE') return { amount: '按项目报价', unit: '' }
  if (price.mode === 'FREE') return { amount: '免费', unit: '' }
  const unit = [price.unitLabel ?? '', price.from ? '起' : ''].filter(Boolean).join(' ')
  return { amount: formatMallAmount(price.amountMinor ?? 0), unit }
}
