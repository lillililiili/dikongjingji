import { describe, expect, it } from 'vitest'
import source from '../../../shared/city/presentation/CitySelectionSheet.vue?raw'

describe('city selection sheet component contract', () => {
  it('matches the approved mobile sheet hierarchy', () => {
    expect(source).toContain('选择服务城市')
    expect(source).toContain('搜索城市')
    expect(source).toContain('当前定位')
    expect(source).toContain('最近使用')
    expect(source).toContain('热门城市')
    expect(source).toContain('更多城市')
    expect(source).toContain('选择后自动筛选该城市服务')
    expect(source).toContain('env(safe-area-inset-bottom)')
    expect(source).toContain(
      'grid-template-columns: repeat(3, minmax(0, 1fr))',
    )
    expect(source).toContain('@click.self="$emit(\'close\')"')
    expect(source).not.toContain('showActionSheet')
  })

  it('uses typed inputs and emits every interactive outcome', () => {
    expect(source).toContain('directory: CityDirectory | null')
    expect(source).toContain('currentCity: CurrentCity | null')
    expect(source).toContain('recentCities: PlatformCity[]')
    expect(source).toContain('loading: boolean')
    expect(source).toContain('error: string')
    expect(source).toContain('relocating: boolean')
    expect(source).toContain('close: []')
    expect(source).toContain('retry: []')
    expect(source).toContain('relocate: []')
    expect(source).toContain('select: [payload:')
    expect(source).toContain("source: 'CURRENT'")
    expect(source).toContain("source: 'RECENT'")
    expect(source).toContain("source: 'HOT'")
    expect(source).toContain("source: 'SEARCH'")
    expect(source).toContain("source: 'TREE'")
  })

  it('keeps search, selection, accordion, and icon behavior explicit', () => {
    expect(source).toContain(
      "import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'",
    )
    expect(source).toContain(
      'searchCities(props.directory, keyword.value)',
    )
    expect(source).toContain('v-model="keyword"')
    expect(source).toContain(
      'expandedProvinceCode === province.code ? null : province.code',
    )
    expect(source).toContain(
      "'city-sheet__city--selected': currentCity?.code === city.code",
    )
    expect(source).toContain('min-height: 44px')
    expect(source).not.toMatch(/[\u{1F300}-\u{1FAFF}]/u)
    expect(source).not.toContain('<svg')
  })
})
