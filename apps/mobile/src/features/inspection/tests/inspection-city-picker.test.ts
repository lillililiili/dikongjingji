import { compileScript, parse } from '@vue/compiler-sfc'
import {
  createRenderer,
  defineComponent,
  h,
  nextTick,
  reactive,
  type App,
  type Component,
} from '@vue/runtime-core'
import * as VueRuntime from '@vue/runtime-core'
import {
  ModuleKind,
  ScriptTarget,
  transpileModule,
} from 'typescript'
import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  CITY_SERVICE_STATUS_LABELS,
  cityServiceStatus,
  findCityByLocatedName,
  searchCities,
  type CityDirectory,
  type PlatformCity,
  type PlatformProvince,
} from '../../../shared/city/domain/city-directory'
import catalogPageSource from '../presentation/catalog/pages/InspectionCatalogPage.vue?raw'
import source from '../../../shared/city/presentation/CitySelectionSheet.vue?raw'
import type { CurrentCity } from '../../../shared/city/state/current-city'

interface HostNode {
  type: string
  props: Record<string, unknown>
  children: HostNode[]
  parent: HostNode | null
  text: string
}

interface SheetProps {
  directory: CityDirectory | null
  currentCity: CurrentCity | null
  recentCities: PlatformCity[]
  loading: boolean
  error: string
  relocating: boolean
}

type TestCitySelectionSource =
  | 'CURRENT'
  | 'OPEN'
  | 'PREPARING'
  | 'RECENT'
  | 'HOT'
  | 'SEARCH'
  | 'TREE'

interface CatalogCityControllerOptions {
  setCurrentCity(city: CurrentCity): void
  saveRecentCity(city: PlatformCity): void
  loadRecentCities(): PlatformCity[]
  setRecentCities(cities: PlatformCity[]): void
  closeCitySheet(): void
  setPage(page: number): void
  reloadCatalog(): Promise<void>
  recordSearchSelection(cityCode: string): Promise<void>
  getDirectory(): CityDirectory | null
  isDirectoryLoading(): boolean
  loadDirectory(): Promise<void>
  isRelocating(): boolean
  setRelocating(value: boolean): void
  locateCityName(): Promise<string>
  showToast(title: string): void
}

interface CatalogCityController {
  applyCitySelection(payload: {
    city: PlatformCity
    source: TestCitySelectionSource
  }): Promise<void>
  relocateCity(): Promise<void>
  closeCitySheet(): void
}

type CatalogCityControllerFactory = (
  options: CatalogCityControllerOptions,
) => CatalogCityController

async function loadCatalogCityControllerFactory():
Promise<CatalogCityControllerFactory> {
  const parsed = parse(catalogPageSource, {
    filename: 'InspectionCatalogPage.vue',
  })
  expect(parsed.descriptor.script).not.toBeNull()
  if (!parsed.descriptor.script) {
    throw new Error('Catalog page runtime script is unavailable')
  }
  const output = transpileModule(parsed.descriptor.script.content, {
    compilerOptions: {
      esModuleInterop: true,
      module: ModuleKind.CommonJS,
      target: ScriptTarget.ES2020,
    },
  }).outputText
  const testModule: {
    exports: {
      createCatalogCityController?: CatalogCityControllerFactory
    }
  } = { exports: {} }
  const testRequire = (request: string): unknown => {
    if (request === '@/shared/city/domain/city-directory') {
      return { findCityByLocatedName }
    }
    throw new Error(`Unexpected catalog runtime dependency: ${request}`)
  }
  const evaluate = new Function('require', 'module', 'exports', output)
  evaluate(testRequire, testModule, testModule.exports)
  const factory = testModule.exports.createCatalogCityController
  expect(factory).toBeTypeOf('function')
  if (!factory) throw new Error('Catalog city controller is unavailable')
  return factory
}

const UniIconsMock = defineComponent({
  name: 'UniIconsMock',
  props: {
    type: String,
    size: [String, Number],
    color: String,
  },
  setup(props) {
    return () => h('uni-icons-mock', { ...props })
  },
})

const ScrollViewMock = defineComponent({
  name: 'ScrollViewMock',
  setup(_props, { slots }) {
    return () => h('scroll-view', slots.default?.())
  },
})

