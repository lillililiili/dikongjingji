import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { describe, expect, it } from 'vitest'
import { reactive } from 'vue'

import type { ApiClient } from '@/core/http/api-client'

import BlockEditor from './components/BlockEditor.vue'
import { createContentOpsApi, type OpsArticle } from './content-api'
import {
  articleForm,
  articleProblems,
  articleRequest,
  beijingToIso,
  blockProblem,
  emptyBlock,
  formatDuration,
  idsFromText,
  tagsFromText,
  topicForm,
  topicProblems,
  topicRequest,
  videoForm,
  videoProblems,
  videoRequest,
  type BlockForm,
} from './content-rules'

const EAGLE = 'f2000000-0000-4000-8000-000000000001'
const ROBOT = 'c1000000-0000-4000-8000-000000000001'
const KIT = 'c2000000-0000-4000-8000-000000000101'
const ACTIVITY = 'a2000000-0000-4000-8000-000000000001'

const ARTICLE: OpsArticle = {
  articleId: 'f3000000-0000-4000-8000-000000000001',
  kind: 'CASE',
  title: '一台机器狗如何完成园区夜间巡逻',
  author: { kind: 'PROVIDER', organizationId: EAGLE, name: '鹰眼智能巡检' },
  sponsored: false,
  cityCode: '370500',
  coverUrl: null,
  sample: true,
  status: 'DRAFT',
  publishedAt: null,
  blocks: [
    { type: 'STEPS', text: null, items: [{ title: '白天踏勘，定路线', text: null }], image: null },
    { type: 'IMAGE', text: null, items: [], image: { url: null, alt: '无人机在园区上空巡查', caption: '围墙外侧由无人机补充巡查' } },
  ],
  refs: [{ type: 'PRODUCT', refId: 'c2000000-0000-4000-8000-000000000401', title: '巡检机器狗' }],
  publishMissing: ['coverUrl', 'imageUrl'],
  version: 2,
  updatedAt: '2026-11-23T02:00:00Z',
}

describe('图文表单', () => {
  it('从详情回填表单，再拼回同样的请求体（配图块只带图片字段）', () => {
    const form = articleForm(ARTICLE)
    expect(form.author).toEqual({ kind: 'PROVIDER', organizationId: EAGLE, organizationName: '鹰眼智能巡检', name: '' })
    expect(form.blocks[1]).toMatchObject({ type: 'IMAGE', imageUrl: '', imageAlt: '无人机在园区上空巡查' })

    form.blocks[1]!.imageUrl = ' https://media.example.com/ops-media/a.png '
    const request = articleRequest(form, ARTICLE.version)
    expect(request).toMatchObject({
      kind: 'CASE',
      authorKind: 'PROVIDER',
      authorOrganizationId: EAGLE,
      authorName: null,
      cityCode: '370500',
      coverUrl: null,
      sample: true,
      version: 2,
    })
    expect(request.blocks).toEqual([
      { type: 'STEPS', text: null, items: [{ title: '白天踏勘，定路线', text: null }], imageUrl: null, imageAlt: null, imageCaption: null },
      { type: 'IMAGE', text: null, items: null, imageUrl: 'https://media.example.com/ops-media/a.png', imageAlt: '无人机在园区上空巡查', imageCaption: '围墙外侧由无人机补充巡查' },
    ])
    expect(request.refs).toEqual(ARTICLE.refs)
  })

  it('平台作者写署名、不带机构；新建默认一段正文', () => {
    const form = articleForm()
    expect(form.blocks.map((block) => block.type)).toEqual(['PARAGRAPH'])
    form.kind = 'KNOWLEDGE'
    form.author = { kind: 'PLATFORM', organizationId: EAGLE, organizationName: '', name: ' 东营城市运营中心 ' }
    form.blocks[0]!.text = '  第一段\n第二段 '
    expect(articleRequest(form, null)).toMatchObject({
      authorKind: 'PLATFORM', authorOrganizationId: null, authorName: '东营城市运营中心', cityCode: null, version: null,
      blocks: [{ type: 'PARAGRAPH', text: '第一段\n第二段' }],
    })
  })

  it('提交前挡住：案例要城市、不能平台发布，块的内容和类型要对上，提到的对象 ID 要对', () => {
    const form = articleForm()
    form.author = { kind: 'PLATFORM', organizationId: '', organizationName: '', name: '平台' }
    form.blocks = [emptyBlock('PARAGRAPH'), emptyBlock('FACTS'), emptyBlock('IMAGE')]
    form.refs = [{ key: 'r', type: 'PRODUCT', refId: 'not-a-uuid', title: '狗' }]
    expect(articleProblems(form)).toEqual([
      '案例不能由平台发布，请选服务商或商家',
      '案例要选城市',
      '第 1 块（段落）还没写内容',
      '第 2 块（要点表）至少要一条',
      '第 3 块（正文配图）要写替代文字（图片看不到时读出来的话）',
      '内容中提到的“狗”ID 格式不对',
    ])
  })

  it('一块一块地看：小标题只能一行，配图地址要 https，没地址也能先存', () => {
    const heading: BlockForm = { ...emptyBlock('HEADING'), text: '第一行\n第二行' }
    expect(blockProblem(heading, 1)).toBe('第 1 块（小标题）只能写一行')
    const image: BlockForm = { ...emptyBlock('IMAGE'), imageAlt: '替代', imageUrl: 'http://cdn.example.com/a.png' }
    expect(blockProblem(image, 2)).toBe('第 2 块（正文配图）的图片地址要是 https')
    expect(blockProblem({ ...image, imageUrl: '' }, 2)).toBeNull()
    const steps: BlockForm = { ...emptyBlock('STEPS'), items: [{ key: 'a', title: '', text: '只有说明' }] }
    expect(blockProblem(steps, 3)).toBe('第 3 块（步骤）每条都要有标题')
  })
})

