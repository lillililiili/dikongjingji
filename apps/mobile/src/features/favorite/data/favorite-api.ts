import { resolveBusinessMediaUrl } from '../../../shared/http/business-media'
import { MALL_CATEGORIES_FOR_FAVORITES } from './favorite-categories'
import {
  FAVORITE_TARGET_TYPES,
  type FavoriteCounts,
  type FavoriteGroup,
  type FavoriteItem,
  type FavoritePage,
  type FavoritePrice,
  type FavoriteState,
  type FavoriteTargetType,
} from '../domain/favorite'

type FavoriteRequest = <T>(input: {
  url: string
  method: 'GET' | 'PUT' | 'DELETE'
  headers?: Record<string, string>
}) => Promise<T>

// The DTOs as they arrive; a row the page cannot draw is dropped rather than drawn wrong.
interface PriceDto { mode?: string; amountMinor?: number | null; from?: boolean; unitLabel?: string | null }
interface ItemDto {
  targetType?: string
  targetId?: string
  favoritedAt?: string
  available?: boolean
  title?: string
  meta?: string | null
  tag?: string | null
  price?: PriceDto | null
  coverUrl?: string | null
  productCategory?: string | null
}
interface PageDto {
  total?: number
  hasMore?: boolean
  counts?: Partial<Record<'goods' | 'service' | 'event' | 'content', number>>
  items?: ItemDto[]
}
interface StateDto { targetType?: string; targetId?: string; favorited?: boolean }

export interface FavoriteApi {
  list(accessToken: string, group: FavoriteGroup, cityCode: string, page: number, size: number): Promise<FavoritePage>
  state(accessToken: string, type: FavoriteTargetType, targetId: string): Promise<FavoriteState>
  // Idempotent; within 10 minutes of a removal it undoes the removal.
  add(accessToken: string, type: FavoriteTargetType, targetId: string, cityCode: string): Promise<FavoriteState>
  remove(accessToken: string, type: FavoriteTargetType, targetId: string): Promise<FavoriteState>
}

const isTargetType = (value: unknown): value is FavoriteTargetType =>
  typeof value === 'string' && (FAVORITE_TARGET_TYPES as readonly string[]).includes(value)

function toPrice(dto: PriceDto | null | undefined): FavoritePrice | null {
  if (!dto) return null
  if (dto.mode === 'AMOUNT' && typeof dto.amountMinor === 'number' && dto.amountMinor >= 0) {
    return { mode: 'AMOUNT', amountMinor: dto.amountMinor, from: dto.from === true, unitLabel: dto.unitLabel?.trim() || null }
  }
  if (dto.mode === 'QUOTE' || dto.mode === 'FREE') {
    return { mode: dto.mode, amountMinor: null, from: false, unitLabel: null }
  }
  return null
}

// Only HTTPS pictures with a host (AGENTS.md §11); anything else is drawn as no picture.

export function toFavoriteItem(dto: ItemDto): FavoriteItem | null {
  if (!isTargetType(dto.targetType) || !dto.targetId || !dto.title?.trim()) return null
  const available = dto.available === true
  const category = MALL_CATEGORIES_FOR_FAVORITES.find((code) => code === dto.productCategory) ?? null
  return {
    targetType: dto.targetType,
    targetId: dto.targetId,
    favoritedAt: dto.favoritedAt ?? '',
    available,
    title: dto.title.trim(),
    meta: available ? dto.meta?.trim() || null : null,
    tag: available ? dto.tag?.trim() || null : null,
    price: available ? toPrice(dto.price) : null,
    coverUrl: available ? resolveBusinessMediaUrl(dto.coverUrl) : null,
    productCategory: available && dto.targetType === 'PRODUCT' ? category : null,
  }
}

function toCounts(dto: PageDto['counts']): FavoriteCounts {
  const count = (value: number | undefined) => (typeof value === 'number' && value > 0 ? value : 0)
  return {
    GOODS: count(dto?.goods),
    SERVICE: count(dto?.service),
    EVENT: count(dto?.event),
    CONTENT: count(dto?.content),
  }
}

function toState(dto: StateDto, type: FavoriteTargetType, targetId: string): FavoriteState {
  return { targetType: type, targetId, favorited: dto.favorited === true }
}

const path = (type: FavoriteTargetType, targetId: string) =>
  `/api/v1/favorites/${type}/${encodeURIComponent(targetId)}`

export function createFavoriteApi(request: FavoriteRequest): FavoriteApi {
  const auth = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  return {
    async list(accessToken, group, cityCode, page, size) {
      const dto = await request<PageDto>({
        url: `/api/v1/favorites?group=${group}&cityCode=${encodeURIComponent(cityCode)}&page=${page}&size=${size}`,
        method: 'GET',
        headers: auth(accessToken),
      })
      return {
        total: Math.max(0, dto.total ?? 0),
        hasMore: dto.hasMore === true,
        counts: toCounts(dto.counts),
        items: (dto.items ?? []).map(toFavoriteItem).filter((item): item is FavoriteItem => item !== null),
      }
    },
    async state(accessToken, type, targetId) {
      return toState(await request<StateDto>({ url: path(type, targetId), method: 'GET', headers: auth(accessToken) }), type, targetId)
    },
    async add(accessToken, type, targetId, cityCode) {
      return toState(await request<StateDto>({
        url: `${path(type, targetId)}?cityCode=${encodeURIComponent(cityCode)}`,
        method: 'PUT',
        headers: auth(accessToken),
      }), type, targetId)
    },
    async remove(accessToken, type, targetId) {
      return toState(await request<StateDto>({ url: path(type, targetId), method: 'DELETE', headers: auth(accessToken) }), type, targetId)
    },
  }
}