const TestVueRuntime = {
  ...VueRuntime,
  vModelText: {},
  withModifiers(
    handler: (...args: unknown[]) => unknown,
    modifiers: string[],
  ) {
    return (event: {
      currentTarget?: unknown
      target?: unknown
    }) => {
      if (
        modifiers.includes('self')
        && event.target !== event.currentTarget
      ) {
        return undefined
      }
      return handler(event)
    }
  },
}

function compileCitySelectionSheet(): Component {
  const parsed = parse(source, {
    filename: 'CitySelectionSheet.vue',
  })
  if (parsed.errors.length > 0) {
    throw new Error(`SFC parse failed: ${String(parsed.errors[0])}`)
  }

  const compiled = compileScript(parsed.descriptor, {
    id: 'city-selection-sheet-test',
    inlineTemplate: true,
    templateOptions: {
      compilerOptions: {
        isCustomElement: (tag) =>
          tag === tag.toLocaleLowerCase(),
      },
    },
  })
  const output = transpileModule(compiled.content, {
    compilerOptions: {
      esModuleInterop: true,
      module: ModuleKind.CommonJS,
      target: ScriptTarget.ES2020,
    },
  }).outputText
  const testModule: {
    exports: { default?: Component }
  } = {
    exports: {},
  }
  const testRequire = (request: string): unknown => {
    if (request === 'vue') return TestVueRuntime
    if (
      request
      === '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
    ) {
      return UniIconsMock
    }
    if (request === '@/shared/ui/LineIcon.vue') {
      return UniIconsMock
    }
    if (request === '../domain/city-directory') {
      return { searchCities, cityServiceStatus, CITY_SERVICE_STATUS_LABELS }
    }
    throw new Error(`Unexpected SFC dependency: ${request}`)
  }
  const evaluate = new Function(
    'require',
    'module',
    'exports',
    output,
  )
  evaluate(testRequire, testModule, testModule.exports)

  if (!testModule.exports.default) {
    throw new Error('Compiled SFC did not export a component')
  }
  return testModule.exports.default
}

const CitySelectionSheet = compileCitySelectionSheet()

const renderer = createRenderer<HostNode, HostNode>({
  patchProp(element, key, _previousValue, nextValue) {
    if (nextValue == null) {
      delete element.props[key]
      return
    }
    element.props[key] = nextValue
  },
  insert(child, parent, anchor) {
    if (child.parent) {
      const previousIndex = child.parent.children.indexOf(child)
      if (previousIndex >= 0) child.parent.children.splice(previousIndex, 1)
    }

    child.parent = parent
    const anchorIndex = anchor ? parent.children.indexOf(anchor) : -1
    if (anchorIndex >= 0) {
      parent.children.splice(anchorIndex, 0, child)
      return
    }
    parent.children.push(child)
  },
  remove(child) {
    if (!child.parent) return
    const index = child.parent.children.indexOf(child)
    if (index >= 0) child.parent.children.splice(index, 1)
    child.parent = null
  },
  createElement(type) {
    return createHostNode(type)
  },
  createText(text) {
    return createHostNode('#text', text)
  },
  createComment() {
    return createHostNode('#comment')
  },
  setText(node, text) {
    node.text = text
  },
  setElementText(element, text) {
    element.children = []
    element.text = text
  },
  parentNode(node) {
    return node.parent
  },
  nextSibling(node) {
    if (!node.parent) return null
    const index = node.parent.children.indexOf(node)
    return node.parent.children[index + 1] ?? null
  },
  querySelector() {
    return null
  },
  setScopeId(element, id) {
    element.props[id] = ''
  },
  insertStaticContent(content, parent, anchor) {
    const node = createHostNode('#static', content)
    const anchorIndex = anchor ? parent.children.indexOf(anchor) : -1
    node.parent = parent
    if (anchorIndex >= 0) parent.children.splice(anchorIndex, 0, node)
    else parent.children.push(node)
    return [node, node]
  },
})

const mountedApps: App[] = []

afterEach(() => {
  mountedApps.splice(0).forEach((app) => app.unmount())
})

function createHostNode(type: string, text = ''): HostNode {
  return {
    type,
    props: {},
    children: [],
    parent: null,
    text,
  }
}

