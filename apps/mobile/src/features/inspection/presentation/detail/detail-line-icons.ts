// Line icons of the inspection detail boards (InspDetail, InspDetailOil / Solar / Park / Heat),
// copied path for path. The server sends labels and a few legacy icon keys, so the icon is picked
// by position (服务内容, as the boards do) or by the words in the label (流程, 交付成果, 保障).

const SCOPE_ICONS = [
  'M12 3L7 21M12 3l5 18M5 7h14M9.2 13h5.6M8 17.5h8',
  'M4 4v16M20 4v16M4 7c5 5 11 5 16 0M4 12c5 5 11 5 16 0',
  'M12 3v18M7.5 6h9M6 10h12M7.5 14h9M6 18h12',
  'M12 3l5 7h-3l4 6H6l4-6H7zM12 16v5',
] as const

const PARK_SCOPE_ICONS = [
  'M5 9l2-3 2 3v11H5zM15 9l2-3 2 3v11h-4zM2 12h20M2 16h20',
  'M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z',
  'M12 21c4.6-4.7 7-8.1 7-11a7 7 0 0 0-14 0c0 2.9 2.4 6.3 7 11zM12 12.5a2.5 2.5 0 1 0 0-5a2.5 2.5 0 0 0 0 5z',
  'M12 4l9 16H3zM12 10v4M12 17h.01',
] as const

export function scopeIcon(categoryCode: string, index: number): string {
  const icons = categoryCode === 'PARK' ? PARK_SCOPE_ICONS : SCOPE_ICONS
  return icons[index % icons.length]
}

const STEP_DOC = 'M7 3h7l5 5v13H7zM14 3v5h5M10 13h6M10 17h4'
const STEP_PLAN = 'M9 4h6v3H9zM7 5H5v16h14V5h-2M9 14l2 2 4-4'
const STEP_SITE = 'M12 10v4M8 12h8M5 6a2 2 0 1 0 0 .1M19 6a2 2 0 1 0 0 .1M5 18a2 2 0 1 0 0 .1M19 18a2 2 0 1 0 0 .1M7 8l3 3M17 8l-3 3M7 16l3-3M17 16l-3-3'
const STEP_REPORT = 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM8.5 12l2.5 2.5 4.5-5'
const STEP_COMPARE = 'M8 20V5M4 9l4-4 4 4M16 4v15M12 15l4 4 4-4'
const STEP_ORDER = [STEP_DOC, STEP_PLAN, STEP_SITE, STEP_REPORT] as const

export function processStepIcon(label: string, index: number): string {
  if (/报价|比选/.test(label)) return STEP_COMPARE
  if (/需求|预约/.test(label)) return STEP_DOC
  if (/方案|确认/.test(label)) return STEP_PLAN
  if (/报告|交付|验收/.test(label)) return STEP_REPORT
  if (/巡检|执行|履约|现场/.test(label)) return STEP_SITE
  return STEP_ORDER[index % STEP_ORDER.length]
}

const FILE_IMAGES = 'M4 5h16v14H4zM4 16l5-5 4 4 3-3 4 4M15.5 9.5h.01'
const FILE_LIST = 'M4 5h16v14H4zM4 10h16M4 15h16M10 5v14'
const FILE_POINTS = 'M12 21c4.6-4.7 7-8.1 7-11a7 7 0 0 0-14 0c0 2.9 2.4 6.3 7 11zM12 12.5a2.5 2.5 0 1 0 0-5a2.5 2.5 0 0 0 0 5z'
const FILE_REPORT = 'M6 3h8l4 4v14H6zM14 3v4h4M9 12h6M9 16h6'

export function deliverableIcon(label: string): string {
  if (/清单|台账/.test(label)) return FILE_LIST
  if (/点位|标注|定位/.test(label)) return FILE_POINTS
  if (/影像|图|照片|视频|热成像/.test(label)) return FILE_IMAGES
  return FILE_REPORT
}

const GUARD_PILOT = 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM8.5 12l2.5 2.5 4.5-5'
const GUARD_COMPLIANCE = 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM9 10.5h6M9 14h6'
const GUARD_PRIVACY = 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6zM9.5 11.5h5V15h-5zM10.5 11.5V10a1.5 1.5 0 0 1 3 0v1.5'

export function guaranteeIcon(label: string): string {
  if (/保密|数据|隐私/.test(label)) return GUARD_PRIVACY
  if (/合规|规范|资质/.test(label)) return GUARD_COMPLIANCE
  return GUARD_PILOT
}

// The category badge on the head picture. Board colours: 电力 green, 油气 blue, 光伏 violet,
// 热力 orange-brown; 园区, 定期 and the rest green, as on InspDetailPark and InspDetailCycle.
const BADGE_COLOURS: Record<string, string> = {
  OIL_GAS: '#1d63c4',
  PHOTOVOLTAIC: '#6a4fe0',
  THERMAL: '#ba470b',
}

export function categoryBadgeColour(categoryCode: string): string {
  return BADGE_COLOURS[categoryCode] ?? '#11804a'
}

/** “电力巡检”: the boards name the badge after the category plus 巡检. */
export function categoryBadgeLabel(categoryLabel: string): string {
  if (!categoryLabel) return ''
  return categoryLabel.endsWith('巡检') ? categoryLabel : `${categoryLabel}巡检`
}
