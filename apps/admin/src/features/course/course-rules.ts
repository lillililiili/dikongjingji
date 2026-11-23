import type { FormItemRule, FormRules } from 'element-plus'

import { isHttpsUrl } from '@/core/ops/media-api'
import { charCount } from '@/core/ui/format'
import { maxChars, required } from '@/core/ui/form-rules'
import { yuanToMinor } from '@/core/ui/money'

import { LIMITS, parseDuration, type ChapterForm, type CourseCreateForm, type CourseForm, type CourseMode } from './course-api'

/** 和后端课程运营接口的校验一致（openapi-ops-course.yaml）；后端还会再查一次。 */

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function isUuid(value: string): boolean {
  return UUID.test(value.trim())
}

function httpsOrEmpty(label: string): FormItemRule {
  return {
    trigger: 'change',
    validator: (_rule, value: unknown, callback) => {
      const text = typeof value === 'string' ? value.trim() : ''
      callback(text !== '' && !isHttpsUrl(text) ? new Error(`${label}只能是 https 地址`) : undefined)
    },
  }
}

/** 价格可空（不单独出售）；填了要是 0.01–100000 元、最多两位小数。 */
export const priceRule: FormItemRule = {
  trigger: 'blur',
  validator: (_rule, value: unknown, callback) => {
    const text = typeof value === 'string' ? value.trim() : ''
    if (text === '') {
      callback()
      return
    }
    const minor = yuanToMinor(text)
    callback(minor === null || minor < 1 || minor > LIMITS.priceMinor
      ? new Error('价格是 0.01–100000 元，最多两位小数；不单独出售就留空')
      : undefined)
  },
}

const experienceRule: FormItemRule = {
  trigger: 'blur',
  validator: (_rule, value: unknown, callback) => {
    const text = typeof value === 'string' ? value.trim() : ''
    callback(text !== '' && !isUuid(text) ? new Error('试听入口要填体验项目的 ID（UUID），不用就留空') : undefined)
  },
}

const includesRule: FormItemRule = {
  trigger: 'change',
  validator: (_rule, value: unknown, callback) => {
    const rows = Array.isArray(value) ? value as Array<{ name: string, quantity: string }> : []
    callback(includeProblem(rows) ?? undefined)
  },
}

const specsRule: FormItemRule = {
  trigger: 'change',
  validator: (_rule, value: unknown, callback) => {
    const rows = Array.isArray(value) ? value as Array<{ label: string, value: string }> : []
    callback(specProblem(rows) ?? undefined)
  },
}

const introRule: FormItemRule = {
  trigger: 'change',
  validator: (_rule, value: unknown, callback) => {
    const paragraphs = Array.isArray(value) ? value as string[] : []
    if (paragraphs.length > LIMITS.intro) {
      callback(new Error(`图文介绍最多 ${LIMITS.intro} 段`))
      return
    }
    const long = paragraphs.findIndex((paragraph) => charCount(paragraph.trim()) > LIMITS.introParagraph)
    callback(long >= 0 ? new Error(`第 ${long + 1} 段超过 ${LIMITS.introParagraph} 字`) : undefined)
  },
}

function maxItems(label: string, max: number): FormItemRule {
  return {
    trigger: 'change',
    validator: (_rule, value: unknown, callback) => {
      callback(Array.isArray(value) && value.length > max ? new Error(`${label}最多 ${max} 个`) : undefined)
    },
  }
}

/** 课程包含：整行空着的不提交；名称 1–12 字，数量 1–999 的整数；最多 6 行。 */
export function includeProblem(rows: ReadonlyArray<{ name: string, quantity: string }>): string | null {
  if (rows.length > LIMITS.includes) return `课程包含最多 ${LIMITS.includes} 项`
  for (const [index, row] of rows.entries()) {
    const name = row.name.trim()
    const quantity = row.quantity.trim()
    if (name === '' && quantity === '') continue
    if (name === '') return `课程包含第 ${index + 1} 行要填名称`
    if (charCount(name) > LIMITS.includeName) return `课程包含第 ${index + 1} 行名称最多 ${LIMITS.includeName} 个字`
    if (!/^\d{1,3}$/.test(quantity) || Number(quantity) < 1) return `课程包含第 ${index + 1} 行数量是 1–${LIMITS.includeQuantity} 的整数`
  }
  return null
}

/** 参数补充行：整行空着的不提交；名称 1–8 字，内容 1–40 字；最多 6 行。 */
export function specProblem(rows: ReadonlyArray<{ label: string, value: string }>): string | null {
  if (rows.length > LIMITS.specs) return `参数补充最多 ${LIMITS.specs} 行`
  for (const [index, row] of rows.entries()) {
    const label = row.label.trim()
    const value = row.value.trim()
    if (label === '' && value === '') continue
    if (label === '' || value === '') return `参数补充第 ${index + 1} 行名称和内容都要填`
    if (charCount(label) > LIMITS.specLabel) return `参数补充第 ${index + 1} 行名称最多 ${LIMITS.specLabel} 个字`
    if (charCount(value) > LIMITS.specValue) return `参数补充第 ${index + 1} 行内容最多 ${LIMITS.specValue} 个字`
  }
  return null
}

