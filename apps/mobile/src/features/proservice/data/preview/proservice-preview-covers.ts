import { PROSERVICE_PREVIEW_ENABLED } from '../../application/proservice-preview'
import type { ProServiceCategory, ProServiceType } from '../../domain/proservice'

// Pictures for the sample services of the development seed (R__dev_proservice_catalog.sql). They
// are the 2026-11-14 boards' pictures (SvcCatalog cards, SvcAerial / SvcSurvey / SvcMaint /
// SvcTech heroes) and only stand in until services carry licensed pictures of their own; a cover
// the server returns always wins, and public builds get none of them.
const DIRECTORY = '/static/images/preview'

// By scene: the SvcCatalog card of each scene.
const BY_CATEGORY: Partial<Record<`${ProServiceType}:${ProServiceCategory}`, string>> = {
  'AERIAL:ENGINEERING': 'proservice-park-aerial.webp',
  'AERIAL:ENERGY': 'proservice-solar.webp',
  'AERIAL:TOURISM': 'proservice-drone-city.jpg',
  'AERIAL:EVENT': 'proservice-event.jpg',
  'SURVEY:MODELING': 'proservice-survey-kit.jpg',
  'SURVEY:PARK': 'proservice-park-aerial.webp',
  'SURVEY:TERRAIN': 'proservice-solar.webp',
  'SURVEY:CORRIDOR': 'proservice-corridor.webp',
  'MAINTENANCE:GENERAL': 'proservice-drone-parts.jpg',
  'MAINTENANCE:DRONE': 'proservice-drone-city.jpg',
  'MAINTENANCE:ROBOT': 'proservice-robot-care.jpg',
  'MAINTENANCE:ROBOT_DOG': 'proservice-robot-dog-check.webp',
  'TECH_SUPPORT:GENERAL': 'proservice-tech-robot.jpg',
  'TECH_SUPPORT:FLIGHT': 'proservice-flight-sim.jpg',
  'TECH_SUPPORT:EVENT': 'proservice-event-robot.jpg',
  'TECH_SUPPORT:SOFTWARE': 'proservice-software.jpg',
}

// The SvcAerial board heads the page with the drone over the city instead of its card picture.
const HERO_BY_CATEGORY: Partial<Record<`${ProServiceType}:${ProServiceCategory}`, string>> = {
  'AERIAL:ENGINEERING': 'proservice-drone-city.jpg',
}

// Any other scene of a type: the type's own board picture.
const BY_TYPE: Record<ProServiceType, string> = {
  AERIAL: 'proservice-drone-city.jpg',
  SURVEY: 'proservice-survey-kit.jpg',
  MAINTENANCE: 'proservice-drone-parts.jpg',
  TECH_SUPPORT: 'proservice-tech-robot.jpg',
}

export function resolveProServicePreviewCover(
  type: ProServiceType,
  category: ProServiceCategory,
  hero = false,
  previewEnabled: boolean = PROSERVICE_PREVIEW_ENABLED,
): string | null {
  if (!previewEnabled) return null
  const key = `${type}:${category}` as const
  const name = (hero ? HERO_BY_CATEGORY[key] : undefined) ?? BY_CATEGORY[key] ?? BY_TYPE[type]
  return `${DIRECTORY}/${name}`
}

// The inspection services on a provider page carry only their category's name, no picture. They
// take the inspection catalog's own photos (shipped with the inspection pages); the scenes it has
// no photo for take a board picture in preview builds and stay grey elsewhere.
const INSPECTION_DIRECTORY = '/static/images/inspection'
const INSPECTION_BY_CATEGORY_NAME: Record<string, string> = {
  电力: 'service-power-line.webp',
  光伏: 'service-photovoltaic.webp',
  油气: 'service-oil-gas.webp',
  园区: 'service-park.webp',
}
const INSPECTION_PREVIEW_BY_CATEGORY_NAME: Record<string, string> = {
  热力: 'proservice-inspection-heat.webp',
  变电站: 'proservice-inspection-equipment.webp',
  电缆: 'proservice-inspection-equipment.webp',
  配电网: 'proservice-inspection-equipment.webp',
  设备: 'proservice-inspection-equipment.webp',
}

export function inspectionServicePicture(
  categoryName: string,
  previewEnabled: boolean = PROSERVICE_PREVIEW_ENABLED,
): string | null {
  const own = INSPECTION_BY_CATEGORY_NAME[categoryName]
  if (own) return `${INSPECTION_DIRECTORY}/${own}`
  if (!previewEnabled) return null
  return `${DIRECTORY}/${INSPECTION_PREVIEW_BY_CATEGORY_NAME[categoryName] ?? 'proservice-park-aerial.webp'}`
}
