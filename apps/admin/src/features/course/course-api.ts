import type { ApiClient } from '@/core/http/api-client'
import { missingFields, type OpsChange } from '@/core/ops/ops-common'
import { minorToYuan, yuanToMinor } from '@/core/ui/money'
import type { Page } from '@/core/ui/use-paged-list'

// 契约 contracts/openapi-ops-course.yaml 1.0.0：课程（线上录播课、线下班）只给平台运营。

export type CourseMode = 'ONLINE' | 'OFFLINE'
export type CourseTopic = 'DRONE' | 'ROBOT' | 'PROGRAMMING' | 'CERTIFICATE'
export type CourseStatus = 'DRAFT' | 'LISTED' | 'UNLISTED'
export type CourseListField = 'coverUrl' | 'lessons' | 'videos' | 'sale' | 'merchantAccount' | 'scheduleLabel' | 'activity'

export const MODE_OPTIONS: ReadonlyArray<{ value: CourseMode, label: string, hint: string }> = [
  { value: 'ONLINE', label: '线上课', hint: '录播课：有章节和课时视频，可以单独出售，也可以随商城商品附带' },
  { value: 'OFFLINE', label: '线下班', hint: '挂靠一个赛事研学活动：App 上标题、封面、价格、城市取自活动，报名走活动' },
]

export const TOPIC_OPTIONS: ReadonlyArray<{ value: CourseTopic, label: string }> = [
  { value: 'DRONE', label: '无人机' },
  { value: 'ROBOT', label: '机器人' },
  { value: 'PROGRAMMING', label: '编程' },
  { value: 'CERTIFICATE', label: '考证' },
]

export const STATUS_OPTIONS: ReadonlyArray<{ value: CourseStatus, label: string, tag: 'info' | 'success' | 'warning' }> = [
  { value: 'DRAFT', label: '草稿', tag: 'info' },
  { value: 'LISTED', label: '已上架', tag: 'success' },
  { value: 'UNLISTED', label: '已下架', tag: 'warning' },
]

/** 上架必填项的中文（详情的 listMissing 和 422 OPS_PUBLISH_INCOMPLETE 的 details.missing）。 */
export const LIST_FIELD_LABELS: Readonly<Record<CourseListField, string>> = {
  coverUrl: '封面',
  lessons: '至少一节课时',
  videos: '每节课时都有视频',
  sale: '单独出售或附带于商品',
  merchantAccount: '开课机构登记在线收款账户',
  scheduleLabel: '卡片文字',
  activity: '挂靠的活动',
}

export const CHANGE_SUBJECT_LABELS: Readonly<Record<string, string>> = { COURSE: '课程' }
export const CHANGE_ACTION_LABELS: Readonly<Record<string, string>> = {
  CREATE: '新建',
  UPDATE: '修改资料',
  OUTLINE: '修改目录',
  LIST: '上架',
  UNLIST: '下架',
}

export const LIMITS = {
  title: 60,
  subtitle: 120,
  gallery: 9,
  /** 单独出售的价格：1 分到 10 万元。 */
  priceMinor: 10_000_000,
  validityDays: 3650,
  ageNote: 30,
  instructorName: 30,
  instructorTitle: 40,
  includes: 6,
  includeName: 12,
  includeQuantity: 999,
  specs: 6,
  specLabel: 8,
  specValue: 40,
  intro: 20,
  introParagraph: 500,
  bundledProducts: 10,
  scheduleLabel: 12,
  sortOrder: 9999,
  chapters: 20,
  chapterTitle: 40,
  lessonsPerChapter: 30,
  lessonsTotal: 200,
  lessonTitle: 40,
  lessonSeconds: 14400,
} as const

export function labelOf<T extends string>(options: ReadonlyArray<{ value: T, label: string }>, value: T): string {
  return options.find((option) => option.value === value)?.label ?? value
}

export function statusTag(status: CourseStatus): 'info' | 'success' | 'warning' {
  return STATUS_OPTIONS.find((option) => option.value === status)?.tag ?? 'info'
}

/** 缺的字段键 → “封面、每节课时都有视频”；没登记的键原样显示，免得漏看。 */
export function listMissingText(keys: readonly string[]): string {
  return keys.map((key) => LIST_FIELD_LABELS[key as CourseListField] ?? key).join('、')
}

