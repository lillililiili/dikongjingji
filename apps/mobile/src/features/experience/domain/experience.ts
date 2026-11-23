// Mirrors contracts/openapi-experience.yaml. Session statuses are worked out by the server.

export type ExperienceType =
  | 'SIMULATION'
  | 'RECORDED_DEMO'
  | 'SOFTWARE_TRIAL'
  | 'COURSE_TRIAL'

export interface ExperienceImage {
  url: string
  alt: string
}

export interface ExperienceItem {
  experienceId: string
  type: ExperienceType
  title: string
  summary: string | null
  coverImage: ExperienceImage | null
  loginRequired: boolean
}

export interface VenueCard {
  venueId: string
  name: string
  coverImage: ExperienceImage | null
  openingNote: string | null
  address: string
  openSessionsToday: number
  // 1.4.0 (S4-4): the card's first line, e.g. 模拟飞行 · 机器人编程 · 厂家展区 (board HomeFull).
  projectSummary: string | null
}

export interface VenueProject {
  projectId: string
  name: string
  durationMinutes: number
  minAge: number | null
  capacity: number
  limitNote: string | null
  priceAmountMinor: number
  // 1.1.0: a priced project the venue can take payment for. False on a free project (no payment)
  // and on a priced one whose operator has no WeChat account yet (the confirm page cannot submit).
  onlinePayment: boolean
}

export interface VenueInfoItem {
  label: string
  content: string
}

export interface VenueDetail {
  venueId: string
  name: string
  coverImage: ExperienceImage | null
  openingNote: string | null
  address: string
  latitude: number | null
  longitude: number | null
  phone: string | null
  operatorName: string
  projects: VenueProject[]
  infoItems: VenueInfoItem[]
  deviceCount: number
}

export type VenueSessionStatus = 'OPEN' | 'FULL' | 'ENDED'

export interface VenueSession {
  sessionId: string
  startsAt: string
  endsAt: string
  capacity: number
  seatsLeft: number
  status: VenueSessionStatus
}

export type VenueDeviceCategory = 'ROBOT' | 'DRONE' | 'PARTS'

export type VenueDeviceTrialMode = 'ACCOMPANIED_TRIAL' | 'DISPLAY_ONLY'

export interface VenueDevice {
  deviceId: string
  category: VenueDeviceCategory
  name: string
  summary: string | null
  image: ExperienceImage | null
  trialMode: VenueDeviceTrialMode
  // 1.4.0 (S4-4): the linked mall product as it sells in the venue's city (board VenueDevices); null without one.
  product: VenueDeviceProduct | null
}

export type VenueDeviceSaleMode = 'BUY' | 'RENT' | 'INQUIRY'
export type VenueDevicePriceUnit = 'ITEM' | 'DAY' | 'HALF_DAY'

export interface VenueDeviceProduct {
  productId: string
  saleMode: VenueDeviceSaleMode
  // Lowest price of the city's offers in fen; null for an inquiry product.
  fromAmountMinor: number | null
  priceUnit: VenueDevicePriceUnit
  multiplePrices: boolean
  sellerCount: number
}

// R6-2 先体验 (1.2.0): where a mall product can be tried before buying.
export interface ProductExperienceItem {
  experienceId: string
  type: ExperienceType
  title: string
  // The operator's line with the duration, e.g. 约 3 分钟 · 免登录可试.
  summary: string | null
}

export interface ProductExperienceVenue {
  venueId: string
  name: string
  // The venue's device linked to the product.
  deviceName: string
}

export interface ProductExperiences {
  productId: string
  experiences: ProductExperienceItem[]
  // Venues of the city with the product on show, in the venues' order.
  venues: ProductExperienceVenue[]
}

export interface Paged<Item> {
  page: number
  size: number
  total: number
  hasMore: boolean
  items: Item[]
}

export const EXPERIENCE_PAGE_SIZE = 20
