import { compileScript, parse } from '@vue/compiler-sfc'
import {
  createRenderer,
  defineComponent,
  h,
  type App,
  type Component,
} from '@vue/runtime-core'
import * as VueRuntime from '@vue/runtime-core'
import { ModuleKind, ScriptTarget, transpileModule } from 'typescript'
import { afterEach, describe, expect, it } from 'vitest'

import * as catalogModel from '../presentation/catalog/model/inspection-catalog'
import type { InspectionCatalogService } from '../presentation/catalog/model/inspection-catalog'
import cardSource from '../presentation/catalog/components/InspectionServiceCard.vue?raw'
import headerSource from '../presentation/catalog/components/CatalogHeader.vue?raw'
import pagesJson from '../../../pages.json'

interface HostNode {
  type: string
  props: Record<string, unknown>
  children: HostNode[]
  parent: HostNode | null
  text: string
}

const UniIconsMock = defineComponent({
  name: 'UniIconsMock',
  props: { type: String, size: [String, Number], color: String },
  setup(props) {
    return () => h('uni-icons-mock', { ...props })
  },
})

function compileCard(): Component {
  const parsed = parse(cardSource, { filename: 'InspectionServiceCard.vue' })
  if (parsed.errors.length > 0) {
    throw new Error(`SFC parse failed: ${String(parsed.errors[0])}`)
  }
  const compiled = compileScript(parsed.descriptor, {
    id: 'inspection-service-card-test',
    inlineTemplate: true,
    templateOptions: {
      compilerOptions: {
        isCustomElement: (tag) => tag === tag.toLocaleLowerCase(),
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
  const testModule: { exports: { default?: Component } } = { exports: {} }
  const testRequire = (request: string): unknown => {
    if (request === 'vue') return { ...VueRuntime, withModifiers: (handler: unknown) => handler }
    if (request === '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue') return UniIconsMock
    if (request === '../model/inspection-catalog') return catalogModel
    throw new Error(`Unexpected SFC dependency: ${request}`)
  }
  new Function('require', 'module', 'exports', output)(testRequire, testModule, testModule.exports)
  if (!testModule.exports.default) throw new Error('Compiled SFC did not export a component')
  return testModule.exports.default
}

const InspectionServiceCard = compileCard()

function createHostNode(type: string, text = ''): HostNode {
  return { type, props: {}, children: [], parent: null, text }
}

const renderer = createRenderer<HostNode, HostNode>({
  patchProp(element, key, _previous, next) {
    if (next == null) delete element.props[key]
    else element.props[key] = next
  },
  insert(child, parent, anchor) {
    if (child.parent) {
      const index = child.parent.children.indexOf(child)
      if (index >= 0) child.parent.children.splice(index, 1)
    }
    child.parent = parent
    const anchorIndex = anchor ? parent.children.indexOf(anchor) : -1
    if (anchorIndex >= 0) parent.children.splice(anchorIndex, 0, child)
    else parent.children.push(child)
  },
  remove(child) {
    if (!child.parent) return
    const index = child.parent.children.indexOf(child)
    if (index >= 0) child.parent.children.splice(index, 1)
    child.parent = null
  },
  createElement: (type) => createHostNode(type),
  createText: (text) => createHostNode('#text', text),
  createComment: () => createHostNode('#comment'),
  setText(node, text) {
    node.text = text
  },
  setElementText(element, text) {
    element.children = []
    element.text = text
  },
  parentNode: (node) => node.parent,
  nextSibling(node) {
    if (!node.parent) return null
    return node.parent.children[node.parent.children.indexOf(node) + 1] ?? null
  },
  querySelector: () => null,
  setScopeId(element, id) {
    element.props[id] = ''
  },
  insertStaticContent(content, parent, anchor) {
    const node = createHostNode('#static', content)
    node.parent = parent
    const anchorIndex = anchor ? parent.children.indexOf(anchor) : -1
    if (anchorIndex >= 0) parent.children.splice(anchorIndex, 0, node)
    else parent.children.push(node)
    return [node, node]
  },
})

const mountedApps: App[] = []
afterEach(() => {
  mountedApps.splice(0).forEach((app) => app.unmount())
})

function descendants(node: HostNode): HostNode[] {
  return node.children.flatMap((child) => [child, ...descendants(child)])
}

function textOf(node: HostNode): string {
  return `${node.text}${node.children.map(textOf).join('')}`
}

function hasClass(node: HostNode, className: string): boolean {
  return String(node.props.class ?? '').split(/\s+/).includes(className)
}

function renderCard(overrides: Partial<InspectionCatalogService> = {}): HostNode {
  const service: InspectionCatalogService = {
    id: 'power-001',
    category: 'TRANSMISSION',
    categoryLabel: '输电',
    title: '输电线路无人机巡检',
    description: '杆塔精细巡检 · 导线通道检测',
    tags: ['高精度建模', '巡检报告'],
    imageUrl: '/static/images/inspection/service-power-line.webp',
    price: { mode: 'STARTING_PRICE', amountFen: 69900, unitLabel: '公里起' },
    ...overrides,
  }
  const root = createHostNode('root')
  const app = renderer.createApp(defineComponent({
    setup() {
      return () => h(InspectionServiceCard, { service })
    },
  }))
  app.mount(root)
  mountedApps.push(app)
  return root
}

function priceNoteNodes(root: HostNode): HostNode[] {
  return descendants(root).filter((node) => hasClass(node, 'inspection-card__price-note'))
}

describe('M02 service card price note (T04)', () => {
  it('shows the server price note on the line under the price', () => {
    const root = renderCard({ priceNote: '含现场巡检和报告' })

    const notes = priceNoteNodes(root)
    expect(notes).toHaveLength(1)
    expect(textOf(notes[0]).trim()).toBe('含现场巡检和报告')
    expect(textOf(root)).toContain('¥699')
  })

  it('renders no note element at all when the service has none', () => {
    const root = renderCard()

    expect(priceNoteNodes(root)).toHaveLength(0)
    expect(textOf(root)).toContain('¥699')
  })

  it('shows the server wording for project-quote services next to 按项目报价', () => {
    const root = renderCard({
      price: { mode: 'PROJECT_QUOTE' },
      priceNote: '方案确认后定价',
    })

    expect(textOf(root)).toContain('按项目报价')
    expect(textOf(priceNoteNodes(root)[0]).trim()).toBe('方案确认后定价')
  })
})

describe('M02 header copy (T04)', () => {
  it('titles the list 巡检服务 and keeps the AGENTS.md §8 search placeholder', () => {
    expect(headerSource).toContain('title="巡检服务"')
    expect(headerSource).not.toContain('电力巡检')
    expect(headerSource).toContain('placeholder="搜索巡检服务、场景或关键词"')
  })

  it('keeps the pages.json catalog title in step with the header', () => {
    const catalog = (pagesJson as { pages: Array<{ path: string; style?: { navigationBarTitleText?: string } }> })
      .pages.find((page) => page.path === 'pages/service/inspection/catalog')
    expect(catalog?.style?.navigationBarTitleText).toBe('巡检服务')
  })
})
