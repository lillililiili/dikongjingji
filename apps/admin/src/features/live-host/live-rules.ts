import { charCount } from '@/core/ui/format'

import { ITEM_TYPES, type LiveItemType, type LiveRoom, type LiveRoomRequest } from './live-host-api'

// 直播主办工作台的页面规则，和 LiveService 的校验一致（后端仍会再校验）。

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * 推流地址打码：只留协议和主机（让人认得出是哪家推流服务），路径和流密钥换成星号。
 * 推流地址等于开播权限，只给本机构主播，页面默认不显示原文。
 */
export function maskPushUrl(pushUrl: string): string {
  const match = /^([a-z][a-z0-9+.-]*:\/\/[^/?#]+)/i.exec(pushUrl.trim())
  return match ? `${match[1]}/********` : '********'
}

/**
 * 讲解项的对象 ID：可以直接粘 ID，也可以粘 App 详情页“分享 → 复制链接”得到的链接（…/pages/mall/product?productId=…），
 * 从链接里按类型取参数。后端只校验是不是 UUID，不校验对象存不存在，所以填错了观众点进去会提示找不到。
 * 取不到返回 null。
 */
export function parseItemRef(type: LiveItemType, input: string): string | null {
  const value = input.trim()
  if (UUID.test(value)) return value.toLowerCase()
  const param = ITEM_TYPES.find((option) => option.value === type)?.linkParam
  if (!param) return null
  const query = value.includes('?') ? value.slice(value.indexOf('?') + 1) : ''
  const found = new URLSearchParams(query.split('#')[0]).get(param)
  return found && UUID.test(found) ? found.toLowerCase() : null
}

export interface RoomDraft {
  title: string
  subtitle: string
  coverUrl: string
  cityCode: string
  scheduledAt: string
  speakerName: string
  speakerTitle: string
  /** 一行一条。 */
  topics: string
}

export function emptyDraft(now: Date = new Date()): RoomDraft {
  const next = new Date(now.getTime() + 86_400_000)
  next.setSeconds(0, 0)
  return {
    title: '', subtitle: '', coverUrl: '', cityCode: '', scheduledAt: next.toISOString(),
    speakerName: '', speakerTitle: '', topics: '',
  }
}

export function draftOf(room: LiveRoom): RoomDraft {
  return {
    title: room.title,
    subtitle: room.subtitle ?? '',
    coverUrl: room.coverUrl ?? '',
    cityCode: room.cityCode ?? '',
    scheduledAt: room.scheduledAt,
    speakerName: room.speakerName ?? '',
    speakerTitle: room.speakerTitle ?? '',
    topics: room.topics.join('\n'),
  }
}

export function topicsOf(text: string): string[] {
  return text.split('\n').map((line) => line.trim()).filter((line) => line !== '')
}

/** 建直播间 / 改预告前检查，返回第一条问题。 */
export function draftProblem(draft: RoomDraft, now: Date = new Date()): string | null {
  const title = draft.title.trim()
  if (!title || charCount(title) > 30) return '标题要填，最多 30 个字'
  if (charCount(draft.subtitle.trim()) > 40) return '副标题最多 40 个字'
  const cover = draft.coverUrl.trim()
  if (cover && (!/^https:\/\//i.test(cover) || cover.length > 500)) return '封面要是 https 地址（≤500 字符）'
  if (draft.cityCode.trim() && !/^\d{6}$/.test(draft.cityCode.trim())) return '城市编码是 6 位数字（如 370500 东营），空着表示全国可见'
  const at = new Date(draft.scheduledAt).getTime()
  if (!draft.scheduledAt || Number.isNaN(at)) return '选一个开播时间'
  if (at <= now.getTime()) return '开播时间要在现在之后'
  if (at > now.getTime() + 90 * 86_400_000) return '开播时间要在 90 天以内'
  if (charCount(draft.speakerName.trim()) > 20) return '主讲人最多 20 个字'
  if (charCount(draft.speakerTitle.trim()) > 30) return '主讲人身份最多 30 个字'
  const topics = topicsOf(draft.topics)
  if (topics.length > 5) return '讲什么最多 5 条（一行一条）'
  if (topics.some((topic) => charCount(topic) > 40)) return '讲什么每条最多 40 个字'
  return null
}

export function requestOf(draft: RoomDraft): LiveRoomRequest {
  const text = (value: string) => value.trim() || null
  const topics = topicsOf(draft.topics)
  return {
    title: draft.title.trim(),
    subtitle: text(draft.subtitle),
    coverUrl: text(draft.coverUrl),
    cityCode: text(draft.cityCode),
    scheduledAt: new Date(draft.scheduledAt).toISOString(),
    speakerName: text(draft.speakerName),
    speakerTitle: text(draft.speakerTitle),
    topics: topics.length ? topics : null,
  }
}

export interface ItemDraft {
  type: LiveItemType
  ref: string
  title: string
}

/** 讲解清单检查：≤10 项、ID 认得出、标题 1–40 字、同一个对象不出现两次。 */
export function itemsProblem(items: readonly ItemDraft[]): string | null {
  if (items.length > 10) return '讲解清单最多 10 项'
  const seen = new Set<string>()
  for (const [index, item] of items.entries()) {
    const ref = parseItemRef(item.type, item.ref)
    if (!ref) return `第 ${index + 1} 项认不出对象 ID：粘贴 ID，或 App 详情页“分享 → 复制链接”的链接`
    const title = item.title.trim()
    if (!title || charCount(title) > 40) return `第 ${index + 1} 项要填标题（≤40 字）`
    const identity = `${item.type}:${ref}`
    if (seen.has(identity)) return `第 ${index + 1} 项和前面重复了`
    seen.add(identity)
  }
  return null
}

/** 回看链接：两项同时给或同时不给，链接要 https、≤500 字符，平台名 ≤20 字。 */
export function replayProblem(url: string, platform: string): string | null {
  const link = url.trim()
  const name = platform.trim()
  if (!link && !name) return null
  if (!link || !name) return '回看链接和平台名要一起填（都空表示去掉回看）'
  if (!/^https:\/\//i.test(link) || link.length > 500) return '回看链接要是 https 地址（≤500 字符）'
  if (charCount(name) > 20) return '平台名最多 20 个字'
  return null
}
