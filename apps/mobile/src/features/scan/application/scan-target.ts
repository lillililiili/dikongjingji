/**
 * What a scanned code means (board Scan): a platform link opens its page inside the App; a
 * check-in code (an enrolment ticket ACT-… or a venue booking VEN-…) is for the staff at the door;
 * anything else is not opened, for safety (no outside links, no arbitrary routes).
 *
 * A platform link is the address 分享 copies (shared/share/share-link.ts): the platform's own web
 * address with a hash route, or the route itself. Only the detail pages below are opened, with
 * only their own parameters; the page then reads the object and says so itself when it is gone.
 */
export type ScanTarget =
  | { kind: 'PAGE'; url: string; subject: string }
  | { kind: 'CHECK_IN_CODE' }
  | { kind: 'UNKNOWN' }

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const CITY = /^[A-Z0-9]{4,16}$/
const CHECK_IN_CODE = /^(ACT|VEN)-[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$/

// Path → its id parameter and what it is.
const PAGES: Record<string, { id: string; subject: string }> = {
  '/pages/mall/product': { id: 'productId', subject: '商品' },
  '/pages/activity/detail': { id: 'activityId', subject: '活动' },
  '/pages/venue/detail': { id: 'venueId', subject: '场馆' },
  '/pages/service/inspection/detail': { id: 'serviceId', subject: '服务' },
  '/pages/proservice/detail': { id: 'serviceId', subject: '服务' },
  '/pages/provider/detail': { id: 'providerId', subject: '服务商' },
  '/pages/content/article': { id: 'articleId', subject: '内容' },
  '/pages/content/topic': { id: 'topicId', subject: '专题' },
  '/pages/content/video': { id: 'videoId', subject: '视频' },
  '/pages/live/room': { id: 'roomId', subject: '直播' },
  '/pages/live/notice': { id: 'roomId', subject: '直播' },
}

function originOf(url: string): string | null {
  const match = /^(https?:\/\/[^/?#]+)/i.exec(url)
  return match ? match[1].toLowerCase() : null
}

function readQuery(query: string): Map<string, string> {
  const values = new Map<string, string>()
  for (const part of query.split('&')) {
    const at = part.indexOf('=')
    if (at <= 0) continue
    try {
      values.set(decodeURIComponent(part.slice(0, at)), decodeURIComponent(part.slice(at + 1)).trim())
    } catch {
      // A broken parameter is left out.
    }
  }
  return values
}

/**
 * @param platformOrigins the web addresses that are the platform's own (the share base and, in a
 *   browser, this site); links elsewhere are never opened.
 */
export function parseScanText(text: string, platformOrigins: readonly string[]): ScanTarget {
  const value = text.trim()
  if (!value || value.length > 600) return { kind: 'UNKNOWN' }
  if (CHECK_IN_CODE.test(value.toUpperCase())) return { kind: 'CHECK_IN_CODE' }

  let route = value
  const origin = originOf(value)
  if (origin) {
    if (!platformOrigins.map((item) => item.toLowerCase()).includes(origin)) return { kind: 'UNKNOWN' }
    const hash = value.indexOf('#')
    if (hash < 0) return { kind: 'UNKNOWN' }
    route = value.slice(hash + 1)
  }
  if (!route.startsWith('/pages/')) return { kind: 'UNKNOWN' }
  const [path, query = ''] = route.split('?')
  const page = PAGES[path]
  if (!page) return { kind: 'UNKNOWN' }
  const values = readQuery(query)
  const id = values.get(page.id) ?? ''
  if (!UUID.test(id)) return { kind: 'UNKNOWN' }
  const city = values.get('cityCode') ?? ''
  const parameters = [`${page.id}=${encodeURIComponent(id)}`]
  if (CITY.test(city)) parameters.push(`cityCode=${encodeURIComponent(city)}`)
  return { kind: 'PAGE', url: `${path}?${parameters.join('&')}`, subject: page.subject }
}

// The platform's own web addresses: the share base (VITE_SHARE_BASE_URL) and, in a browser, this site.
export function platformOrigins(): string[] {
  const origins: string[] = []
  const base = (import.meta.env.VITE_SHARE_BASE_URL as string | undefined)?.trim()
  const fromBase = base ? originOf(base) : null
  if (fromBase) origins.push(fromBase)
  if (typeof window !== 'undefined' && window.location && /^https?:/.test(window.location.origin)) {
    origins.push(window.location.origin.toLowerCase())
  }
  return origins
}
