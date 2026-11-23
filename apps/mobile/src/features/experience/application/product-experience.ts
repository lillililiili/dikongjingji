import type { ExperienceApi } from '../data/experience-api'
import type { ExperienceType, ProductExperiences } from '../domain/experience'
import { buildVenueDetailUrl, buildVenueDevicesUrl } from '../navigation/experience-routes'

import { resolveExperienceIntent } from './experience-intent'

// 先体验，再决定 on a product page (R6-2, boards PdFull and PdRental): the ways this product can be
// tried before buying, read from the experience module by the product's stable id. The mall page
// only shows what this returns; it does not know experience items or venues.

// The three ways of the board, in its order. Software trials and course previews are not 先体验
// of a product on the board, so they get no pill.
export type ProductExperienceWay = 'SIMULATION' | 'RECORDED_DEMO' | 'VENUE'

const WAY_LABELS: Record<ProductExperienceWay, string> = {
  SIMULATION: '虚拟仿真',
  RECORDED_DEMO: '录播演示',
  VENUE: '到馆体验',
}

export interface ProductExperiencePill {
  way: ProductExperienceWay
  label: string
  // The experience item or the venue the pill opens.
  targetId: string
  // The first pill is drawn green and bold, as on the board.
  highlighted: boolean
  // What the 选择体验方式 sheet (board ExpPick) says: the item's title or the venue and its device,
  // and the item's short line.
  title: string
  meta: string | null
}

export interface ProductExperienceView {
  pills: ProductExperiencePill[]
  // "东营低空与机器人体验馆有这款设备" (board PdRental); null when no venue of the city has it.
  venueNote: string | null
  // The venue whose 馆内展示设备 the note links to.
  venueId: string | null
}

/** The block of a product, or null when there is no way to try it here (block and button hidden). */
export function toProductExperienceView(data: ProductExperiences): ProductExperienceView | null {
  const firstOf = (type: ExperienceType) => data.experiences.find((item) => item.type === type) ?? null
  const simulation = firstOf('SIMULATION')
  const demo = firstOf('RECORDED_DEMO')
  const venue = data.venues[0] ?? null
  const targets: Array<[ProductExperienceWay, string | null, string, string | null]> = [
    ['SIMULATION', simulation?.experienceId ?? null, simulation?.title ?? '', simulation?.summary ?? null],
    ['RECORDED_DEMO', demo?.experienceId ?? null, demo?.title ?? '', demo?.summary ?? null],
    ['VENUE', venue?.venueId ?? null, venue ? `${venue.name} · ${venue.deviceName}` : '', null],
  ]
  const pills = targets
    .filter((entry): entry is [ProductExperienceWay, string, string, string | null] => entry[1] !== null)
    .map(([way, targetId, title, meta], index) => ({
      way, label: WAY_LABELS[way], targetId, highlighted: index === 0, title, meta,
    }))
  if (pills.length === 0) return null
  return {
    pills,
    venueNote: venue ? venueNoteOf(venue.name, data.venues.length) : null,
    venueId: venue?.venueId ?? null,
  }
}

function venueNoteOf(firstName: string, count: number): string {
  return count > 1 ? `${firstName}等 ${count} 处有这款设备` : `${firstName}有这款设备`
}

export type ProductExperienceAction =
  | { kind: 'NAVIGATE'; url: string }
  | { kind: 'NOTICE'; message: string }
  | { kind: 'NONE' }

/**
 * What a way does: a venue opens its page (到馆预约 is built); an online experience goes through the
 * experience gate, which opens the simulation (S5-4) and explains the recorded demo on preview builds.
 */
export function resolveProductExperiencePill(
  pill: Pick<ProductExperiencePill, 'way' | 'targetId'>,
  cityCode: string,
  previewEnabled: boolean,
): ProductExperienceAction {
  if (pill.way === 'VENUE') return { kind: 'NAVIGATE', url: buildVenueDetailUrl(pill.targetId, cityCode) }
  const resolution = resolveExperienceIntent(
    { type: 'OPEN_EXPERIENCE', experienceId: pill.targetId, experienceType: pill.way },
    previewEnabled,
  )
  if (resolution.action === 'NAVIGATE') return { kind: 'NAVIGATE', url: resolution.url }
  return resolution.action === 'SHOW_NOTICE' ? { kind: 'NOTICE', message: resolution.message } : { kind: 'NONE' }
}

/**
 * Board PdFull: 虚拟仿真 opens the task straight away; 录播演示 and 到馆体验 open the 选择体验方式 sheet
 * (board ExpPick), which says what each way is before the user goes.
 */
export function pillOpensPicker(pill: Pick<ProductExperiencePill, 'way'>): boolean {
  return pill.way !== 'SIMULATION'
}

/** 馆内展示设备 of the venue in the note. */
export function productExperienceVenueDevicesUrl(view: ProductExperienceView, cityCode: string): string | null {
  return view.venueId ? buildVenueDevicesUrl(view.venueId, cityCode) : null
}

/**
 * Reads the block of a product. Any failure hides the block: trying first is an extra, the product
 * page works without it.
 */
export async function loadProductExperienceView(
  api: Pick<ExperienceApi, 'loadProductExperiences'>,
  productId: string,
  cityCode: string,
): Promise<ProductExperienceView | null> {
  if (!productId || !cityCode) return null
  try {
    return toProductExperienceView(await api.loadProductExperiences(productId, cityCode))
  } catch {
    return null
  }
}