function makeCity(
  code: string,
  name: string,
  pinyin: string,
  provinceCode: string,
  provinceName: string,
  serviceStatus: PlatformCity['serviceStatus'] = 'CLOSED',
): PlatformCity {
  return {
    code,
    name,
    pinyin,
    initials: pinyin[0]?.toUpperCase() ?? '',
    provinceCode,
    provinceName,
    serviceStatus,
  }
}

function makeProvince(
  code: string,
  name: string,
  pinyin: string,
  cities: PlatformCity[],
): PlatformProvince {
  return {
    code,
    name,
    pinyin,
    initials: pinyin[0]?.toUpperCase() ?? '',
    cities,
  }
}

// 照原型 CitySheet：已开通东营；筹备中济南、青岛；其余未开通。
const jinan = makeCity('370100', '济南市', 'jinan', '370000', '山东省', 'PREPARING')
const qingdao = makeCity('370200', '青岛市', 'qingdao', '370000', '山东省', 'PREPARING')
const dongying = makeCity('370500', '东营市', 'dongying', '370000', '山东省', 'OPEN')
const yantai = makeCity('370600', '烟台市', 'yantai', '370000', '山东省')
const nanjing = makeCity('320100', '南京市', 'nanjing', '320000', '江苏省')

const directory: CityDirectory = {
  hotCities: [jinan, dongying],
  provinces: [
    makeProvince(
      '370000',
      '山东省',
      'shandong',
      [jinan, qingdao, dongying, yantai],
    ),
    makeProvince('320000', '江苏省', 'jiangsu', [nanjing]),
  ],
  openCities: [dongying],
  preparingCities: [jinan, qingdao],
}

function mountSheet(overrides: Partial<SheetProps> = {}) {
  const props = reactive<SheetProps>({
    directory,
    currentCity: {
      code: jinan.code,
      name: jinan.name,
      provinceCode: jinan.provinceCode,
    },
    recentCities: [qingdao],
    loading: false,
    error: '',
    relocating: false,
    ...overrides,
  })
  const events = {
    close: vi.fn(),
    retry: vi.fn(),
    relocate: vi.fn(),
    select: vi.fn(),
  }
  const root = createHostNode('root')
  const app = renderer.createApp(defineComponent({
    setup() {
      return () => h(CitySelectionSheet, {
        ...props,
        onClose: events.close,
        onRetry: events.retry,
        onRelocate: events.relocate,
        onSelect: events.select,
      })
    },
  }))

  app.component('scroll-view', ScrollViewMock)
  app.mount(root)
  mountedApps.push(app)

  return { events, props, root }
}

function descendants(node: HostNode): HostNode[] {
  return node.children.flatMap((child) => [child, ...descendants(child)])
}

function textOf(node: HostNode): string {
  return `${node.text}${node.children.map(textOf).join('')}`
}

function hasClass(node: HostNode, className: string): boolean {
  return String(node.props.class ?? '')
    .split(/\s+/)
    .includes(className)
}

function findNode(
  root: HostNode,
  predicate: (node: HostNode) => boolean,
): HostNode {
  const result = [root, ...descendants(root)].find(predicate)
  if (!result) throw new Error('Expected rendered node was not found')
  return result
}

function findByClass(root: HostNode, className: string): HostNode {
  return findNode(root, (node) => hasClass(node, className))
}

function findButtonByText(root: HostNode, text: string): HostNode {
  return findNode(
    root,
    (node) => node.type === 'button' && textOf(node) === text,
  )
}

function buttonsByText(root: HostNode, text: string): HostNode[] {
  return [root, ...descendants(root)].filter(
    (node) => node.type === 'button' && textOf(node) === text,
  )
}

async function trigger(
  node: HostNode,
  propName: string,
  payload?: unknown,
): Promise<void> {
  const handler = node.props[propName]
  if (Array.isArray(handler)) {
    handler.forEach((entry) => {
      if (typeof entry === 'function') entry(payload)
    })
  } else if (typeof handler === 'function') {
    handler(payload)
  } else {
    throw new Error(`Expected ${propName} handler was not found`)
  }
  await nextTick()
}

async function typeKeyword(root: HostNode, keyword: string): Promise<void> {
  const input = findNode(root, (node) => node.type === 'input')
  const modelUpdate = input.props['onUpdate:modelValue']
  if (typeof modelUpdate === 'function') {
    modelUpdate(keyword)
    await nextTick()
    return
  }

  await trigger(input, 'onInput', {
    detail: { value: keyword },
    target: { value: keyword },
  })
}

