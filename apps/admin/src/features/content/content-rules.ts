import { charCount } from '@/core/ui/format'
import { isRetainedMediaUrl } from '@/core/ops/media-api'

import type {
  ArticleKind,
  ArticleRequest,
  AuthorKind,
  BlockRequest,
  BlockType,
  OpsArticle,
  OpsBlock,
  OpsContentAuthor,
  OpsRef,
  OpsTopic,
  OpsVideo,
  RefType,
  TopicRequest,
  VideoOrientation,
  VideoRequest,
} from './content-api'

// 运营端“发现内容”的表单、中文、校验和请求体拼装。限制和后端 ContentOpsService 一致（后端会再校验一次）。

export const LIMITS = {
  title: 60,
  topicTitle: 40,
  subtitle: 60,
  authorName: 160,
  blocks: 40,
  blockItems: 20,
  blockText: 500,
  heading: 60,
  itemTitle: 60,
  itemText: 200,
  imageAlt: 60,
  imageCaption: 60,
  refs: 10,
  refTitle: 60,
  videoSeconds: 3600,
  videoCaption: 200,
  tags: 5,
  tag: 20,
  rank: 9999,
  topicActivities: 20,
  topicVenues: 10,
  topicArticles: 20,
  topicMerchantItems: 6,
} as const

export const ARTICLE_KIND_LABELS: Record<ArticleKind, string> = { CASE: '案例', KNOWLEDGE: '知识' }
export const CONTENT_KIND_LABELS: Record<ArticleKind | 'VIDEO', string> = { ...ARTICLE_KIND_LABELS, VIDEO: '视频' }
export const AUTHOR_KIND_LABELS: Record<AuthorKind, string> = { PROVIDER: '服务商', MERCHANT: '商家', PLATFORM: '平台' }
export const ORIENTATION_LABELS: Record<VideoOrientation, string> = { PORTRAIT: '竖屏', LANDSCAPE: '横屏' }

export const BLOCK_TYPE_LABELS: Record<BlockType, string> = {
  PARAGRAPH: '段落',
  HEADING: '小标题',
  FACTS: '要点表',
  STEPS: '步骤',
  LIST: '条目',
  TIP: '提示',
  NOTICE: '文末说明',
  IMAGE: '正文配图',
}

export const BLOCK_TYPES: readonly BlockType[] = ['PARAGRAPH', 'HEADING', 'FACTS', 'STEPS', 'LIST', 'TIP', 'NOTICE', 'IMAGE']

export const REF_TYPE_LABELS: Record<RefType, string> = {
  INSPECTION_SERVICE: '巡检服务',
  PRO_SERVICE: '专业服务',
  PRODUCT: '商品',
  ACTIVITY: '活动',
  VENUE: '场馆',
}

/** 上线还差的字段键（契约 OpsContentMissingField）。 */
export const MISSING_LABELS: Readonly<Record<string, string>> = {
  author: '作者机构已停用，请换作者',
  publisher: '发布方机构已停用，请换发布方',
  coverUrl: '封面',
  blocks: '至少一块正文',
  imageUrl: '配图块的图片',
  playUrl: '播放地址',
  items: '至少挂一个活动、场馆、图文或商家内容',
}

export const SUBJECT_LABELS: Readonly<Record<string, string>> = { ARTICLE: '图文', VIDEO: '视频', TOPIC: '专题' }

export function hasText(type: BlockType): boolean {
  return type === 'PARAGRAPH' || type === 'HEADING' || type === 'TIP' || type === 'NOTICE'
}

export function hasItems(type: BlockType): boolean {
  return type === 'FACTS' || type === 'STEPS' || type === 'LIST'
}

// ---- 表单 ----

let keys = 0
export function newKey(): string {
  keys += 1
  return `k${keys}`
}

export interface AuthorForm {
  kind: AuthorKind
  organizationId: string
  // 机构的名称（选机构时带回来，只用来显示）。
  organizationName: string
  // 平台署名。
  name: string
}

export interface BlockItemForm {
  key: string
  title: string
  text: string
}

export interface BlockForm {
  key: string
  type: BlockType
  text: string
  items: BlockItemForm[]
  imageUrl: string
  originalImageUrl?: string | null
  imageAlt: string
  imageCaption: string
}

export interface RefForm {
  key: string
  type: RefType
  refId: string
  title: string
}

