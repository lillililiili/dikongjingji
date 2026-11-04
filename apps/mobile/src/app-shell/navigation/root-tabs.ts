export type RootTabCode = 'HOME' | 'DISCOVER' | 'MESSAGE' | 'CART' | 'MINE'

export interface RootTabDefinition {
  code: RootTabCode
  label: string
  path: string
}

export const HOME_ROOT_PATH = '/pages/home/index'

export const ROOT_TABS: readonly RootTabDefinition[] = [
  { code: 'HOME', label: '首页', path: HOME_ROOT_PATH },
  { code: 'DISCOVER', label: '发现', path: '/pages/discover/index' },
  { code: 'MESSAGE', label: '消息', path: '/pages/message/index' },
  { code: 'CART', label: '购物车', path: '/pages/cart/index' },
  { code: 'MINE', label: '我的', path: '/pages/mine/index' },
]

const MAX_BADGE_COUNT = 99

export function formatRootTabBadge(count: number | undefined): string {
  if (!count || count < 1) return ''
  return count > MAX_BADGE_COUNT ? `${MAX_BADGE_COUNT}+` : String(count)
}
