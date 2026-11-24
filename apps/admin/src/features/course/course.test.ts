import type { FormItemRule, FormRules } from 'element-plus'
import { describe, expect, it, vi } from 'vitest'

import type { ApiClient } from '@/core/http/api-client'
import { ApiRequestError, errorMessage } from '@/core/http/api-error'
import { firstError } from '@/core/ui/form-rules'

import {
  courseFormOf,
  courseRequestOf,
  createCourseApi,
  createRequestOf,
  describeListMissing,
  emptyCreateForm,
  formatDuration,
  listMissingText,
  newChapter,
  newLesson,
  outlineFormOf,
  outlineRequestOf,
  parseDuration,
  type CourseCreateForm,
  type CourseForm,
  type OpsCourse,
} from './course-api'
import { courseProblems, courseRules, createRules, includeProblem, outlineProblems, specProblem } from './course-rules'

const ORG = 'a1000000-0000-4000-8000-000000000001'
const ACTIVITY = 'b1000000-0000-4000-8000-000000000001'
const PRODUCT = 'c1000000-0000-4000-8000-000000000001'
const EXPERIENCE = 'd1000000-0000-4000-8000-000000000001'
const SIMULATION = 'd1000000-0000-4000-8000-000000000002'

function fakeHttp() {
  const calls: Array<{ method: string, path: string, body?: unknown, query?: unknown }> = []
  const http = {
    request: vi.fn(),
    get: vi.fn(async (path: string, query?: unknown) => {
      calls.push({ method: 'GET', path, query })
      return {}
    }),
    post: vi.fn(async (path: string, body?: unknown) => {
      calls.push({ method: 'POST', path, body })
      return {}
    }),
    put: vi.fn(async (path: string, body?: unknown) => {
      calls.push({ method: 'PUT', path, body })
      return {}
    }),
    delete: vi.fn(),
    download: vi.fn(),
  }
  return { http: http as unknown as ApiClient, calls }
}

function onlineCourse(overrides: Partial<OpsCourse> = {}): OpsCourse {
  return {
    courseId: 'e1000000-0000-4000-8000-000000000001',
    mode: 'ONLINE',
    topic: 'DRONE',
    status: 'DRAFT',
    title: '无人机航拍入门',
    subtitle: '从起飞到成片',
    organization: { organizationId: ORG, name: '云翼科技' },
    coverUrl: 'https://cdn.example.com/cover.jpg',
    galleryUrls: ['https://cdn.example.com/1.jpg'],
    priceAmountMinor: 19990,
    validityDays: 365,
    ageNote: '12 岁以上',
    instructorName: '王老师',
    instructorTitle: '资深飞手',
    includes: [{ name: '课程视频', quantity: 12 }],
    extraSpecs: [{ label: '上课方式', value: '线上录播' }],
    intro: ['第一段'],
    experienceItemId: EXPERIENCE,
    simulationItemId: SIMULATION,
    bundledProductIds: [PRODUCT],
    activityId: null,
    scheduleLabel: null,
    sortOrder: 10,
    chapters: [{
      chapterId: 'f1000000-0000-4000-8000-000000000001',
      title: '起飞前',
      lessons: [
        { lessonId: 'f2000000-0000-4000-8000-000000000001', no: 1, title: '认识遥控器', durationSeconds: 750, trial: true, videoUrl: 'https://cdn.example.com/1.mp4', learnerCount: 3 },
        { lessonId: 'f2000000-0000-4000-8000-000000000002', no: 2, title: '检查清单', durationSeconds: 65, trial: false, videoUrl: null, learnerCount: 0 },
      ],
    }],
    lessonCount: 2,
    enrollmentCount: 3,
    listMissing: [],
    firstListedAt: null,
    createdAt: '2026-11-20T02:00:00Z',
    updatedAt: '2026-11-22T02:00:00Z',
    version: 4,
    ...overrides,
  }
}

