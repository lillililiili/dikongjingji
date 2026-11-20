import cableImageUrl from '../presentation/catalog/assets/service-cable.png'
import distributionImageUrl from '../presentation/catalog/assets/service-distribution.png'
import substationImageUrl from '../presentation/catalog/assets/service-substation.png'

// One picture per inspection category, shared by the catalog card, the detail head and every
// later page that shows the service (booking, submitted, plan, fulfilment, order card). The
// pictures are the ones the 2026-11-14 boards use (InspCatalog, InspDetail*, OrderList). A cover
// sent by the server always wins; these only fill the gap while services have no cover.

const POWER_IMAGE = '/static/images/inspection/service-power-line.webp'

const SERVICE_IMAGE_BY_CATEGORY: Record<string, string> = {
  POWER: POWER_IMAGE,
  SUBSTATION: substationImageUrl,
  DISTRIBUTION: distributionImageUrl,
  CABLE: cableImageUrl,
  PHOTOVOLTAIC: '/static/images/inspection/service-photovoltaic.webp',
  OIL_GAS: '/static/images/inspection/service-oil-gas.webp',
  THERMAL: '/static/images/inspection/category-thermal.webp',
  PARK: '/static/images/inspection/service-park.webp',
  EQUIPMENT: '/static/images/inspection/category-equipment.webp',
  PERIODIC: '/static/images/inspection/category-periodic.webp',
}

/** The picture of a category; unknown categories fall back to the power-line picture. */
export function inspectionCategoryImage(categoryCode: string | null | undefined): string {
  return (categoryCode && SERVICE_IMAGE_BY_CATEGORY[categoryCode]) || POWER_IMAGE
}

// Reservation, plan and order snapshots carry the service title but no category code, so the
// category is read from the title (or a category name) by these words. Order matters: 变电站,
// 配电网 and 电缆 before the general 电力 words.
const CATEGORY_WORDS: ReadonlyArray<readonly [string, readonly string[]]> = [
  ['SUBSTATION', ['变电站']],
  ['DISTRIBUTION', ['配电网', '配电']],
  ['CABLE', ['电缆']],
  ['PHOTOVOLTAIC', ['光伏']],
  ['OIL_GAS', ['油气', '燃气', '管线', '管道']],
  ['THERMAL', ['热力', '供热', '热网']],
  ['PARK', ['园区', '安防']],
  ['POWER', ['电力', '输电', '线路', '杆塔']],
]

export function inspectionCategoryFromText(text: string | null | undefined): string | null {
  if (!text) return null
  const match = CATEGORY_WORDS.find(([, words]) => words.some((word) => text.includes(word)))
  return match ? match[0] : null
}

/**
 * The picture for a service wherever it is shown: the server cover first, then the category
 * picture (from the code, or else from the category name or the service title).
 */
export function inspectionServiceImage(service: {
  coverUrl?: string | null
  categoryCode?: string | null
  categoryName?: string | null
  title?: string | null
}): string {
  if (service.coverUrl) return service.coverUrl
  const code = service.categoryCode
    || inspectionCategoryFromText(service.categoryName)
    || inspectionCategoryFromText(service.title)
  return inspectionCategoryImage(code)
}

// The detail head is 390×200 and uses the card pictures, except 热力: board InspDetailHeat puts the
// park drawing there rather than the small thermal drawing of the catalog card.
const DETAIL_HERO_BY_CATEGORY: Record<string, string> = {
  THERMAL: '/static/images/inspection/category-park.webp',
}

export function inspectionDetailHeroImage(categoryCode: string | null | undefined): string {
  return (categoryCode && DETAIL_HERO_BY_CATEGORY[categoryCode]) || inspectionCategoryImage(categoryCode)
}
