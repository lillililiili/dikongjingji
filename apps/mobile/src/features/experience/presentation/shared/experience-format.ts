import { formatBeijingTime } from '../../../../shared/time/beijing-time'
import type {
  ExperienceType,
  VenueCard,
  VenueDeviceCategory,
  VenueDevicePriceUnit,
  VenueDeviceProduct,
  VenueDeviceTrialMode,
  VenueProject,
  VenueSession,
} from '../../domain/experience'

export const EXPERIENCE_TYPE_LABELS: Record<ExperienceType, string> = {
  SIMULATION: '虚拟仿真',
  RECORDED_DEMO: '录播演示',
  SOFTWARE_TRIAL: '软件试用',
  COURSE_TRIAL: '课程试听',
}

// The tag on each card says what kind of experience it is, so nobody mistakes it for a real device.
export const EXPERIENCE_TYPE_TAGS: Record<ExperienceType, string> = {
  SIMULATION: '虚拟仿真 · 不连接真实设备',
  RECORDED_DEMO: '录播演示',
  SOFTWARE_TRIAL: '软件试用',
  COURSE_TRIAL: '课程试听',
}

export const VENUE_DEVICE_CATEGORY_LABELS: Record<VenueDeviceCategory, string> = {
  ROBOT: '机器人',
  DRONE: '无人机',
  PARTS: '配件',
}

export const VENUE_DEVICE_TRIAL_LABELS: Record<VenueDeviceTrialMode, string> = {
  ACCOMPANIED_TRIAL: '工作人员陪同试用',
  DISPLAY_ONLY: '只展示，不试用',
}

// "09:30–18:00 · 周一闭馆 · 今日 3 场可约"; the count is left out when nothing can be booked today.
export function formatVenueCardNote(venue: VenueCard): string {
  return [
    venue.openingNote,
    venue.openSessionsToday > 0 ? `今日 ${venue.openSessionsToday} 场可约` : null,
  ].filter((part): part is string => Boolean(part)).join(' · ')
}

// "30 分钟 · 6 岁以上 · 每场 8 人"
export function formatVenueProjectMeta(project: VenueProject): string {
  return [
    `${project.durationMinutes} 分钟`,
    project.minAge !== null ? `${project.minAge} 岁以上` : null,
    `每场 ${project.capacity} 人`,
  ].filter((part): part is string => Boolean(part)).join(' · ')
}

export function formatVenuePrice(amountMinor: number): string {
  if (amountMinor === 0) return '免费'
  const yuan = amountMinor / 100
  const amount = Number.isInteger(yuan)
    ? yuan.toLocaleString('zh-CN')
    : yuan.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return `¥${amount}`
}

const DEVICE_PRICE_UNITS: Record<VenueDevicePriceUnit, string> = {
  ITEM: '',
  DAY: '/天',
  HALF_DAY: '/半天',
}

export interface VenueDeviceOfferText {
  price: string
  unit: string
  sellers: string
}

// Board VenueDevices' bottom line of a device card: "¥1,299 起 · 3 家在售", "¥380 /天 起 · 租赁 · 1 家",
// "询价 · 按项目报价". 起 only when the merchants of the city ask more than one price.
export function formatVenueDeviceOffer(product: VenueDeviceProduct): VenueDeviceOfferText {
  if (product.saleMode === 'INQUIRY' || product.fromAmountMinor === null) {
    return { price: '询价', unit: '', sellers: '按项目报价' }
  }
  const from = product.multiplePrices ? '起' : ''
  const per = DEVICE_PRICE_UNITS[product.priceUnit]
  return {
    price: formatVenuePrice(product.fromAmountMinor),
    unit: [per, from].filter(Boolean).join(' '),
    sellers: product.saleMode === 'RENT'
      ? `租赁 · ${product.sellerCount} 家`
      : `${product.sellerCount} 家在售`,
  }
}

export function formatVenueSessionTime(session: VenueSession): string {
  return formatBeijingTime(session.startsAt)
}

export function formatVenueSessionSeats(session: VenueSession): string {
  if (session.status === 'ENDED') return '已结束'
  if (session.status === 'FULL') return '已满'
  return `余 ${session.seatsLeft}`
}

// Digits only, so "0546-8000 0000" can be handed to the dialer.
export function toDialNumber(phone: string): string {
  return phone.replace(/[^\d+]/g, '')
}
