import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import { ApiRequestError } from '@/core/http/api-error'

import type { OpsCourse } from './course-api'

const get = vi.fn()
const put = vi.fn()
const post = vi.fn()
vi.mock('@/core/http', () => ({ http: { get, put, post } }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { courseId: 'course-1' }, query: {} }),
  useRouter: () => ({ push: vi.fn(), replace: vi.fn() }),
}))
const confirmAction = vi.fn()
const showError = vi.fn()
const notifyRecorded = vi.fn()
vi.mock('@/core/ui/feedback', () => ({ confirmAction, showError, notifyRecorded }))

const { default: CourseEditPage } = await import('./CourseEditPage.vue')
const { default: SimulationSelect } = await import('./components/SimulationSelect.vue')

const PRODUCT = 'c1000000-0000-4000-8000-000000000001'

function course(overrides: Partial<OpsCourse> = {}): OpsCourse {
  return {
    courseId: 'course-1',
    mode: 'ONLINE',
    topic: 'DRONE',
    status: 'DRAFT',
    title: '无人机航拍入门',
    subtitle: null,
    organization: { organizationId: 'org-1', name: '云翼科技' },
    coverUrl: null,
    galleryUrls: [],
    priceAmountMinor: 19900,
    validityDays: null,
    ageNote: null,
    instructorName: null,
    instructorTitle: null,
    includes: [],
    extraSpecs: [],
    intro: [],
    experienceItemId: null,
    simulationItemId: null,
    bundledProductIds: [PRODUCT],
    activityId: null,
    scheduleLabel: null,
    sortOrder: 0,
    chapters: [{
      chapterId: 'ch-1',
      title: '起飞前',
      lessons: [
        { lessonId: 'l-1', no: 1, title: '认识遥控器', durationSeconds: 750, trial: true, videoUrl: null, learnerCount: 2 },
        { lessonId: 'l-2', no: 2, title: '检查清单', durationSeconds: 65, trial: false, videoUrl: null, learnerCount: 0 },
      ],
    }],
    lessonCount: 2,
    enrollmentCount: 2,
    listMissing: ['coverUrl', 'videos'],
    firstListedAt: null,
    createdAt: '2026-11-20T02:00:00Z',
    updatedAt: '2026-11-22T02:00:00Z',
    version: 3,
    ...overrides,
  }
}

async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    await flushPromises()
    await new Promise((resolve) => setTimeout(resolve, 0))
  }
}

function button(selector: string): HTMLButtonElement {
  const found = document.body.querySelector<HTMLButtonElement>(`[data-test="${selector}"]`)
  if (!found) throw new Error(`no ${selector}`)
  return found
}

function buttonsByText(text: string): HTMLButtonElement[] {
  return Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
    .filter((candidate) => candidate.textContent?.trim() === text)
}

function serve(current: OpsCourse): void {
  get.mockImplementation(async (path: string) => {
    if (path === '/api/v1/ops/courses/course-1') return current
    if (path === '/api/v1/ops/courses/course-1/changes') return []
    if (path === `/api/v1/ops/catalog/products/${PRODUCT}`) {
      return { product: { name: '航拍无人机套装', category: 'DRONE', status: 'PUBLISHED' } }
    }
    throw new Error(`unexpected GET ${path}`)
  })
}

