export type RootTabCode = 'HOME' | 'SERVICE' | 'DISCOVER' | 'MALL' | 'MINE'

export interface RootTabDefinition {
  code: RootTabCode
  label: string
  path: string
}

export const ROOT_TABS: readonly RootTabDefinition[] = [
  { code: 'HOME', label: '首页', path: '/pages/home/index' },
  { code: 'SERVICE', label: '服务', path: '/pages/service/index' },
  { code: 'DISCOVER', label: '发现', path: '/pages/discover/index' },
  { code: 'MALL', label: '商城', path: '/pages/mall/index' },
  { code: 'MINE', label: '我的', path: '/pages/mine/index' },
]

