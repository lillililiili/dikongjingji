// Favorites (plan 08 S5-6, contract contracts/openapi-favorite.yaml 1.0.0) and the comparison of
// mall products, which lives only on the device (board MallCompare).

import type { MallCategory } from '../../mall/domain/mall'

export const FAVORITE_TARGET_TYPES = [
  'PRODUCT',
  'INSPECTION_SERVICE',
  'PRO_SERVICE',
  'ACTIVITY',
  'VENUE',
  'CONTENT',
] as const
export type FavoriteTargetType = (typeof FAVORITE_TARGET_TYPES)[number]

// The four tabs of board Favorites: 商品 / 服务 / 活动 / 内容.
export const FAVORITE_GROUPS = ['GOODS', 'SERVICE', 'EVENT', 'CONTENT'] as const
export type FavoriteGroup = (typeof FAVORITE_GROUPS)[number]

export const FAVORITE_GROUP_LABELS: Record<FavoriteGroup, string> = {
  GOODS: '商品',
  SERVICE: '服务',
  EVENT: '活动',
  CONTENT: '内容',
}

export type FavoritePriceMode = 'AMOUNT' | 'QUOTE' | 'FREE'

export interface FavoritePrice {
  mode: FavoritePriceMode
  amountMinor: number | null
  from: boolean
  unitLabel: string | null
}

export interface FavoriteItem {
  targetType: FavoriteTargetType
  targetId: string
  favoritedAt: string
  // False when the target can no longer be read in this city: only the remembered title is left.
  available: boolean
  title: string
  meta: string | null
  tag: string | null
  price: FavoritePrice | null
  coverUrl: string | null
  productCategory: MallCategory | null
}

export type FavoriteCounts = Record<FavoriteGroup, number>

export interface FavoritePage {
  total: number
  hasMore: boolean
  counts: FavoriteCounts
  items: FavoriteItem[]
}

export interface FavoriteState {
  targetType: FavoriteTargetType
  targetId: string
  favorited: boolean
}

export const FAVORITE_PAGE_SIZE = 10