function rulesOf(rules: FormRules, field: string): FormItemRule[] {
  const value = rules[field]
  return (Array.isArray(value) ? value : value ? [value] : []) as FormItemRule[]
}

describe('新建课程的请求体', () => {
  it('线上课不带活动；线下班带活动', () => {
    const online = createRequestOf({ ...emptyCreateForm(), title: '  航拍入门 ', organizationId: ORG, activityId: ACTIVITY })
    expect(online).toEqual({ mode: 'ONLINE', topic: 'DRONE', title: '航拍入门', organizationId: ORG, activityId: null })

    const offline = createRequestOf({ mode: 'OFFLINE', topic: 'ROBOT', title: '周末机器人班', organizationId: ORG, activityId: ACTIVITY })
    expect(offline.activityId).toBe(ACTIVITY)
    expect(offline.mode).toBe('OFFLINE')
  })
})

describe('基本资料：表单 ↔ 请求体（金额元 ↔ 分）', () => {
  it('读进表单时分 → 元，原样存回去时元 → 分不走样', () => {
    const course = onlineCourse()
    const form = courseFormOf(course)
    expect(form.priceYuan).toBe('199.9')
    expect(form.includes).toEqual([{ name: '课程视频', quantity: '12' }])

    const request = courseRequestOf('ONLINE', form, course.version)
    expect(request.priceAmountMinor).toBe(19990)
    expect(request.includes).toEqual([{ name: '课程视频', quantity: 12 }])
    expect(request.version).toBe(4)
    expect(request.activityId).toBeNull()
    expect(request.scheduleLabel).toBeNull()
    expect(request.bundledProductIds).toEqual([PRODUCT])
    expect(request.experienceItemId).toBe(EXPERIENCE)
    expect(request.simulationItemId).toBe(SIMULATION)
  })

  it('价格留空 = 不单独出售（null）；0.01 元是 1 分；空白变 null，空行和空段落不提交', () => {
    const form: CourseForm = {
      ...courseFormOf(onlineCourse()),
      priceYuan: '  ',
      subtitle: '   ',
      experienceItemId: ' ',
      simulationItemId: '',
      includes: [{ name: ' 飞行手册 ', quantity: ' 2 ' }, { name: '', quantity: '' }],
      extraSpecs: [{ label: '', value: '  ' }],
      intro: ['  第一段 ', '', '   '],
      validityDays: null,
    }
    const request = courseRequestOf('ONLINE', form, 0)
    expect(request.priceAmountMinor).toBeNull()
    expect(request.subtitle).toBeNull()
    expect(request.experienceItemId).toBeNull()
    expect(request.simulationItemId).toBeNull()
    expect(request.includes).toEqual([{ name: '飞行手册', quantity: 2 }])
    expect(request.extraSpecs).toEqual([])
    expect(request.intro).toEqual(['第一段'])
    expect(request.validityDays).toBeNull()

    expect(courseRequestOf('ONLINE', { ...form, priceYuan: '0.01' }, 0).priceAmountMinor).toBe(1)
    expect(courseRequestOf('ONLINE', { ...form, priceYuan: '19.9' }, 0).priceAmountMinor).toBe(1990)
  })

  it('线下班只带标题、子类目、机构、封面、活动、卡片文字和排序，线上课专有的都清空', () => {
    const form: CourseForm = { ...courseFormOf(onlineCourse()), activityId: ACTIVITY, scheduleLabel: ' 8 次课 ' }
    const request = courseRequestOf('OFFLINE', form, 2)
    expect(request).toMatchObject({
      version: 2,
      title: '无人机航拍入门',
      organizationId: ORG,
      coverUrl: 'https://cdn.example.com/cover.jpg',
      activityId: ACTIVITY,
      scheduleLabel: '8 次课',
      sortOrder: 10,
      subtitle: null,
      galleryUrls: [],
      priceAmountMinor: null,
      validityDays: null,
      instructorName: null,
      includes: [],
      extraSpecs: [],
      intro: [],
      experienceItemId: null,
      simulationItemId: null,
      bundledProductIds: [],
    })
  })
})