export interface ArticleForm {
  kind: ArticleKind
  title: string
  author: AuthorForm
  cityCode: string
  coverUrl: string
  sample: boolean
  blocks: BlockForm[]
  refs: RefForm[]
}

export interface VideoForm {
  title: string
  author: AuthorForm
  cityCode: string
  coverUrl: string
  sample: boolean
  durationSeconds: number
  orientation: VideoOrientation
  playUrl: string
  caption: string
  tagsText: string
  rank: number
  refs: RefForm[]
}

export interface MerchantItemForm {
  key: string
  productId: string
  merchantOrganizationId: string
  merchantName: string
}

export interface TopicForm {
  title: string
  subtitle: string
  publisher: AuthorForm
  cityCode: string
  startsOn: string
  endsOn: string
  coverUrl: string
  sample: boolean
  // 一行一个 ID。
  activityIdsText: string
  venueIdsText: string
  articleIdsText: string
  merchantItems: MerchantItemForm[]
}

function authorForm(author?: OpsContentAuthor, fallback: AuthorKind = 'PROVIDER'): AuthorForm {
  if (!author) return { kind: fallback, organizationId: '', organizationName: '', name: '' }
  return author.kind === 'PLATFORM'
    ? { kind: 'PLATFORM', organizationId: '', organizationName: '', name: author.name }
    : { kind: author.kind, organizationId: author.organizationId ?? '', organizationName: author.name, name: '' }
}

export function emptyBlock(type: BlockType): BlockForm {
  return {
    key: newKey(),
    type,
    text: '',
    items: hasItems(type) ? [{ key: newKey(), title: '', text: '' }] : [],
    imageUrl: '',
    imageAlt: '',
    imageCaption: '',
  }
}

export function blockForm(block: OpsBlock): BlockForm {
  return {
    key: newKey(),
    type: block.type,
    text: block.text ?? '',
    items: block.items.map((item) => ({ key: newKey(), title: item.title, text: item.text ?? '' })),
    imageUrl: block.image?.url ?? '',
    originalImageUrl: block.image?.url,
    imageAlt: block.image?.alt ?? '',
    imageCaption: block.image?.caption ?? '',
  }
}

function refForms(refs: OpsRef[]): RefForm[] {
  return refs.map((ref) => ({ key: newKey(), type: ref.type, refId: ref.refId, title: ref.title }))
}

export function articleForm(article?: OpsArticle | null): ArticleForm {
  return {
    kind: article?.kind ?? 'CASE',
    title: article?.title ?? '',
    author: authorForm(article?.author),
    cityCode: article?.cityCode ?? '',
    coverUrl: article?.coverUrl ?? '',
    sample: article?.sample ?? false,
    blocks: article ? article.blocks.map(blockForm) : [emptyBlock('PARAGRAPH')],
    refs: refForms(article?.refs ?? []),
  }
}

export function videoForm(video?: OpsVideo | null): VideoForm {
  return {
    title: video?.title ?? '',
    author: authorForm(video?.author),
    cityCode: video?.cityCode ?? '',
    coverUrl: video?.coverUrl ?? '',
    sample: video?.sample ?? false,
    durationSeconds: video?.durationSeconds ?? 60,
    orientation: video?.orientation ?? 'PORTRAIT',
    playUrl: video?.playUrl ?? '',
    caption: video?.caption ?? '',
    tagsText: (video?.tags ?? []).join(' '),
    rank: video?.rank ?? 0,
    refs: refForms(video?.refs ?? []),
  }
}

export function topicForm(topic?: OpsTopic | null): TopicForm {
  return {
    title: topic?.title ?? '',
    subtitle: topic?.subtitle ?? '',
    publisher: authorForm(topic?.publisher, 'PLATFORM'),
    cityCode: topic?.cityCode ?? '',
    startsOn: topic?.startsOn ?? '',
    endsOn: topic?.endsOn ?? '',
    coverUrl: topic?.coverUrl ?? '',
    sample: topic?.sample ?? false,
    activityIdsText: (topic?.activityIds ?? []).join('\n'),
    venueIdsText: (topic?.venueIds ?? []).join('\n'),
    articleIdsText: (topic?.articleIds ?? []).join('\n'),
    merchantItems: (topic?.merchantItems ?? []).map((item) => ({
      key: newKey(),
      productId: item.productId,
      merchantOrganizationId: item.merchant.organizationId ?? '',
      merchantName: item.merchant.name,
    })),
  }
}