describe('city selection sheet runtime behavior', () => {
  it('searches the real directory and emits a SEARCH selection', async () => {
    const { events, root } = mountSheet()

    await typeKeyword(root, '烟台')

    expect(textOf(root)).toContain('搜索结果')
    // 未开通的城市只在搜索里出现，名字后面标“未开通”。
    expect(buttonsByText(root, `${yantai.name}未开通`)).toHaveLength(1)
    expect(buttonsByText(root, dongying.name)).toHaveLength(0)

    await trigger(findButtonByText(root, `${yantai.name}未开通`), 'onClick')
    expect(events.select).toHaveBeenCalledWith({
      city: yantai,
      source: 'SEARCH',
    })
  })

  it('emits typed payloads for the current city and the open and preparing groups', async () => {
    const currentMount = mountSheet()
    expect(textOf(findByClass(currentMount.root, 'city-sheet__current-city'))).toContain('济南市 · 筹备中')
    await trigger(
      findByClass(currentMount.root, 'city-sheet__current-city'),
      'onClick',
    )
    expect(currentMount.events.select).toHaveBeenCalledWith({
      city: jinan,
      source: 'CURRENT',
    })

    const openMount = mountSheet()
    const open = findByClass(openMount.root, 'city-sheet__open')
    await trigger(findButtonByText(open, dongying.name), 'onClick')
    expect(openMount.events.select).toHaveBeenCalledWith({
      city: dongying,
      source: 'OPEN',
    })

    const preparingMount = mountSheet()
    const preparing = findByClass(preparingMount.root, 'city-sheet__preparing')
    // 当前城市打勾；筹备中的城市名后面有小字“筹备”。
    expect(buttonsByText(preparing, `${jinan.name} ✓筹备`)).toHaveLength(1)
    await trigger(findButtonByText(preparing, `${qingdao.name}筹备`), 'onClick')
    expect(preparingMount.events.select).toHaveBeenCalledWith({
      city: qingdao,
      source: 'PREPARING',
    })
  })

  it('lists only open and preparing cities, in the board’s two groups', () => {
    const { root } = mountSheet({ recentCities: [yantai] })

    expect(textOf(root)).toContain('已开通城市')
    expect(textOf(root)).toContain('筹备中（可先浏览全国商品与线上体验）')
    // 未开通的、最近使用的、按省展开的都不再列（画板没有这些组）。
    expect(textOf(root)).not.toContain('热门城市')
    expect(textOf(root)).not.toContain('最近使用')
    expect(textOf(root)).not.toContain('江苏省')
    expect(buttonsByText(root, yantai.name)).toHaveLength(0)
    expect(buttonsByText(root, nanjing.name)).toHaveLength(0)
    expect(textOf(root)).toContain('账号、订单、参与人和收藏不变，跨城通用')
  })

  it('emits close, retry, and relocate from rendered controls', async () => {
    const closeMount = mountSheet()
    const mask = findByClass(closeMount.root, 'city-sheet__mask')
    await trigger(mask, 'onClick', {
      currentTarget: mask,
      target: mask,
    })
    await trigger(
      findByClass(closeMount.root, 'city-sheet__close'),
      'onClick',
    )
    expect(closeMount.events.close).toHaveBeenCalledTimes(2)

    const retryMount = mountSheet({
      directory: null,
      currentCity: null,
      error: '城市目录加载失败',
    })
    await trigger(
      findButtonByText(retryMount.root, '重新加载'),
      'onClick',
    )
    expect(retryMount.events.retry).toHaveBeenCalledTimes(1)

    const relocateMount = mountSheet()
    await trigger(
      findByClass(relocateMount.root, 'city-sheet__relocate'),
      'onClick',
    )
    expect(relocateMount.events.relocate).toHaveBeenCalledTimes(1)
  })

  it('renders loading, error, and wholly empty states', () => {
    const loadingMount = mountSheet({
      directory: null,
      currentCity: null,
      loading: true,
    })
    expect(textOf(loadingMount.root)).toContain('城市加载中')

    const errorMount = mountSheet({
      directory: null,
      currentCity: null,
      error: '城市目录加载失败',
    })
    expect(textOf(errorMount.root)).toContain('城市目录加载失败')
    expect(textOf(errorMount.root)).toContain('重新加载')

    const emptyMount = mountSheet({
      currentCity: null,
      recentCities: [],
      directory: {
        hotCities: [],
        provinces: [],
        openCities: [],
        preparingCities: [],
      },
    })
    expect(textOf(emptyMount.root)).toContain('暂无已开通的城市，可以输入城市名搜索')
  })

  it('uses the approved copy when a city search has no results', async () => {
    const { root } = mountSheet()

    await typeKeyword(root, 'zzzznotfound')

    expect(textOf(root)).toContain('未找到相关城市')
  })

  it('blocks relocation and current selection while unavailable', async () => {
    const { events, root } = mountSheet({
      currentCity: {
        code: '999999',
        name: '未知城市',
      },
      relocating: true,
    })
    const currentButton = findByClass(root, 'city-sheet__current-city')
    const relocateButton = findByClass(root, 'city-sheet__relocate')

    expect(currentButton.props.disabled).toBe(true)
    expect(relocateButton.props.disabled).toBe(true)
    expect(textOf(relocateButton)).toContain('定位中…')

    await trigger(currentButton, 'onClick')
    await trigger(relocateButton, 'onClick')
    expect(events.select).not.toHaveBeenCalled()
    expect(events.relocate).not.toHaveBeenCalled()
  })
})

