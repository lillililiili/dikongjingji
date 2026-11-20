import { iconTileToneAt, type IconTileTone } from '../../../../shared/ui/icon-tile-tones'

import type { ProServiceType } from '../../domain/proservice'

// Line icon paths copied from the SvcAerial / SvcSurvey / SvcMaint / SvcTech boards (and their
// scene variants). The boards give every scope cell its own icon by position within the type, so
// the server's scope labels (which differ per scene) take the icon of their place.
const BUILDINGS = 'M4 21V10l6-3v14M10 21V4l10 4v13M2 21h20M13.5 11h3M13.5 15h3'
const BARS = 'M4 20h16M7 20v-6M12 20V9M17 20V5'
const FLAG = 'M6 21V4M6 5h12l-3 3.5 3 3.5H6'
const CLAPPER = 'M4 6h16v13H4zM4 10h16M8 6l2 4M14 6l2 4'
const DRONE = 'M12 10v4M8 12h8M5 6a2 2 0 1 0 0 .1M19 6a2 2 0 1 0 0 .1M5 18a2 2 0 1 0 0 .1M19 18a2 2 0 1 0 0 .1M7 8l3 3M17 8l-3 3M7 16l3-3M17 16l-3-3'
const ROBOT_DOG = 'M4 10h10a2 2 0 0 0 2-2V6h3l2 2v3h-3l-1 3v6M4 10v10M8 20v-5h7M4 10L2.5 7'
const ROBOT = 'M8 8h8a3 3 0 0 1 3 3v6a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3v-6a3 3 0 0 1 3-3zM12 8V4M9.5 13h.01M14.5 13h.01M9.5 17h5'
const BATTERY = 'M4 8h14a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1zM22 11v2M7 11v2M11 11v2'
const MOUNTAIN = 'M2 20l7-12 4 6 3-4 6 10z'
const CUBE = 'M12 3l8 4.5v9L12 21l-8-4.5v-9zM4 7.5l8 4.5 8-4.5M12 12v9'
const LAYERS = 'M12 4l9 5-9 5-9-5zM3 14l9 5 9-5'
const ROUTE = 'M6 17a2 2 0 1 0 0 4a2 2 0 0 0 0-4zM18 3a2 2 0 1 0 0 4a2 2 0 0 0 0-4zM8 19h7a3 3 0 0 0 0-6H9a3 3 0 0 1 0-6h7'
const SLIDERS = 'M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4'
const WINDOW = 'M3 5h18v14H3zM3 9h18M6.5 7h.01M9.5 7h.01'
const BOOK = 'M12 7c-2-1.5-5-2-8-2v13c3 0 6 .5 8 2 2-1.5 5-2 8-2V5c-3 0-6 .5-8 2zM12 7v13'
const SHIELD_CHECK = 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM8.5 12l2.5 2.5 4.5-5'
const SHIELD_LINES = 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM9 10.5h6M9 14h6'
const SHIELD_LOCK = 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM9.5 11.5h5V15h-5zM10.5 11.5V10a1.5 1.5 0 0 1 3 0v1.5'
const DOC = 'M7 3h7l5 5v13H7zM14 3v5h5M10 13h6M10 17h4'
const SWAP = 'M8 20V5M4 9l4-4 4 4M16 4v15M12 15l4 4 4-4'
const WRENCH = 'M15 5a5 5 0 0 0-6.5 6.2L3.5 16.2a1.8 1.8 0 0 0 0 2.5l1.8 1.8a1.8 1.8 0 0 0 2.5 0l5-5A5 5 0 0 0 19 9l-3 3-3-3z'
const HEADSET = 'M4 14v-2a8 8 0 1 1 16 0v2M4 14h3v5H5a1 1 0 0 1-1-1zM20 14h-3v5h2a1 1 0 0 0 1-1zM17 19c0 1.5-2 2-5 2'
const PERSON = 'M12 12a4 4 0 1 0 0-8a4 4 0 1 0 0 8zM4 21c1-4 4-6 8-6s7 2 8 6'
const PIN = 'M12 21c4.6-4.7 7-8.1 7-11a7 7 0 0 0-14 0c0 2.9 2.4 6.3 7 11zM12 12.5a2.5 2.5 0 1 0 0-5a2.5 2.5 0 0 0 0 5z'
const PARCEL = 'M3 8l9-5 9 5v8l-9 5-9-5zM3 8l9 5 9-5M12 13v8'
const VIDEO = 'M4 6h12v12H4zM16 10.5l4-2.5v8l-4-2.5'
const PHOTO = 'M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15.5 9.5h.01'
const MAP = 'M4 6l5-2 6 2 5-2v14l-5 2-6-2-5 2zM9 4v14M15 6v14'

const SCOPE_BY_TYPE: Record<ProServiceType, readonly string[]> = {
  AERIAL: [BUILDINGS, BARS, FLAG, CLAPPER],
  SURVEY: [MOUNTAIN, CUBE, LAYERS, ROUTE],
  MAINTENANCE: [DRONE, ROBOT_DOG, ROBOT, BATTERY],
  TECH_SUPPORT: [SLIDERS, WINDOW, BOOK, SHIELD_CHECK],
}

/** The orange line icon of the nth cell in 拍摄内容 / 作业范围 / 适用设备 / 支持内容. */
export function scopeIconPath(type: ProServiceType, index: number): string {
  const icons = SCOPE_BY_TYPE[type]
  return icons[index % icons.length]
}

// 成果格式 / 交付成果: each format has its picture and its tile colour (视频紫、照片橙、正射蓝).
const OUTPUTS: Record<string, { d: string; tone: IconTileTone }> = {
  视频素材: { d: VIDEO, tone: 'violet' },
  成片剪辑: { d: VIDEO, tone: 'violet' },
  照片: { d: PHOTO, tone: 'orange' },
  正射影像: { d: MAP, tone: 'blue' },
  三维模型: { d: CUBE, tone: 'violet' },
}

export function outputIcon(label: string, index: number): { d: string; tone: IconTileTone } {
  return OUTPUTS[label] ?? { d: DOC, tone: iconTileToneAt(index) }
}

// 服务流程: the request and the comparison open every flow, the acceptance closes it; the middle
// steps have their own pictures.
const STEPS: Record<string, string> = {
  提交需求: DOC,
  报价比选: SWAP,
  检测报价: SWAP,
  现场拍摄: DRONE,
  外业采集: DRONE,
  内业处理: LAYERS,
  维修保养: WRENCH,
  支持实施: HEADSET,
}

export function stepIconPath(label: string, index: number, count: number): string {
  if (STEPS[label]) return STEPS[label]
  if (index === count - 1) return SHIELD_CHECK
  return index === 0 ? DOC : SWAP
}

const GUARANTEES = [SHIELD_CHECK, SHIELD_LINES, SHIELD_LOCK] as const

/** 服务保障: the three shields in board order (check, lines, lock). */
export function guaranteeIconPath(index: number): string {
  return GUARANTEES[index % GUARANTEES.length]
}

/** The round icon of a 服务方式 / 支持方式 card, read from its name. */
export function modeIconPath(label: string): string {
  if (label.includes('远程')) return HEADSET
  if (label.includes('驻场')) return PERSON
  if (label.includes('送修') || label.includes('寄')) return PARCEL
  return PIN
}