// ---- 校验（提交前在页面上挡一次，后端再校验） ----

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function isUuid(value: string): boolean {
  return UUID.test(value.trim())
}

/** 一行一个 ID（也认逗号、空格分隔），去掉空行；不是 UUID 的、重复的单独列出。 */
export function idsFromText(text: string): { ids: string[], invalid: string[], repeated: string[] } {
  const parts = text.split(/[\s,，]+/).map((part) => part.trim()).filter(Boolean)
  const ids: string[] = []
  const invalid: string[] = []
  const repeated: string[] = []
  for (const part of parts) {
    if (!isUuid(part)) invalid.push(part)
    else if (ids.includes(part.toLowerCase())) repeated.push(part)
    else ids.push(part.toLowerCase())
  }
  return { ids, invalid, repeated }
}

/** 话题：空格或逗号分隔，# 去掉，去重。 */
export function tagsFromText(text: string): string[] {
  const tags: string[] = []
  for (const part of text.split(/[\s,，]+/)) {
    const tag = part.trim().replace(/^#+/, '')
    if (tag && !tags.includes(tag)) tags.push(tag)
  }
  return tags
}

function tooLong(value: string, max: number): boolean {
  return charCount(value.trim()) > max
}

/** 一块正文的问题（没有问题返回 null）；序号从 1 起，只用于提示。 */
export function blockProblem(block: BlockForm, position: number): string | null {
  const label = `第 ${position} 块（${BLOCK_TYPE_LABELS[block.type]}）`
  if (hasText(block.type)) {
    if (!block.text.trim()) return `${label}还没写内容`
    const max = block.type === 'HEADING' ? LIMITS.heading : LIMITS.blockText
    if (tooLong(block.text, max)) return `${label}最多 ${max} 个字`
    if (block.type === 'HEADING' && /\n/.test(block.text.trim())) return `${label}只能写一行`
    return null
  }
  if (hasItems(block.type)) {
    const items = block.items.filter((item) => item.title.trim() || item.text.trim())
    if (items.length === 0) return `${label}至少要一条`
    if (items.length > LIMITS.blockItems) return `${label}最多 ${LIMITS.blockItems} 条`
    if (items.some((item) => !item.title.trim())) return `${label}每条都要有标题`
    if (items.some((item) => tooLong(item.title, LIMITS.itemTitle) || tooLong(item.text, LIMITS.itemText))) {
      return `${label}标题最多 ${LIMITS.itemTitle} 字、说明最多 ${LIMITS.itemText} 字`
    }
    return null
  }
  if (!block.imageAlt.trim()) return `${label}要写替代文字（图片看不到时读出来的话）`
  if (tooLong(block.imageAlt, LIMITS.imageAlt) || tooLong(block.imageCaption, LIMITS.imageCaption)) {
    return `${label}替代文字和图注最多 ${LIMITS.imageAlt} 个字`
  }
  if (block.imageUrl.trim() && !isRetainedMediaUrl(block.imageUrl, block.originalImageUrl)) return `${label}的图片地址要是 https`
  return null
}

export function refsProblem(refs: RefForm[]): string | null {
  const filled = refs.filter((ref) => ref.refId.trim() || ref.title.trim())
  if (filled.length > LIMITS.refs) return `内容中提到最多 ${LIMITS.refs} 项`
  const seen = new Set<string>()
  for (const ref of filled) {
    if (!isUuid(ref.refId)) return `内容中提到的“${ref.title || REF_TYPE_LABELS[ref.type]}”ID 格式不对`
    if (!ref.title.trim()) return '内容中提到的每一项都要写名称'
    if (tooLong(ref.title, LIMITS.refTitle)) return `内容中提到的名称最多 ${LIMITS.refTitle} 个字`
    const key = `${ref.type}:${ref.refId.trim().toLowerCase()}`
    if (seen.has(key)) return '内容中提到的同一个对象只能写一次'
    seen.add(key)
  }
  return null
}

export function authorProblem(author: AuthorForm, label = '作者'): string | null {
  if (author.kind === 'PLATFORM') {
    if (!author.name.trim()) return `请填写平台署名（比如“东营城市运营中心”）`
    if (tooLong(author.name, LIMITS.authorName)) return `平台署名最多 ${LIMITS.authorName} 个字`
    return null
  }
  return author.organizationId ? null : `请选择${label}机构`
}

export function articleProblems(form: ArticleForm, existing?: OpsArticle | null): string[] {
  const problems: string[] = []
  const author = authorProblem(form.author)
  if (author) problems.push(author)
  if (form.kind === 'CASE' && form.author.kind === 'PLATFORM') problems.push('案例不能由平台发布，请选服务商或商家')
  if (form.kind === 'CASE' && !form.cityCode) problems.push('案例要选城市')
  if (form.coverUrl.trim() && !isRetainedMediaUrl(form.coverUrl, existing?.coverUrl)) problems.push('封面地址要是 https')
  if (form.blocks.length > LIMITS.blocks) problems.push(`正文最多 ${LIMITS.blocks} 块`)
  form.blocks.forEach((block, index) => {
    const problem = blockProblem(block, index + 1)
    if (problem) problems.push(problem)
  })
  const refs = refsProblem(form.refs)
  if (refs) problems.push(refs)
  return problems
}

export function videoProblems(form: VideoForm, existing?: OpsVideo | null): string[] {
  const problems: string[] = []
  const author = authorProblem(form.author)
  if (author) problems.push(author)
  if (!form.cityCode) problems.push('视频要选城市')
  if (!Number.isInteger(form.durationSeconds) || form.durationSeconds < 1 || form.durationSeconds > LIMITS.videoSeconds) {
    problems.push(`时长要在 1–${LIMITS.videoSeconds} 秒`)
  }
  if (form.playUrl.trim() && !isRetainedMediaUrl(form.playUrl, existing?.playUrl)) problems.push('播放地址要是 https')
  if (form.coverUrl.trim() && !isRetainedMediaUrl(form.coverUrl, existing?.coverUrl)) problems.push('封面地址要是 https')
  const tags = tagsFromText(form.tagsText)
  if (tags.length > LIMITS.tags) problems.push(`话题最多 ${LIMITS.tags} 个`)
  if (tags.some((tag) => charCount(tag) > LIMITS.tag || tag.includes('#'))) problems.push(`每个话题最多 ${LIMITS.tag} 个字`)
  if (!Number.isInteger(form.rank) || form.rank < 0 || form.rank > LIMITS.rank) problems.push(`运营排序要在 0–${LIMITS.rank}`)
  const refs = refsProblem(form.refs)
  if (refs) problems.push(refs)
  return problems
}

export function topicProblems(form: TopicForm, existing?: OpsTopic | null): string[] {
  const problems: string[] = []
  const publisher = authorProblem(form.publisher, '发布方')
  if (publisher) problems.push(publisher)
  if (!form.cityCode) problems.push('专题要选城市')
  if (!form.startsOn || !form.endsOn) problems.push('请选起止日期')
  else if (form.endsOn < form.startsOn) problems.push('结束日期不能早于开始日期')
  if (form.coverUrl.trim() && !isRetainedMediaUrl(form.coverUrl, existing?.coverUrl)) problems.push('封面地址要是 https')
  for (const [text, label, max] of [
    [form.activityIdsText, '活动', LIMITS.topicActivities],
    [form.venueIdsText, '场馆', LIMITS.topicVenues],
    [form.articleIdsText, '图文', LIMITS.topicArticles],
  ] as const) {
    const parsed = idsFromText(text)
    if (parsed.invalid.length) problems.push(`${label} ID 格式不对：${parsed.invalid.join('、')}`)
    if (parsed.repeated.length) problems.push(`${label} ID 重复了：${parsed.repeated.join('、')}`)
    if (parsed.ids.length > max) problems.push(`${label}最多 ${max} 个`)
  }
  const merchants = form.merchantItems.filter((item) => item.productId.trim() || item.merchantOrganizationId)
  if (merchants.length > LIMITS.topicMerchantItems) problems.push(`商家内容最多 ${LIMITS.topicMerchantItems} 条`)
  if (merchants.some((item) => !isUuid(item.productId))) problems.push('商家内容的商品 ID 格式不对')
  if (merchants.some((item) => !item.merchantOrganizationId)) problems.push('商家内容要选发布商家')
  if (new Set(merchants.map((item) => item.productId.trim().toLowerCase())).size !== merchants.length) {
    problems.push('商家内容的同一件商品只能挂一次')
  }
  return problems
}

// ---- 请求体 ----

function trimmedOrNull(value: string): string | null {
  const text = value.trim()
  return text ? text : null
}

function authorFields(author: AuthorForm): { kind: AuthorKind, organizationId: string | null, name: string | null } {
  return author.kind === 'PLATFORM'
    ? { kind: 'PLATFORM', organizationId: null, name: author.name.trim() }
    : { kind: author.kind, organizationId: author.organizationId || null, name: null }
}

export function blockRequest(block: BlockForm): BlockRequest {
  if (hasText(block.type)) {
    return { type: block.type, text: block.text.trim(), items: null, imageUrl: null, imageAlt: null, imageCaption: null }
  }
  if (hasItems(block.type)) {
    return {
      type: block.type,
      text: null,
      items: block.items
        .filter((item) => item.title.trim() || item.text.trim())
        .map((item) => ({ title: item.title.trim(), text: trimmedOrNull(item.text) })),
      imageUrl: null,
      imageAlt: null,
      imageCaption: null,
    }
  }
  return {
    type: 'IMAGE',
    text: null,
    items: null,
    imageUrl: trimmedOrNull(block.imageUrl),
    imageAlt: block.imageAlt.trim(),
    imageCaption: trimmedOrNull(block.imageCaption),
  }
}

function refsRequest(refs: RefForm[]): OpsRef[] {
  return refs
    .filter((ref) => ref.refId.trim() || ref.title.trim())
    .map((ref) => ({ type: ref.type, refId: ref.refId.trim(), title: ref.title.trim() }))
}

export function articleRequest(form: ArticleForm, version: number | null): ArticleRequest {
  const author = authorFields(form.author)
  return {
    kind: form.kind,
    title: form.title.trim(),
    authorKind: author.kind,
    authorOrganizationId: author.organizationId,
    authorName: author.name,
    cityCode: form.cityCode || null,
    coverUrl: trimmedOrNull(form.coverUrl),
    sample: form.sample,
    blocks: form.blocks.map(blockRequest),
    refs: refsRequest(form.refs),
    version,
  }
}

export function videoRequest(form: VideoForm, version: number | null): VideoRequest {
  const author = authorFields(form.author)
  return {
    title: form.title.trim(),
    authorKind: author.kind,
    authorOrganizationId: author.organizationId,
    authorName: author.name,
    cityCode: form.cityCode,
    coverUrl: trimmedOrNull(form.coverUrl),
    sample: form.sample,
    durationSeconds: form.durationSeconds,
    orientation: form.orientation,
    playUrl: trimmedOrNull(form.playUrl),
    caption: trimmedOrNull(form.caption),
    tags: tagsFromText(form.tagsText),
    rank: form.rank,
    refs: refsRequest(form.refs),
    version,
  }
}

export function topicRequest(form: TopicForm, version: number | null): TopicRequest {
  const publisher = authorFields(form.publisher)
  return {
    title: form.title.trim(),
    subtitle: trimmedOrNull(form.subtitle),
    publisherKind: publisher.kind,
    publisherOrganizationId: publisher.organizationId,
    publisherName: publisher.name,
    cityCode: form.cityCode,
    startsOn: form.startsOn,
    endsOn: form.endsOn,
    coverUrl: trimmedOrNull(form.coverUrl),
    sample: form.sample,
    activityIds: idsFromText(form.activityIdsText).ids,
    venueIds: idsFromText(form.venueIdsText).ids,
    articleIds: idsFromText(form.articleIdsText).ids,
    merchantItems: form.merchantItems
      .filter((item) => item.productId.trim() || item.merchantOrganizationId)
      .map((item) => ({ productId: item.productId.trim().toLowerCase(), merchantOrganizationId: item.merchantOrganizationId })),
    version,
  }
}

// ---- 时间 ----

/** 页面上按北京时间选的“YYYY-MM-DD HH:mm” → 带 +08:00 的 ISO 时间；不合格返回 null。 */
export function beijingToIso(value: string): string | null {
  const match = /^(\d{4}-\d{2}-\d{2})[ T](\d{2}):(\d{2})$/.exec(value.trim())
  return match ? `${match[1]}T${match[2]}:${match[3]}:00+08:00` : null
}

/** 95 → “1:35”。 */
export function formatDuration(seconds: number): string {
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
}
