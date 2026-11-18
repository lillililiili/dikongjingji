import type { PublicApiHttpClient } from '@/shared/http/public-api-request'

import type {
  ExperienceImage,
  ExperienceItem,
  ExperienceType,
  Paged,
  VenueCard,
  VenueDetail,
  VenueDevice,
  VenueDeviceCategory,
  VenueDeviceTrialMode,
  VenueInfoItem,
  VenueSession,
} from '../domain/experience'

interface MediaAssetDto {
  url: string
  alt: string
}

interface PageDto<Item> {
  page: number
  size: number
  total: number
  hasMore: boolean
  items: Item[]
}

interface ExperienceItemDto {
  experienceId: string
  type: ExperienceType
  title: string
  summary: string | null
  coverImage: MediaAssetDto | null
  loginRequired: boolean
}

export interface VenueCardDto {
  venueId: string
  name: string
  coverImage: MediaAssetDto | null
  openingNote: string | null
  address: string
  openSessionsToday: number
}

interface VenueDetailDto {
  venueId: string
  name: string
  coverImage: MediaAssetDto | null
  openingNote: string | null
  address: string
  latitude: number | null
  longitude: number | null
  phone: string | null
  operator: { organizationId: string; name: string }
  projects: {
    projectId: string
    name: string
    durationMinutes: number
    minAge: number | null
    capacity: number
    limitNote: string | null
    priceAmountMinor: number
    currency: string
  }[]
  infoItems: VenueInfoItem[]
  deviceCount: number
}

interface VenueSessionListDto {
  date: string
  sessions: VenueSession[]
}

interface VenueDeviceDto {
  deviceId: string
  category: VenueDeviceCategory
  name: string
  summary: string | null
  image: MediaAssetDto | null
  trialMode: VenueDeviceTrialMode
}

export interface ExperienceApi {
  loadExperienceItems(type: ExperienceType | null, page: number, size: number): Promise<Paged<ExperienceItem>>
  loadVenues(cityCode: string, page: number, size: number): Promise<Paged<VenueCard>>
  loadVenue(venueId: string, cityCode: string): Promise<VenueDetail>
  loadSessions(input: {
    venueId: string
    cityCode: string
    projectId: string
    date: string
  }): Promise<VenueSession[]>
  loadDevices(input: {
    venueId: string
    cityCode: string
    category: VenueDeviceCategory | null
  }, page: number, size: number): Promise<Paged<VenueDevice>>
}

// The server already filters images to HTTPS; this keeps a bad row from reaching an <image> tag anyway.
function toImage(asset: MediaAssetDto | null): ExperienceImage | null {
  return asset && /^https:\/\//i.test(asset.url)
    ? { url: asset.url, alt: asset.alt }
    : null
}

// Also used by the home recommend block, which receives the same card shape.
export function toVenueCard(venue: VenueCardDto): VenueCard {
  return {
    venueId: venue.venueId,
    name: venue.name,
    coverImage: toImage(venue.coverImage),
    openingNote: venue.openingNote,
    address: venue.address,
    openSessionsToday: venue.openSessionsToday,
  }
}

function toPage<Dto, Item>(dto: PageDto<Dto>, map: (item: Dto) => Item): Paged<Item> {
  return {
    page: dto.page,
    size: dto.size,
    total: dto.total,
    hasMore: dto.hasMore,
    items: dto.items.map(map),
  }
}

function toQueryString(parameters: Record<string, string | number | null>): string {
  return Object.entries(parameters)
    .filter(([, value]) => value !== null)
    .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
    .join('&')
}

export function createExperienceApi(http: PublicApiHttpClient): ExperienceApi {
  return {
    async loadExperienceItems(type, page, size) {
      const dto = await http.request<PageDto<ExperienceItemDto>>({
        method: 'GET',
        url: `/api/v1/experiences?${toQueryString({ type, page, size })}`,
      })
      return toPage(dto, (item) => ({
        experienceId: item.experienceId,
        type: item.type,
        title: item.title,
        summary: item.summary,
        coverImage: toImage(item.coverImage),
        loginRequired: item.loginRequired,
      }))
    },

    async loadVenues(cityCode, page, size) {
      const dto = await http.request<PageDto<VenueCardDto>>({
        method: 'GET',
        url: `/api/v1/venues?${toQueryString({ cityCode, page, size })}`,
      })
      return toPage(dto, toVenueCard)
    },

    async loadVenue(venueId, cityCode) {
      const dto = await http.request<VenueDetailDto>({
        method: 'GET',
        url: `/api/v1/venues/${encodeURIComponent(venueId)}?${toQueryString({ cityCode })}`,
      })
      return {
        venueId: dto.venueId,
        name: dto.name,
        coverImage: toImage(dto.coverImage),
        openingNote: dto.openingNote,
        address: dto.address,
        latitude: dto.latitude,
        longitude: dto.longitude,
        phone: dto.phone,
        operatorName: dto.operator.name,
        projects: dto.projects.map((project) => ({
          projectId: project.projectId,
          name: project.name,
          durationMinutes: project.durationMinutes,
          minAge: project.minAge,
          capacity: project.capacity,
          limitNote: project.limitNote,
          priceAmountMinor: project.priceAmountMinor,
        })),
        infoItems: dto.infoItems.map((item) => ({ label: item.label, content: item.content })),
        deviceCount: dto.deviceCount,
      }
    },

    async loadSessions(input) {
      const dto = await http.request<VenueSessionListDto>({
        method: 'GET',
        url: `/api/v1/venues/${encodeURIComponent(input.venueId)}/sessions?${toQueryString({
          cityCode: input.cityCode,
          projectId: input.projectId,
          date: input.date,
        })}`,
      })
      return dto.sessions.map((session) => ({ ...session }))
    },

    async loadDevices(input, page, size) {
      const dto = await http.request<PageDto<VenueDeviceDto>>({
        method: 'GET',
        url: `/api/v1/venues/${encodeURIComponent(input.venueId)}/devices?${toQueryString({
          cityCode: input.cityCode,
          category: input.category,
          page,
          size,
        })}`,
      })
      return toPage(dto, (device) => ({
        deviceId: device.deviceId,
        category: device.category,
        name: device.name,
        summary: device.summary,
        image: toImage(device.image),
        trialMode: device.trialMode,
      }))
    },
  }
}