describe('目录', () => {
  it('时长按“分:秒”或秒数填', () => {
    expect(parseDuration('12:30')).toBe(750)
    expect(parseDuration(' 750 ')).toBe(750)
    expect(parseDuration('0:05')).toBe(5)
    expect(parseDuration('12:3')).toBeNull()
    expect(parseDuration('12分')).toBeNull()
    expect(parseDuration('')).toBeNull()
    expect(formatDuration(750)).toBe('12:30')
    expect(formatDuration(65)).toBe('1:05')
  })

  it('原有的章和课时带 ID，新加的 ID 为 null；时长换成秒，空视频地址是 null', () => {
    const chapters = outlineFormOf(onlineCourse().chapters)
    const added = newLesson()
    added.title = ' 第一次起飞 '
    added.durationText = '8:00'
    added.videoUrl = 'https://cdn.example.com/3.mp4'
    chapters[0]!.lessons.push(added)
    const chapter = newChapter()
    chapter.title = '航拍构图'
    chapters.push(chapter)

    expect(outlineRequestOf(chapters, 4)).toEqual({
      version: 4,
      chapters: [
        {
          chapterId: 'f1000000-0000-4000-8000-000000000001',
          title: '起飞前',
          lessons: [
            { lessonId: 'f2000000-0000-4000-8000-000000000001', title: '认识遥控器', durationSeconds: 750, trial: true, videoUrl: 'https://cdn.example.com/1.mp4' },
            { lessonId: 'f2000000-0000-4000-8000-000000000002', title: '检查清单', durationSeconds: 65, trial: false, videoUrl: null },
            { lessonId: null, title: '第一次起飞', durationSeconds: 480, trial: false, videoUrl: 'https://cdn.example.com/3.mp4' },
          ],
        },
        { chapterId: null, title: '航拍构图', lessons: [] },
      ],
    })
  })

  it('保存前逐条挡住：章名、课时名、时长、视频地址', () => {
    const chapters = outlineFormOf(onlineCourse().chapters)
    expect(outlineProblems(chapters)).toEqual([])

    chapters[0]!.title = ' '
    chapters[0]!.lessons[0]!.durationText = '241:00'
    chapters[0]!.lessons[1]!.videoUrl = 'http://cdn.example.com/2.mp4'
    const lesson = newLesson()
    chapters[0]!.lessons.push(lesson)
    expect(outlineProblems(chapters)).toEqual([
      '第 1 章要填章名',
      '第 1 章第 1 节时长按“分:秒”（如 12:30）或秒数填，1 秒到 240 分钟',
      '第 1 章第 2 节视频地址只能是 https 地址',
      '第 1 章第 3 节要填课时名称',
      '第 1 章第 3 节时长按“分:秒”（如 12:30）或秒数填，1 秒到 240 分钟',
    ])
  })

  it('章数和课时数有上限', () => {
    const many = Array.from({ length: 21 }, () => ({ ...newChapter(), title: '章' }))
    expect(outlineProblems(many)[0]).toBe('最多 20 章')
    const big = { ...newChapter(), title: '章', lessons: Array.from({ length: 31 }, () => ({ ...newLesson(), title: '课', durationText: '60' })) }
    expect(outlineProblems([big])).toEqual(['第 1 章最多 30 节课时'])
  })
})

