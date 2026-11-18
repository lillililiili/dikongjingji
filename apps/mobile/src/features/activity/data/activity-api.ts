import type { PublicApiHttpClient } from '@/shared/http/public-api-request'

import type {
  ActivityAgeRange,
  ActivityCard,
  ActivityDetail,
  ActivityEnrollmentStatus,
  ActivityImage,
  ActivityListQuery,
  ActivityPage,
  ActivityPrice,
  ActivityPriceMode,
  ActivitySession,
  ActivityType,
} from '../domain/activity'

interface MediaAssetDto {
  url: string
  alt: string
}

interface ActivityPriceDto {
  mode: ActivityPriceMode
  amountMinor: number | null
  currency: string
}

export interface ActivityCardDto {
  activityId: string
  type: ActivityType
  title: string
  coverImage: MediaAssetDto | null
  venueName: string
  scheduleNote: string | null
  price: ActivityPriceDto
  ageRange: ActivityAgeRange
  enrollmentStatus: ActivityEnrollmentStatus
  nextSession: {
    sessionId: string
    startsAt: string
    capacity: number
    seatsLeft: number
  } | null
}

interface ActivityPageDto {
  page: number
  size: number
  total: number
  hasMore: boolean
  items: ActivityCardDto[]
}

interface ActivitySessionDatesDto {
  dates: string[]
}

interface ActivityDetailDto {
  activityId: string
  type: ActivityType
  title: string
  subtitle: string | null
  coverImage: MediaAssetDto | null
  price: ActivityPriceDto
  ageRange: ActivityAgeRange
  enrollmentStatus: ActivityEnrollmentStatus
  organizer: { organizationId: string; name: string }
  coOrganizer: { organizationId: string; name: string } | null
  location: {
    name: string
    address: string
    latitude: number | null
    longitude: number | null
  }
  scheduleNote: string | null
  eligibilityNote: string | null
  seatNote: string | null
  refundNote: string | null
  waitlistAllowed: boolean
  teamEnrollmentAllowed: boolean
  sessions: ActivitySession[]
}

export interface ActivityApi {
  loadPage(query: ActivityListQuery, page: number, size: number): Promise<ActivityPage>
  loadSessionDates(input: {
    cityCode: string
    type: ActivityType | null
    from: string
    to: string
  }): Promise<string[]>
  loadDetail(activityId: string, cityCode: string): Promise<ActivityDetail>
}

// The server already filters images to HTTPS; this keeps a bad row from reaching an <image> tag anyway.
function toImage(asset: MediaAssetDto | null): ActivityImage | null {
  return asset && /^https:\/\//i.test(asset.url)
    ? { url: asset.url, alt: asset.alt }
    : null
}

function toPrice(price: ActivityPriceDto): ActivityPrice {
  return {
    mode: price.mode,
    amountMinor: price.mode === 'PER_PERSON' ? price.amountMinor : null,
  }
}

// Also used by the home recommend block, which receives the same card shape.
export function toCard(dto: ActivityCardDto): ActivityCard {
  return {
    activityId: dto.activityId,
    type: dto.type,
    title: dto.title,
    coverImage: toImage(dto.coverImage),
    venueName: dto.venueName,
    scheduleNote: dto.scheduleNote,
    price: toPrice(dto.price),
    ageRange: { minAge: dto.ageRange.minAge, maxAge: dto.ageRange.maxAge },
    enrollmentStatus: dto.enrollmentStatus,
    nextSession: dto.nextSession ? { ...dto.nextSession } : null,
  }
}

function toDetail(dto: ActivityDetailDto): ActivityDetail {
  return {
    activityId: dto.activityId,
    type: dto.type,
    title: dto.title,
    subtitle: dto.subtitle,
    coverImage: toImage(dto.coverImage),
    price: toPrice(dto.price),
    ageRange: { minAge: dto.ageRange.minAge, maxAge: dto.ageRange.maxAge },
    enrollmentStatus: dto.enrollmentStatus,
    organizer: { ...dto.organizer },
    coOrganizer: dto.coOrganizer ? { ...dto.coOrganizer } : null,
    location: { ...dto.location },
    scheduleNote: dto.scheduleNote,
    eligibilityNote: dto.eligibilityNote,
    seatNote: dto.seatNote,
    refundNote: dto.refundNote,
    waitlistAllowed: dto.waitlistAllowed,
    teamEnrollmentAllowed: dto.teamEnrollmentAllowed,
    sessions: dto.sessions.map((session) => ({ ...session })),
  }
}

function toQueryString(parameters: Record<string, string | number | boolean | null>): string {
  return Object.entries(parameters)
    .filter(([, value]) => value !== null && value !== false)
    .map(([key, value]) => `${key}=${encodeURIComponent(String(value))}`)
    .join('&')
}

export function createActivityApi(http: PublicApiHttpClient): ActivityApi {
  return {
    async loadPage(query, page, size) {
      const dto = await http.request<ActivityPageDto>({
        method: 'GET',
        url: `/api/v1/activities?${toQueryString({
          cityCode: query.cityCode,
          type: query.type,
          date: query.date,
          enrolling: query.enrollingOnly,
          free: query.freeOnly,
          page,
          size,
        })}`,
      })
      return {
        page: dto.page,
        size: dto.size,
        total: dto.total,
        hasMore: dto.hasMore,
        items: dto.items.map(toCard),
      }
    },

    async loadSessionDates(input) {
      const dto = await http.request<ActivitySessionDatesDto>({
        method: 'GET',
        url: `/api/v1/activities/session-dates?${toQueryString({
          cityCode: input.cityCode,
          type: input.type,
          from: input.from,
          to: input.to,
        })}`,
      })
      return [...dto.dates]
    },

    async loadDetail(activityId, cityCode) {
      const dto = await http.request<ActivityDetailDto>({
        method: 'GET',
        url: `/api/v1/activities/${encodeURIComponent(activityId)}?${toQueryString({ cityCode })}`,
      })
      return toDetail(dto)
    },
  }
}