describe('课程详情', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    get.mockReset()
    put.mockReset()
    post.mockReset()
    confirmAction.mockReset()
    showError.mockReset()
    notifyRecorded.mockReset()
  })

  it('上架前还差的项翻成中文，上架按钮禁用；附带商品带出商品名', async () => {
    serve(course())
    mount(CourseEditPage, { global: { plugins: [ElementPlus] }, attachTo: document.body })
    await settle()

    expect(document.body.querySelector('[data-test="missing"]')?.textContent).toContain('上架前还差：封面、每节课时都有视频')
    expect(button('publish').disabled).toBe(true)
    expect(document.body.textContent).toContain('航拍无人机套装（无人机 · 已上架）')
  })

  it('线上课可单独配置试听后的仿真，保存不改变原有试听入口', async () => {
    const simulationItemId = 'b1000000-0000-4000-8000-000000000002'
    const experienceItemId = 'b1000000-0000-4000-8000-000000000005'
    serve(course({ experienceItemId }))
    confirmAction.mockResolvedValue(true)
    put.mockResolvedValue(course({ experienceItemId, simulationItemId, version: 4 }))
    const wrapper = mount(CourseEditPage, { global: { plugins: [ElementPlus] }, attachTo: document.body })
    await settle()
    const picker = wrapper.getComponent(SimulationSelect)
    get.mockResolvedValueOnce({ items: [{ experienceId: simulationItemId, title: '无人机起降与悬停' }], hasMore: false })
    const select = picker.getComponent({ name: 'ElSelect' })
    select.vm.$emit('visible-change', true)
    await settle()
    expect(get).toHaveBeenCalledWith('/api/v1/experiences', { type: 'SIMULATION', page: 1, size: 50 })
    expect(picker.findAllComponents({ name: 'ElOption' }).map(option => option.props('label'))).toContain('无人机起降与悬停')
    select.vm.$emit('update:modelValue', simulationItemId)
    await settle()
    button('save-info').click()
    await settle()
    expect(put).toHaveBeenCalledWith('/api/v1/ops/courses/course-1', expect.objectContaining({ experienceItemId, simulationItemId }))

    put.mockResolvedValue(course({ experienceItemId, simulationItemId: null, version: 5 }))
    select.vm.$emit('update:modelValue', undefined)
    await settle()
    button('save-info').click()
    await settle()
    expect(put).toHaveBeenLastCalledWith('/api/v1/ops/courses/course-1', expect.objectContaining({ version: 4, experienceItemId, simulationItemId: null }))
  })

  it('已配仿真不在首批选项时按 ID 读出名称，分页不要求选择城市', async () => {
    const simulationItemId = 'b1000000-0000-4000-8000-000000000099'
    const listed = { experienceId: 'b1000000-0000-4000-8000-000000000001', title: '机器人避障' }
    const current = { experienceId: simulationItemId, title: '第二页的仿真项目' }
    get.mockImplementation(async (path, query) => {
      if (path === '/api/v1/ops/courses/course-1') return course({ simulationItemId, bundledProductIds: [] })
      if (path === '/api/v1/ops/courses/course-1/changes') return []
      if (path === '/api/v1/experiences/' + simulationItemId) return current
      if (path === '/api/v1/experiences') return query.page === 1
        ? { items: [listed], hasMore: true }
        : { items: [current], hasMore: false }
      throw new Error('unexpected GET ' + path)
    })
    const wrapper = mount(CourseEditPage, { global: { plugins: [ElementPlus] }, attachTo: document.body })
    await settle()
    const picker = wrapper.getComponent(SimulationSelect)
    const select = picker.getComponent({ name: 'ElSelect' })
    select.vm.$emit('visible-change', true)
    await settle()
    expect(picker.findAllComponents({ name: 'ElOption' }).map(option => option.props('label')))
      .toEqual([current.title, listed.title])
    buttonsByText('加载更多仿真项目')[0]!.click()
    await settle()
    expect(get).toHaveBeenCalledWith('/api/v1/experiences', { type: 'SIMULATION', page: 2, size: 50 })
    expect(picker.findAllComponents({ name: 'ElOption' }).map(option => option.props('label')))
      .toEqual([listed.title, current.title])
  })

  it('有人学过的课时“删除”按钮禁用；保存目录带章节和课时 ID、版本号', async () => {
    serve(course())
    mount(CourseEditPage, { global: { plugins: [ElementPlus] }, attachTo: document.body })
    await settle()

    const removeButtons = Array.from(document.body.querySelectorAll<HTMLButtonElement>('.outline__lesson-remove'))
    expect(removeButtons.map((candidate) => candidate.disabled)).toEqual([true, false])
    expect(buttonsByText('删除本章')[0]!.disabled).toBe(true)

    removeButtons[1]!.click()
    await settle()
    confirmAction.mockResolvedValue(true)
    put.mockResolvedValue(course({ chapters: [course().chapters[0]!], version: 4 }))
    button('save-outline').click()
    await settle()

    expect(confirmAction.mock.calls[0]![0].message).toContain('删掉原来的 1 节')
    expect(put).toHaveBeenCalledWith('/api/v1/ops/courses/course-1/outline', {
      version: 3,
      chapters: [{
        chapterId: 'ch-1',
        title: '起飞前',
        lessons: [{ lessonId: 'l-1', title: '认识遥控器', durationSeconds: 750, trial: true, videoUrl: null }],
      }],
    })
    expect(notifyRecorded).toHaveBeenCalledWith('保存目录', '改动记录')
  })

  it('后端回 OPS_PUBLISH_INCOMPLETE 时把 details.missing 翻成中文', async () => {
    serve(course({ listMissing: [], coverUrl: 'https://cdn.example.com/c.jpg' }))
    mount(CourseEditPage, { global: { plugins: [ElementPlus] }, attachTo: document.body })
    await settle()

    confirmAction.mockResolvedValue(true)
    post.mockRejectedValue(new ApiRequestError(422, 'OPS_PUBLISH_INCOMPLETE', 'incomplete', 't-1', { missing: ['merchantAccount'] }))
    expect(button('publish').disabled).toBe(false)
    button('publish').click()
    await settle()

    expect(post).toHaveBeenCalledWith('/api/v1/ops/courses/course-1/list', { version: 3 })
    expect((showError.mock.calls[0]![0] as Error).message).toBe('上架前还差：开课机构登记在线收款账户')
  })

  it('版本冲突时问要不要刷新，确认后重新读课程', async () => {
    serve(course())
    mount(CourseEditPage, { global: { plugins: [ElementPlus] }, attachTo: document.body })
    await settle()

    confirmAction.mockResolvedValue(true)
    put.mockRejectedValue(new ApiRequestError(409, 'OPS_VERSION_CONFLICT', 'conflict', 't-2'))
    button('save-info').click()
    await settle()

    expect(put).toHaveBeenCalledWith('/api/v1/ops/courses/course-1', expect.objectContaining({ version: 3, priceAmountMinor: 19900 }))
    expect(confirmAction.mock.calls.map((call) => call[0].title)).toEqual(['确认保存基本资料', '课程刚被别人修改过'])
    const detailReads = get.mock.calls.filter((call) => call[0] === '/api/v1/ops/courses/course-1')
    expect(detailReads).toHaveLength(2)
  })

  it('已上架的显示下架按钮，下架带版本号', async () => {
    serve(course({ status: 'LISTED', listMissing: [] }))
    mount(CourseEditPage, { global: { plugins: [ElementPlus] }, attachTo: document.body })
    await settle()

    confirmAction.mockResolvedValue(true)
    post.mockResolvedValue(course({ status: 'UNLISTED', version: 4 }))
    expect(document.body.querySelector('[data-test="publish"]')).toBeNull()
    button('unlist').click()
    await settle()

    expect(post).toHaveBeenCalledWith('/api/v1/ops/courses/course-1/unlist', { version: 3 })
    expect(notifyRecorded).toHaveBeenCalledWith('下架', '改动记录')
  })
})
