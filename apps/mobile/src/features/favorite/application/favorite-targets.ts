import { buildActivityDetailUrl } from '../../activity/navigation/activity-routes'
import { buildContentArticleUrl, buildContentVideoUrl } from '../../content/navigation/content-routes'
import { buildVenueDetailUrl } from '../../experience/navigation/experience-routes'
import { buildInspectionDetailUrl } from '../../inspection/navigation/inspection-routes'
import { formatMallAmount } from '../../mall/presentation/shared/mall-format'
import { buildMallProductUrl } from '../../mall/navigation/mall-routes'
import { buildProServiceDetailUrl } from '../../proservice/navigation/proservice-routes'
import type { FavoriteItem, FavoritePrice, FavoriteTargetType } from '../domain/favorite'

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

export function resolveFavoritePicture(item: Pick<FavoriteItem, 'coverUrl'>): FavoritePicture | null {
  return item.coverUrl ? { url: item.coverUrl, contain: false } : null
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