/** 后端回 422 OPS_PUBLISH_INCOMPLETE 时，把 details.missing 翻成一句话；别的错误返回 null。 */
export function describeListMissing(error: unknown, prefix = '上架前还差'): string | null {
  const keys = missingFields(error)
  return keys.length === 0 ? null : `${prefix}：${listMissingText(keys)}`
}

// ---- 接口形状 ----

export interface OpsCourseLesson {
  lessonId: string
  no: number
  title: string
  durationSeconds: number
  trial: boolean
  videoUrl: string | null
  learnerCount: number
}

export interface OpsCourseChapter {
  chapterId: string
  title: string
  lessons: OpsCourseLesson[]
}

export interface OpsCourse {
  courseId: string
  mode: CourseMode
  topic: CourseTopic
  status: CourseStatus
  title: string
  subtitle: string | null
  organization: { organizationId: string, name: string }
  coverUrl: string | null
  galleryUrls: string[]
  priceAmountMinor: number | null
  validityDays: number | null
  ageNote: string | null
  instructorName: string | null
  instructorTitle: string | null
  includes: Array<{ name: string, quantity: number }>
  extraSpecs: Array<{ label: string, value: string }>
  intro: string[]
  experienceItemId: string | null
  simulationItemId: string | null
  bundledProductIds: string[]
  activityId: string | null
  scheduleLabel: string | null
  sortOrder: number
  chapters: OpsCourseChapter[]
  lessonCount: number
  enrollmentCount: number
  listMissing: CourseListField[]
  firstListedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface OpsCourseSummary {
  courseId: string
  mode: CourseMode
  topic: CourseTopic
  status: CourseStatus
  title: string
  organizationName: string
  priceAmountMinor: number | null
  lessonCount: number
  activityId: string | null
  sortOrder: number
  updatedAt: string
}

export interface CourseFilters {
  keyword: string
  mode: CourseMode | ''
  topic: CourseTopic | ''
  status: CourseStatus | ''
}

export interface CourseCreateForm {
  mode: CourseMode
  topic: CourseTopic
  title: string
  organizationId: string
  activityId: string
}

export interface CourseCreateRequest {
  mode: CourseMode
  topic: CourseTopic
  title: string
  organizationId: string
  activityId: string | null
}

/** 基本资料表单：文字都是字符串，价格按“元”填（空 = 不单独出售），数量按字符串填。 */
export interface CourseForm {
  topic: CourseTopic
  title: string
  subtitle: string
  organizationId: string
  coverUrl: string
  galleryUrls: string[]
  priceYuan: string
  /** null = 长期有效。 */
  validityDays: number | null
  ageNote: string
  instructorName: string
  instructorTitle: string
  includes: Array<{ name: string, quantity: string }>
  extraSpecs: Array<{ label: string, value: string }>
  intro: string[]
  experienceItemId: string
  simulationItemId: string
  bundledProductIds: string[]
  activityId: string
  scheduleLabel: string
  sortOrder: number
}

export interface CourseRequest {
  version: number
  topic: CourseTopic
  title: string
  subtitle: string | null
  organizationId: string
  coverUrl: string | null
  galleryUrls: string[]
  priceAmountMinor: number | null
  validityDays: number | null
  ageNote: string | null
  instructorName: string | null
  instructorTitle: string | null
  includes: Array<{ name: string, quantity: number }>
  extraSpecs: Array<{ label: string, value: string }>
  intro: string[]
  experienceItemId: string | null
  simulationItemId: string | null
  bundledProductIds: string[]
  activityId: string | null
  scheduleLabel: string | null
  sortOrder: number
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

export function emptyCreateForm(): CourseCreateForm {
  return { mode: 'ONLINE', topic: 'DRONE', title: '', organizationId: '', activityId: '' }
}

/** 新建：线上课不带活动，线下班必须带。 */
export function createRequestOf(form: CourseCreateForm): CourseCreateRequest {
  return {
    mode: form.mode,
    topic: form.topic,
    title: form.title.trim(),
    organizationId: form.organizationId,
    activityId: form.mode === 'OFFLINE' ? blankToNull(form.activityId) : null,
  }
}

export function courseFormOf(course: OpsCourse): CourseForm {
  return {
    topic: course.topic,
    title: course.title,
    subtitle: course.subtitle ?? '',
    organizationId: course.organization.organizationId,
    coverUrl: course.coverUrl ?? '',
    galleryUrls: [...course.galleryUrls],
    priceYuan: course.priceAmountMinor === null ? '' : String(minorToYuan(course.priceAmountMinor)),
    validityDays: course.validityDays,
    ageNote: course.ageNote ?? '',
    instructorName: course.instructorName ?? '',
    instructorTitle: course.instructorTitle ?? '',
    includes: course.includes.map((item) => ({ name: item.name, quantity: String(item.quantity) })),
    extraSpecs: course.extraSpecs.map((item) => ({ label: item.label, value: item.value })),
    intro: [...course.intro],
    experienceItemId: course.experienceItemId ?? '',
    simulationItemId: course.simulationItemId ?? '',
    bundledProductIds: [...course.bundledProductIds],
    activityId: course.activityId ?? '',
    scheduleLabel: course.scheduleLabel ?? '',
    sortOrder: course.sortOrder,
  }
}

function filledRows<T extends Record<string, string>>(rows: readonly T[]): T[] {
  return rows.filter((row) => Object.values(row).some((value) => value.trim() !== ''))
}

/**
 * 表单 → 整份保存的请求体：空白变 null，价格元 → 分（空 = 不单独出售）。
 * 线下班只带标题、子类目、开课机构、封面、活动、卡片文字和排序，线上课专有的一律清空（契约：线下班填了 400）；
 * 线上课不带活动和卡片文字。
 */
export function courseRequestOf(mode: CourseMode, form: CourseForm, version: number): CourseRequest {
  const common = {
    version,
    topic: form.topic,
    title: form.title.trim(),
    organizationId: form.organizationId,
    coverUrl: blankToNull(form.coverUrl),
    sortOrder: form.sortOrder,
  }
  if (mode === 'OFFLINE') {
    return {
      ...common,
      subtitle: null,
      galleryUrls: [],
      priceAmountMinor: null,
      validityDays: null,
      ageNote: null,
      instructorName: null,
      instructorTitle: null,
      includes: [],
      extraSpecs: [],
      intro: [],
      experienceItemId: null,
      simulationItemId: null,
      bundledProductIds: [],
      activityId: blankToNull(form.activityId),
      scheduleLabel: blankToNull(form.scheduleLabel),
    }
  }
  return {
    ...common,
    subtitle: blankToNull(form.subtitle),
    galleryUrls: form.galleryUrls.map((url) => url.trim()).filter((url) => url !== ''),
    priceAmountMinor: form.priceYuan.trim() === '' ? null : yuanToMinor(form.priceYuan),
    validityDays: form.validityDays,
    ageNote: blankToNull(form.ageNote),
    instructorName: blankToNull(form.instructorName),
    instructorTitle: blankToNull(form.instructorTitle),
    includes: filledRows(form.includes).map((row) => ({ name: row.name.trim(), quantity: Number(row.quantity.trim()) })),
    extraSpecs: filledRows(form.extraSpecs).map((row) => ({ label: row.label.trim(), value: row.value.trim() })),
    intro: form.intro.map((paragraph) => paragraph.trim()).filter((paragraph) => paragraph !== ''),
    experienceItemId: blankToNull(form.experienceItemId),
    simulationItemId: blankToNull(form.simulationItemId),
    bundledProductIds: [...form.bundledProductIds],
    activityId: null,
    scheduleLabel: null,
  }
}

// ---- 目录（只有线上课） ----

/** 课时时长：按“分:秒”（比如 12:30）或秒数（比如 750）填。 */
export function parseDuration(text: string): number | null {
  const value = text.trim()
  if (/^\d{1,5}$/.test(value)) return Number(value)
  const match = /^(\d{1,3}):([0-5]\d)$/.exec(value)
  if (!match) return null
  return Number(match[1]) * 60 + Number(match[2])
}

export function formatDuration(seconds: number): string {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

let localKey = 0
/** 页面里每一行的本地 key（v-for 用；新建的章和课时还没有 ID）。 */
function nextKey(): string {
  localKey += 1
  return `k${localKey}`
}

export interface LessonForm {
  key: string
  /** 原有课时的 ID；新加的为 null。 */
  lessonId: string | null
  title: string
  durationText: string
  trial: boolean
  videoUrl: string
  originalVideoUrl?: string | null
  /** 学过的人数（原有课时才有；大于 0 的不能删）。 */
  learnerCount: number
}

export interface ChapterForm {
  key: string
  chapterId: string | null
  title: string
  lessons: LessonForm[]
}

export interface OutlineRequest {
  version: number
  chapters: Array<{
    chapterId: string | null
    title: string
    lessons: Array<{ lessonId: string | null, title: string, durationSeconds: number, trial: boolean, videoUrl: string | null }>
  }>
}

export function outlineFormOf(chapters: readonly OpsCourseChapter[]): ChapterForm[] {
  return chapters.map((chapter) => ({
    key: nextKey(),
    chapterId: chapter.chapterId,
    title: chapter.title,
    lessons: chapter.lessons.map((lesson) => ({
      key: nextKey(),
      lessonId: lesson.lessonId,
      title: lesson.title,
      durationText: formatDuration(lesson.durationSeconds),
      trial: lesson.trial,
      videoUrl: lesson.videoUrl ?? '',
    originalVideoUrl: lesson.videoUrl,
      learnerCount: lesson.learnerCount,
    })),
  }))
}

export function newChapter(): ChapterForm {
  return { key: nextKey(), chapterId: null, title: '', lessons: [] }
}

export function newLesson(): LessonForm {
  return { key: nextKey(), lessonId: null, title: '', durationText: '', trial: false, videoUrl: '', learnerCount: 0 }
}

/** 有人学过的课时不能删；含这种课时的章也不能删。 */
export function lessonLocked(lesson: LessonForm): boolean {
  return lesson.learnerCount > 0
}

export function chapterLocked(chapter: ChapterForm): boolean {
  return chapter.lessons.some(lessonLocked)
}

/** 目录表单 → 整份保存的请求体：带 chapterId / lessonId 的是改原来的，null 的是新建；时长换成秒。 */
export function outlineRequestOf(chapters: readonly ChapterForm[], version: number): OutlineRequest {
  return {
    version,
    chapters: chapters.map((chapter) => ({
      chapterId: chapter.chapterId,
      title: chapter.title.trim(),
      lessons: chapter.lessons.map((lesson) => {
        const seconds = parseDuration(lesson.durationText)
        if (seconds === null) throw new Error(`课时“${lesson.title.trim()}”的时长格式不对`)
        return {
          lessonId: lesson.lessonId,
          title: lesson.title.trim(),
          durationSeconds: seconds,
          trial: lesson.trial,
          videoUrl: blankToNull(lesson.videoUrl),
        }
      }),
    })),
  }
}

// ---- 接口 ----

export function createCourseApi(http: ApiClient) {
  const base = '/api/v1/ops/courses'
  const one = (id: string) => `${base}/${encodeURIComponent(id)}`
  return {
    list(filters: CourseFilters, page: number, size: number): Promise<Page<OpsCourseSummary>> {
      return http.get<Page<OpsCourseSummary>>(base, {
        keyword: filters.keyword.trim(),
        mode: filters.mode,
        topic: filters.topic,
        status: filters.status,
        page,
        size,
      })
    },
    detail(id: string): Promise<OpsCourse> {
      return http.get<OpsCourse>(one(id))
    },
    create(form: CourseCreateForm): Promise<OpsCourse> {
      return http.post<OpsCourse>(base, createRequestOf(form))
    },
    update(course: Pick<OpsCourse, 'courseId' | 'mode' | 'version'>, form: CourseForm): Promise<OpsCourse> {
      return http.put<OpsCourse>(one(course.courseId), courseRequestOf(course.mode, form, course.version))
    },
    saveOutline(id: string, chapters: readonly ChapterForm[], version: number): Promise<OpsCourse> {
      return http.put<OpsCourse>(`${one(id)}/outline`, outlineRequestOf(chapters, version))
    },
    /** 上架（POST …/list）：草稿或已下架 → 已上架。 */
    publish(id: string, version: number): Promise<OpsCourse> {
      return http.post<OpsCourse>(`${one(id)}/list`, { version })
    },
    /** 下架（POST …/unlist）：已上架 → 已下架。 */
    unlist(id: string, version: number): Promise<OpsCourse> {
      return http.post<OpsCourse>(`${one(id)}/unlist`, { version })
    },
    changes(id: string): Promise<OpsChange[]> {
      return http.get<OpsChange[]>(`${one(id)}/changes`)
    },
  }
}

export type CourseApi = ReturnType<typeof createCourseApi>
