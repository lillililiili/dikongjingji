import { isDemandType, type DemandStatus, type DemandType } from '../domain/demand'

export const DEMAND_PUBLISH_PATH = '/pages/demand/publish'
export const DEMAND_QUOTES_PATH = '/pages/demand/quotes'
export const DEMAND_COMPARE_PATH = '/pages/demand/compare'
export const MY_DEMANDS_PATH = '/pages/mine/demands'

// The list's status chips; DRAFT is its own chip, 全部 is no filter.
export const DEMAND_LIST_FILTERS = ['ALL', 'OPEN', 'QUOTED', 'CHOSEN', 'CLOSED', 'DRAFT'] as const
export type DemandListFilter = (typeof DEMAND_LIST_FILTERS)[number]

export interface DemandPublishTarget {
  // A draft to keep editing, or a submitted demand to look at.
  demandId?: string
  // A new demand of this type (the entry it came from).
  type?: DemandType
  // A new demand filled from an earlier one (重新发布).
  from?: string
  // A new demand that invites this provider only (发布需求 on a service or provider page). The
  // page still checks the provider against the inviteable list of the city and type.
  providerId?: string
}

// Only stable ids and the type travel in the URL; the page reads the demand from the server.
export function buildDemandPublishUrl(target: DemandPublishTarget = {}): string {
  const parameters: string[] = []
  if (target.demandId) parameters.push(`demandId=${encodeURIComponent(target.demandId)}`)
  else if (target.from) parameters.push(`from=${encodeURIComponent(target.from)}`)
  if (!target.demandId && target.type) parameters.push(`type=${target.type}`)
  if (!target.demandId && !target.from && target.providerId) {
    parameters.push(`providerId=${encodeURIComponent(target.providerId)}`)
  }
  return parameters.length ? `${DEMAND_PUBLISH_PATH}?${parameters.join('&')}` : DEMAND_PUBLISH_PATH
}

export function parseDemandPublishTarget(query: Record<string, unknown> | undefined): DemandPublishTarget {
  const read = (key: string) => (typeof query?.[key] === 'string' ? (query[key] as string).trim() : '')
  const demandId = read('demandId')
  if (demandId) return { demandId }
  const type = read('type')
  const from = read('from')
  const providerId = from ? '' : read('providerId')
  return {
    ...(isDemandType(type) ? { type } : {}),
    ...(from ? { from } : {}),
    ...(providerId ? { providerId } : {}),
  }
}

export function buildDemandQuotesUrl(demandId: string): string {
  return `${DEMAND_QUOTES_PATH}?demandId=${encodeURIComponent(demandId)}`
}

export function buildDemandCompareUrl(demandId: string): string {
  return `${DEMAND_COMPARE_PATH}?demandId=${encodeURIComponent(demandId)}`
}

export function buildMyDemandsUrl(filter: DemandListFilter = 'ALL'): string {
  return filter === 'ALL' ? MY_DEMANDS_PATH : `${MY_DEMANDS_PATH}?status=${filter}`
}

export function parseDemandListFilter(value: unknown): DemandListFilter {
  return typeof value === 'string' && (DEMAND_LIST_FILTERS as readonly string[]).includes(value)
    ? value as DemandListFilter
    : 'ALL'
}

export function demandStatusOfFilter(filter: DemandListFilter): DemandStatus | null {
  return filter === 'ALL' ? null : filter
}