describe('city selection sheet static visual contract', () => {
  it('keeps the safe area, grid, and button sizing constraints', () => {
    expect(source).toContain('env(safe-area-inset-bottom)')
    expect(source).toContain('height: min(68vh, 760px)')
    expect(source).toContain(
      'grid-template-columns: repeat(3, minmax(0, 1fr))',
    )
    expect(source).toMatch(
      /\.city-sheet__city \{[\s\S]*?margin: 0;/,
    )
    expect(source).toMatch(
      /\.city-sheet__grid \.city-sheet__city \{[\s\S]*?width: 100%;/,
    )
    expect(source).not.toContain('showActionSheet')
    expect(source).not.toMatch(/[\u{1F300}-\u{1FAFF}]/u)
    expect(source).not.toContain('<svg')
  })
})

describe('inspection catalog city selection integration', () => {
  it('uses the production city sheet and refreshes the selected city', () => {
    expect(catalogPageSource).toContain('<CitySelectionSheet')
    expect(catalogPageSource).toContain('platformCityApi.load')
    expect(catalogPageSource).toContain('currentCityStore.setCurrentCity')
    expect(catalogPageSource).toContain('recentCityStorage.save')
    expect(catalogPageSource).toContain("source === 'SEARCH'")
    expect(catalogPageSource).toContain('loadCatalogContent')
    expect(catalogPageSource).toContain(
      '@close="catalogCityController.closeCitySheet"',
    )
    expect(catalogPageSource).not.toContain('DEVELOPMENT_CITIES')
    expect(catalogPageSource).not.toContain('showActionSheet')
    expect(catalogPageSource).not.toContain('import.meta.env.DEV')
  })

  it('records only SEARCH selections and always resets and reloads', async () => {
    const createController = await loadCatalogCityControllerFactory()
    const recordSearchSelection = vi.fn(async () => undefined)
    const reloadCatalog = vi.fn(async () => undefined)
    const setCurrentCity = vi.fn()
    let page = 5
    const setPage = vi.fn((nextPage: number) => {
      page = nextPage
    })
    const controller = createController({
      setCurrentCity,
      saveRecentCity: vi.fn(),
      loadRecentCities: () => [],
      setRecentCities: vi.fn(),
      closeCitySheet: vi.fn(),
      setPage,
      reloadCatalog,
      recordSearchSelection,
      getDirectory: () => directory,
      isDirectoryLoading: () => false,
      loadDirectory: vi.fn(async () => undefined),
      isRelocating: () => false,
      setRelocating: vi.fn(),
      locateCityName: vi.fn(async () => jinan.name),
      showToast: vi.fn(),
    })

    for (const source of ['CURRENT', 'OPEN', 'PREPARING', 'RECENT', 'HOT', 'TREE'] as const) {
      await controller.applyCitySelection({ city: jinan, source })
    }
    expect(recordSearchSelection).not.toHaveBeenCalled()

    await controller.applyCitySelection({ city: yantai, source: 'SEARCH' })
    expect(recordSearchSelection).toHaveBeenCalledTimes(1)
    expect(recordSearchSelection).toHaveBeenCalledWith(yantai.code)
    expect(setCurrentCity).toHaveBeenLastCalledWith({
      code: yantai.code,
      name: yantai.name,
      provinceCode: yantai.provinceCode,
    })
    expect(setPage).toHaveBeenCalledTimes(7)
    expect(setPage).toHaveBeenLastCalledWith(1)
    expect(page).toBe(1)
    expect(reloadCatalog).toHaveBeenCalledTimes(7)
  })

  it.each(['read', 'write'] as const)(
    'keeps selection and reload working when storage %s fails',
    async (failure) => {
      const createController = await loadCatalogCityControllerFactory()
      const setCurrentCity = vi.fn()
      const setPage = vi.fn()
      const reloadCatalog = vi.fn(async () => undefined)
      const showToast = vi.fn()
      const controller = createController({
        setCurrentCity,
        saveRecentCity: () => {
          if (failure === 'write') {
            throw new Error('STORAGE_WRITE_FAILED')
          }
        },
        loadRecentCities: () => {
          if (failure === 'read') {
            throw new Error('STORAGE_READ_FAILED')
          }
          return []
        },
        setRecentCities: vi.fn(),
        closeCitySheet: vi.fn(),
        setPage,
        reloadCatalog,
        recordSearchSelection: vi.fn(async () => {
          throw new Error('COUNT_FAILED')
        }),
        getDirectory: () => directory,
        isDirectoryLoading: () => false,
        loadDirectory: vi.fn(async () => undefined),
        isRelocating: () => false,
        setRelocating: vi.fn(),
        locateCityName: vi.fn(async () => jinan.name),
        showToast,
      })

      await controller.applyCitySelection({
        city: yantai,
        source: 'SEARCH',
      })

      expect(setCurrentCity).toHaveBeenCalledOnce()
      expect(setPage).toHaveBeenCalledWith(1)
      expect(reloadCatalog).toHaveBeenCalledOnce()
      expect(showToast).toHaveBeenCalledWith('最近城市保存失败')
    },
  )

  it('keeps the current city on location failure and explains unavailable directories', async () => {
    const createController = await loadCatalogCityControllerFactory()
    const setCurrentCity = vi.fn()
    const reloadCatalog = vi.fn(async () => undefined)
    const showToast = vi.fn()
    let activeDirectory: CityDirectory | null = directory
    let loading = false
    const loadDirectory = vi.fn(async () => undefined)
    const baseOptions: CatalogCityControllerOptions = {
      setCurrentCity,
      saveRecentCity: vi.fn(),
      loadRecentCities: () => [],
      setRecentCities: vi.fn(),
      closeCitySheet: vi.fn(),
      setPage: vi.fn(),
      reloadCatalog,
      recordSearchSelection: vi.fn(async () => undefined),
      getDirectory: () => activeDirectory,
      isDirectoryLoading: () => loading,
      loadDirectory,
      isRelocating: () => false,
      setRelocating: vi.fn(),
      locateCityName: vi.fn(async () => {
        throw new Error('LOCATION_UNAVAILABLE')
      }),
      showToast,
    }
    const controller = createController(baseOptions)

    await controller.relocateCity()
    expect(setCurrentCity).not.toHaveBeenCalled()
    expect(reloadCatalog).not.toHaveBeenCalled()
    expect(showToast).toHaveBeenLastCalledWith(
      '定位失败，请手动选择城市',
    )

    activeDirectory = null
    loading = true
    await controller.relocateCity()
    expect(showToast).toHaveBeenLastCalledWith('城市加载中，请稍候')

    loading = false
    await controller.relocateCity()
    expect(loadDirectory).toHaveBeenCalledOnce()
    expect(showToast).toHaveBeenLastCalledWith(
      '请先重新加载城市目录',
    )
  })

  it('keeps a manual city selected when an earlier relocation returns late', async () => {
    const createController = await loadCatalogCityControllerFactory()
    let resolveLocatedName: ((name: string) => void) | undefined
    let relocating = false
    const setCurrentCity = vi.fn()
    const reloadCatalog = vi.fn(async () => undefined)
    const showToast = vi.fn()
    const controller = createController({
      setCurrentCity,
      saveRecentCity: vi.fn(),
      loadRecentCities: () => [],
      setRecentCities: vi.fn(),
      closeCitySheet: vi.fn(),
      setPage: vi.fn(),
      reloadCatalog,
      recordSearchSelection: vi.fn(async () => undefined),
      getDirectory: () => directory,
      isDirectoryLoading: () => false,
      loadDirectory: vi.fn(async () => undefined),
      isRelocating: () => relocating,
      setRelocating: (value) => {
        relocating = value
      },
      locateCityName: () => new Promise((resolve) => {
        resolveLocatedName = resolve
      }),
      showToast,
    })

    const pendingRelocation = controller.relocateCity()
    await Promise.resolve()
    expect(relocating).toBe(true)

    await controller.applyCitySelection({
      city: yantai,
      source: 'TREE',
    })
    expect(relocating).toBe(false)

    resolveLocatedName?.(jinan.name)
    await pendingRelocation

    expect(setCurrentCity).toHaveBeenCalledOnce()
    expect(setCurrentCity).toHaveBeenCalledWith({
      code: yantai.code,
      name: yantai.name,
      provinceCode: yantai.provinceCode,
    })
    expect(reloadCatalog).toHaveBeenCalledOnce()
    expect(showToast).not.toHaveBeenCalled()
    expect(relocating).toBe(false)
  })

  it('ignores a relocation result after the city sheet closes', async () => {
    const createController = await loadCatalogCityControllerFactory()
    let resolveLocatedName: ((name: string) => void) | undefined
    let relocating = false
    const setCurrentCity = vi.fn()
    const reloadCatalog = vi.fn(async () => undefined)
    const closeCitySheet = vi.fn()
    const showToast = vi.fn()
    const controller = createController({
      setCurrentCity,
      saveRecentCity: vi.fn(),
      loadRecentCities: () => [],
      setRecentCities: vi.fn(),
      closeCitySheet,
      setPage: vi.fn(),
      reloadCatalog,
      recordSearchSelection: vi.fn(async () => undefined),
      getDirectory: () => directory,
      isDirectoryLoading: () => false,
      loadDirectory: vi.fn(async () => undefined),
      isRelocating: () => relocating,
      setRelocating: (value) => {
        relocating = value
      },
      locateCityName: () => new Promise((resolve) => {
        resolveLocatedName = resolve
      }),
      showToast,
    })

    const pendingRelocation = controller.relocateCity()
    await Promise.resolve()
    expect(relocating).toBe(true)

    controller.closeCitySheet()
    expect(closeCitySheet).toHaveBeenCalledOnce()
    expect(relocating).toBe(false)

    resolveLocatedName?.(jinan.name)
    await pendingRelocation

    expect(setCurrentCity).not.toHaveBeenCalled()
    expect(reloadCatalog).not.toHaveBeenCalled()
    expect(showToast).not.toHaveBeenCalled()
    expect(relocating).toBe(false)
  })

  it('applies a relocation result when no newer city interaction occurs', async () => {
    const createController = await loadCatalogCityControllerFactory()
    let relocating = false
    const setCurrentCity = vi.fn()
    const reloadCatalog = vi.fn(async () => undefined)
    const showToast = vi.fn()
    const controller = createController({
      setCurrentCity,
      saveRecentCity: vi.fn(),
      loadRecentCities: () => [],
      setRecentCities: vi.fn(),
      closeCitySheet: vi.fn(),
      setPage: vi.fn(),
      reloadCatalog,
      recordSearchSelection: vi.fn(async () => undefined),
      getDirectory: () => directory,
      isDirectoryLoading: () => false,
      loadDirectory: vi.fn(async () => undefined),
      isRelocating: () => relocating,
      setRelocating: (value) => {
        relocating = value
      },
      locateCityName: vi.fn(async () => jinan.name),
      showToast,
    })

    await controller.relocateCity()

    expect(setCurrentCity).toHaveBeenCalledOnce()
    expect(reloadCatalog).toHaveBeenCalledOnce()
    expect(showToast).not.toHaveBeenCalled()
    expect(relocating).toBe(false)
  })
})