describe('视频表单', () => {
  it('话题去掉 # 和重复，时长、排序和地址要合格', () => {
    expect(tagsFromText('#航拍  黄河口,航拍，#无人机')).toEqual(['航拍', '黄河口', '无人机'])
    const form = videoForm()
    form.title = '4K 航拍黄河口'
    form.author = { kind: 'PROVIDER', organizationId: EAGLE, organizationName: '鹰眼智能巡检', name: '' }
    form.cityCode = '370500'
    form.tagsText = '#航拍 黄河口'
    form.rank = 60
    form.durationSeconds = 95
    expect(videoProblems(form)).toEqual([])
    expect(videoRequest(form, 3)).toMatchObject({ tags: ['航拍', '黄河口'], rank: 60, playUrl: null, version: 3 })
    form.playUrl = 'http://cdn.example.com/a.mp4'
    form.durationSeconds = 0
    expect(videoProblems(form)).toEqual(['时长要在 1–3600 秒', '播放地址要是 https'])
    expect(formatDuration(95)).toBe('1:35')
  })
})

describe('专题表单', () => {
  it('挂的 ID 一行一个，去重并检查格式；商家内容要商品 ID 和发布商家', () => {
    expect(idsFromText(`${ACTIVITY}\n\n${ACTIVITY.toUpperCase()}, bad`)).toEqual({
      ids: [ACTIVITY], invalid: ['bad'], repeated: [ACTIVITY.toUpperCase()],
    })
    const form = topicForm()
    expect(form.publisher.kind).toBe('PLATFORM')
    form.title = '黄河口低空体验季'
    form.publisher.name = '东营城市运营中心'
    form.cityCode = '370500'
    form.startsOn = '2026-11-01'
    form.endsOn = '2026-11-30'
    form.activityIdsText = ACTIVITY
    form.merchantItems = [{ key: 'm', productId: KIT.toUpperCase(), merchantOrganizationId: ROBOT, merchantName: '示例机器人商家' }]
    expect(topicProblems(form)).toEqual([])
    expect(topicRequest(form, null)).toMatchObject({
      publisherKind: 'PLATFORM', publisherName: '东营城市运营中心', activityIds: [ACTIVITY], venueIds: [], articleIds: [],
      merchantItems: [{ productId: KIT, merchantOrganizationId: ROBOT }],
    })
    form.endsOn = '2026-10-01'
    form.merchantItems.push({ key: 'n', productId: KIT, merchantOrganizationId: '', merchantName: '' })
    expect(topicProblems(form)).toEqual([
      '结束日期不能早于开始日期',
      '商家内容要选发布商家',
      '商家内容的同一件商品只能挂一次',
    ])
  })

  it('定时发布按北京时间拼出带 +08:00 的时间', () => {
    expect(beijingToIso('2026-11-25 09:30')).toBe('2026-11-25T09:30:00+08:00')
    expect(beijingToIso('11/25 09:30')).toBeNull()
  })
})