describe('接口请求形状', () => {
  it('列表、详情、新建、改资料、改目录、上架、下架、改动记录', async () => {
    const { http, calls } = fakeHttp()
    const api = createCourseApi(http)
    const course = onlineCourse()

    await api.list({ keyword: ' 航拍 ', mode: 'ONLINE', topic: '', status: 'LISTED' }, 2, 20)
    await api.detail(course.courseId)
    await api.create({ mode: 'OFFLINE', topic: 'ROBOT', title: '周末班', organizationId: ORG, activityId: ACTIVITY })
    await api.update(course, { ...courseFormOf(course), priceYuan: '299' })
    await api.saveOutline(course.courseId, outlineFormOf(course.chapters), course.version)
    await api.publish(course.courseId, 4)
    await api.unlist(course.courseId, 5)
    await api.changes(course.courseId)

    const one = `/api/v1/ops/courses/${course.courseId}`
    expect(calls[0]).toEqual({ method: 'GET', path: '/api/v1/ops/courses',
      query: { keyword: '航拍', mode: 'ONLINE', topic: '', status: 'LISTED', page: 2, size: 20 } })
    expect(calls[1]).toEqual({ method: 'GET', path: one, query: undefined })
    expect(calls[2]).toEqual({ method: 'POST', path: '/api/v1/ops/courses',
      body: { mode: 'OFFLINE', topic: 'ROBOT', title: '周末班', organizationId: ORG, activityId: ACTIVITY } })
    expect(calls[3]!.method).toBe('PUT')
    expect(calls[3]!.path).toBe(one)
    expect(calls[3]!.body).toMatchObject({ version: 4, priceAmountMinor: 29900, organizationId: ORG })
    expect(calls[4]!.method).toBe('PUT')
    expect(calls[4]!.path).toBe(`${one}/outline`)
    expect(calls[4]!.body).toMatchObject({ version: 4, chapters: [{ chapterId: 'f1000000-0000-4000-8000-000000000001' }] })
    expect(calls[5]).toEqual({ method: 'POST', path: `${one}/list`, body: { version: 4 } })
    expect(calls[6]).toEqual({ method: 'POST', path: `${one}/unlist`, body: { version: 5 } })
    expect(calls[7]).toEqual({ method: 'GET', path: `${one}/changes`, query: undefined })
  })
})

