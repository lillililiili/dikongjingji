// The link 分享 copies (board ShareSheet): the address of the page being looked at, carrying only
// stable ids and the city, never anything about the person sharing it.
//
// In the browser build that is this site with the hash route; elsewhere (the App) the public web
// address comes from VITE_SHARE_BASE_URL, and without one the route itself is copied.
export function buildShareLink(path: string, location: { origin?: string; pathname?: string } | null = currentLocation()): string {
  const route = path.startsWith('/') ? path : `/${path}`
  if (location?.origin && /^https?:\/\//.test(location.origin)) {
    return `${location.origin}${location.pathname || '/'}#${route}`
  }
  const base = (import.meta.env.VITE_SHARE_BASE_URL as string | undefined)?.trim().replace(/\/+$/, '')
  return base ? `${base}/#${route}` : route
}

function currentLocation(): { origin?: string; pathname?: string } | null {
  return typeof window !== 'undefined' && window.location ? window.location : null
}

export type ShareSubject = '商品' | '活动' | '场馆' | '服务' | '服务商' | '内容' | '专题' | '直播'

export interface ShareTarget {
  subject: ShareSubject
  // The object's own title and one line under it, as the page shows them.
  title: string
  subtitle: string | null
  // Who offers it (merchant, organiser, provider), when the page knows.
  owner: string | null
  imageUrl: string | null
  // The page's route with its stable ids, e.g. /pages/mall/product?productId=…&cityCode=….
  path: string
}

/**
 * The line under the title. A product adds where it is sold ("¥1,299 起 · 东营可售", board
 * ShareSheet) when the link's city is the App's current city, so the name is known for certain.
 */
export function describeShareSubtitle(
  target: Pick<ShareTarget, 'subject' | 'subtitle' | 'path'>,
  currentCity: { code: string; name: string } | null | undefined,
): string | null {
  const base = target.subtitle?.trim() || null
  if (target.subject !== '商品' || !currentCity) return base
  const cityCode = /[?&]cityCode=([^&#]+)/.exec(target.path)?.[1]
  if (!cityCode || decodeURIComponent(cityCode) !== currentCity.code) return base
  const name = currentCity.name.trim().replace(/市$/, '')
  if (!name) return base
  return base ? `${base} · ${name}可售` : `${name}可售`
}