describe('接口拼法', () => {
  function recorder() {
    const calls: Array<[string, string, unknown]> = []
    const http = {
      async request() { return undefined },
      async get(path: string, query?: unknown) { calls.push(['GET', path, query]); return undefined },
      async post(path: string, body?: unknown) { calls.push(['POST', path, body]); return undefined },
      async put(path: string, body?: unknown) { calls.push(['PUT', path, body]); return undefined },
      async delete(path: string) { calls.push(['DELETE', path, undefined]); return undefined },
      async download() { return { blob: new Blob(), fileName: null } },
    } as unknown as ApiClient
    return { calls, api: createContentOpsApi(http) }
  }

  it('三类内容各走自己的路径，上线带版本号和发布时间', async () => {
    const { calls, api } = recorder()
    await api.articles({ keyword: ' 机器狗 ', kind: 'CASE', cityCode: '370500', status: '' }, 2, 10)
    await api.publishArticle(ARTICLE.articleId, 2, '2026-11-25T09:30:00+08:00')
    await api.offlineVideo('v/1', 4)
    await api.publishTopic('t1', 0)
    await api.topicChanges('t1')
    expect(calls).toEqual([
      ['GET', '/api/v1/ops/content/articles', { keyword: '机器狗', kind: 'CASE', cityCode: '370500', status: '', page: 2, size: 10 }],
      ['POST', `/api/v1/ops/content/articles/${ARTICLE.articleId}/publish`, { version: 2, publishAt: '2026-11-25T09:30:00+08:00' }],
      ['POST', '/api/v1/ops/content/videos/v%2F1/offline', { version: 4 }],
      ['POST', '/api/v1/ops/content/topics/t1/publish', { version: 0 }],
      ['GET', '/api/v1/ops/content/topics/t1/changes', undefined],
    ])
  })
})

describe('正文块编辑器', () => {
  it('加一块配图、换类型、上移和删除都直接改表单里的数组', async () => {
    const blocks = reactive<BlockForm[]>([{ ...emptyBlock('PARAGRAPH'), text: '第一段' }, emptyBlock('STEPS')])
    const wrapper = mount(BlockEditor, { props: { blocks }, global: { plugins: [ElementPlus] } })

    await wrapper.get('[data-testid="add-block-IMAGE"]').trigger('click')
    expect(blocks.map((block) => block.type)).toEqual(['PARAGRAPH', 'STEPS', 'IMAGE'])
    expect(wrapper.text()).toContain('要写替代文字')
    expect(wrapper.text()).toContain('3 / 40 块')

    const moveUp = wrapper.get('[data-testid="block-2"]').findAll('button').find((button) => button.text() === '上移')
    await moveUp!.trigger('click')
    expect(blocks.map((block) => block.type)).toEqual(['PARAGRAPH', 'IMAGE', 'STEPS'])

    const remove = wrapper.get('[data-testid="block-0"]').findAll('button').find((button) => button.text() === '删除')
    await remove!.trigger('click')
    expect(blocks.map((block) => block.type)).toEqual(['IMAGE', 'STEPS'])
  })
})