describe('表单规则', () => {
  it('价格可空；填了要是 0.01–100000 元、最多两位小数', async () => {
    const rules = rulesOf(courseRules('ONLINE'), 'priceYuan')
    expect(await firstError(rules, '')).toBeNull()
    expect(await firstError(rules, '199.9')).toBeNull()
    expect(await firstError(rules, '100000')).toBeNull()
    expect(await firstError(rules, '0')).toContain('0.01–100000 元')
    expect(await firstError(rules, '100000.01')).toContain('0.01–100000 元')
    expect(await firstError(rules, '1.234')).toContain('最多两位小数')
    expect(await firstError(rules, '-5')).toContain('0.01–100000 元')
  })

  it('试听入口要是 UUID；封面只收 https', async () => {
    const rules = courseRules('ONLINE')
    expect(await firstError(rulesOf(rules, 'experienceItemId'), '')).toBeNull()
    expect(await firstError(rulesOf(rules, 'experienceItemId'), EXPERIENCE)).toBeNull()
    expect(await firstError(rulesOf(rules, 'experienceItemId'), 'abc')).toContain('UUID')
    expect(await firstError(rulesOf(rules, 'coverUrl'), 'http://cdn.example.com/a.jpg')).toBe('封面只能是 https 地址')
  })

  it('课程包含：名称 ≤12 字、数量 1–999；参数补充：名称 ≤8 字、内容 ≤40 字，两样都要填', () => {
    expect(includeProblem([{ name: '课程视频', quantity: '12' }, { name: '', quantity: '' }])).toBeNull()
    expect(includeProblem([{ name: '', quantity: '1' }])).toBe('课程包含第 1 行要填名称')
    expect(includeProblem([{ name: '视频', quantity: '0' }])).toBe('课程包含第 1 行数量是 1–999 的整数')
    expect(includeProblem([{ name: '视频', quantity: '1000' }])).toBe('课程包含第 1 行数量是 1–999 的整数')
    expect(includeProblem([{ name: '一二三四五六七八九十一二三', quantity: '1' }])).toBe('课程包含第 1 行名称最多 12 个字')
    expect(includeProblem(Array.from({ length: 7 }, () => ({ name: '视频', quantity: '1' })))).toBe('课程包含最多 6 项')

    expect(specProblem([{ label: '上课方式', value: '线上' }])).toBeNull()
    expect(specProblem([{ label: '上课方式', value: '' }])).toBe('参数补充第 1 行名称和内容都要填')
    expect(specProblem([{ label: '一二三四五六七八九', value: '线上' }])).toBe('参数补充第 1 行名称最多 8 个字')
  })

  it('图集和附带商品不能重复', () => {
    const form = courseFormOf(onlineCourse())
    expect(courseProblems('ONLINE', form)).toEqual([])
    expect(courseProblems('ONLINE', { ...form, bundledProductIds: [PRODUCT, PRODUCT] })).toEqual(['附带商品里有重复的商品'])
    expect(courseProblems('OFFLINE', { ...form, bundledProductIds: [PRODUCT, PRODUCT] })).toEqual([])
  })

  it('新建线下班要选活动；线下班资料里活动必填，卡片文字 ≤12 字', async () => {
    const form: CourseCreateForm = { ...emptyCreateForm(), mode: 'OFFLINE' }
    const rules = createRules(() => form)
    expect(await firstError(rulesOf(rules, 'activityId'), '')).toBe('线下班要选挂靠的活动')
    expect(await firstError(rulesOf(rules, 'activityId'), ACTIVITY)).toBeNull()
    form.mode = 'ONLINE'
    expect(await firstError(rulesOf(rules, 'activityId'), '')).toBeNull()
    expect(await firstError(rulesOf(rules, 'organizationId'), '')).toBe('请填写开课机构')

    const offline = courseRules('OFFLINE')
    expect(await firstError(rulesOf(offline, 'activityId'), '')).toBe('请填写挂靠的活动')
    expect(await firstError(rulesOf(offline, 'scheduleLabel'), '一二三四五六七八九十一二三')).toBe('卡片文字最多 12 个字')
    expect(offline.priceYuan).toBeUndefined()
  })
})

describe('上架还差什么', () => {
  it('listMissing 翻成中文，没登记的键原样显示', () => {
    expect(listMissingText(['coverUrl', 'lessons', 'videos', 'sale', 'merchantAccount', 'scheduleLabel', 'activity']))
      .toBe('封面、至少一节课时、每节课时都有视频、单独出售或附带于商品、开课机构登记在线收款账户、卡片文字、挂靠的活动')
    expect(listMissingText(['coverUrl', 'somethingNew'])).toBe('封面、somethingNew')
  })

  it('422 OPS_PUBLISH_INCOMPLETE 读 details.missing；别的错误不管', () => {
    const incomplete = new ApiRequestError(422, 'OPS_PUBLISH_INCOMPLETE', 'incomplete', 't-1', { missing: ['videos', 'merchantAccount'] })
    expect(describeListMissing(incomplete)).toBe('上架前还差：每节课时都有视频、开课机构登记在线收款账户')
    const conflict = new ApiRequestError(409, 'OPS_VERSION_CONFLICT', 'conflict', 't-2')
    expect(describeListMissing(conflict)).toBeNull()
  })

  it('课程的错误码有中文说明', () => {
    for (const code of ['OPS_COURSE_STATUS_CONFLICT', 'OPS_COURSE_ORGANIZATION_UNKNOWN', 'OPS_COURSE_EXPERIENCE_TAKEN', 'OPS_COURSE_LESSON_IN_USE']) {
      const message = errorMessage(new ApiRequestError(409, code, 'server text', null))
      expect(message, code).not.toBe('server text')
    }
    expect(errorMessage(new ApiRequestError(409, 'OPS_COURSE_LESSON_IN_USE', '', null))).toContain('已经有人学过')
  })
})