export function createRules(form: () => CourseCreateForm): FormRules {
  return {
    mode: [required('上课方式', 'change')],
    topic: [required('子类目', 'change')],
    title: [required('课程名称'), maxChars('课程名称', LIMITS.title)],
    organizationId: [required('开课机构', 'change')],
    activityId: [{
      trigger: 'change',
      validator: (_rule, value: unknown, callback) => {
        const empty = typeof value !== 'string' || value.trim() === ''
        callback(form().mode === 'OFFLINE' && empty ? new Error('线下班要选挂靠的活动') : undefined)
      },
    }],
  }
}

export function courseRules(mode: CourseMode): FormRules {
  const common: FormRules = {
    topic: [required('子类目', 'change')],
    title: [required('课程名称'), maxChars('课程名称', LIMITS.title)],
    organizationId: [required('开课机构', 'change')],
    coverUrl: [httpsOrEmpty('封面')],
  }
  if (mode === 'OFFLINE') {
    return {
      ...common,
      activityId: [required('挂靠的活动', 'change')],
      scheduleLabel: [maxChars('卡片文字', LIMITS.scheduleLabel)],
    }
  }
  return {
    ...common,
    subtitle: [maxChars('副标题', LIMITS.subtitle)],
    galleryUrls: [maxItems('图集', LIMITS.gallery)],
    priceYuan: [priceRule],
    ageNote: [maxChars('适合年龄', LIMITS.ageNote)],
    instructorName: [maxChars('讲师姓名', LIMITS.instructorName)],
    instructorTitle: [maxChars('讲师头衔', LIMITS.instructorTitle)],
    includes: [includesRule],
    extraSpecs: [specsRule],
    intro: [introRule],
    experienceItemId: [experienceRule],
    bundledProductIds: [maxItems('附带商品', LIMITS.bundledProducts)],
  }
}

/** 线上课基本资料里的整体问题（规则之外再挡一次，保存前用）。 */
export function courseProblems(mode: CourseMode, form: CourseForm): string[] {
  if (mode === 'OFFLINE') return []
  const problems: string[] = []
  const include = includeProblem(form.includes)
  if (include) problems.push(include)
  const spec = specProblem(form.extraSpecs)
  if (spec) problems.push(spec)
  if (new Set(form.galleryUrls).size !== form.galleryUrls.length) problems.push('图集里有重复的图片')
  if (new Set(form.bundledProductIds).size !== form.bundledProductIds.length) problems.push('附带商品里有重复的商品')
  return problems
}

/**
 * 目录整体检查（保存前）：章 ≤20、每章课时 ≤30、全课 ≤200；章名、课时名必填 ≤40 字；时长 1 秒到 4 小时；
 * 视频地址只收 https。返回每一条问题，没问题是空数组。
 */
export function outlineProblems(chapters: readonly ChapterForm[]): string[] {
  const problems: string[] = []
  if (chapters.length > LIMITS.chapters) problems.push(`最多 ${LIMITS.chapters} 章`)
  const total = chapters.reduce((sum, chapter) => sum + chapter.lessons.length, 0)
  if (total > LIMITS.lessonsTotal) problems.push(`全课最多 ${LIMITS.lessonsTotal} 节课时，现在 ${total} 节`)
  chapters.forEach((chapter, chapterIndex) => {
    const where = `第 ${chapterIndex + 1} 章`
    const title = chapter.title.trim()
    if (title === '') problems.push(`${where}要填章名`)
    else if (charCount(title) > LIMITS.chapterTitle) problems.push(`${where}章名最多 ${LIMITS.chapterTitle} 个字`)
    if (chapter.lessons.length > LIMITS.lessonsPerChapter) problems.push(`${where}最多 ${LIMITS.lessonsPerChapter} 节课时`)
    chapter.lessons.forEach((lesson, lessonIndex) => {
      const at = `${where}第 ${lessonIndex + 1} 节`
      const lessonTitle = lesson.title.trim()
      if (lessonTitle === '') problems.push(`${at}要填课时名称`)
      else if (charCount(lessonTitle) > LIMITS.lessonTitle) problems.push(`${at}名称最多 ${LIMITS.lessonTitle} 个字`)
      const seconds = parseDuration(lesson.durationText)
      if (seconds === null || seconds < 1 || seconds > LIMITS.lessonSeconds) {
        problems.push(`${at}时长按“分:秒”（如 12:30）或秒数填，1 秒到 240 分钟`)
      }
      const video = lesson.videoUrl.trim()
      if (video !== '' && !isHttpsUrl(video)) problems.push(`${at}视频地址只能是 https 地址`)
    })
  })
  return problems
}
