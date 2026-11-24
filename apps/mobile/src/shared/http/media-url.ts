// Which picture addresses may reach an <image>, and the one exception for the on-site demo.
//
// Everywhere else the rule is AGENTS.md §11: HTTPS only. The on-site demo has no network and no
// trusted certificate, so the demo server hands out addresses under a made-up HTTPS host, and a demo
// build (preview build + VITE_DEMO_MEDIA_REWRITE=true) reads them from the laptop on the local
// network instead. Without that switch the host is never looked at and only HTTPS passes.
export const DEMO_MEDIA_ORIGIN = 'https://media.demo.localhost'

const REWRITE = (import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__) && import.meta.env.VITE_DEMO_MEDIA_REWRITE === 'true'

let demoOrigin: string | null = null

export function isMediaUrl(url: string | null | undefined): url is string {
  if (!url) return false
  return /^https:\/\/[^/\s]+/i.test(url) || (demoOrigin !== null && url.startsWith(`${demoOrigin}/`))
}

/** A JSON response with the demo host replaced by the API address it came from; the response itself otherwise. */
export function rewriteDemoMedia<T>(data: T, apiUrl: string, enabled: boolean = REWRITE): T {
  if (!enabled) return data
  const origin = /^https?:\/\/[^/]+/i.exec(apiUrl)?.[0]
  if (!origin) return data
  demoOrigin = origin
  return replaceStrings(data, origin) as T
}

function replaceStrings(value: unknown, origin: string): unknown {
  if (typeof value === 'string') {
    return value.startsWith(`${DEMO_MEDIA_ORIGIN}/`) ? origin + value.slice(DEMO_MEDIA_ORIGIN.length) : value
  }
  if (Array.isArray(value)) return value.map((item) => replaceStrings(item, origin))
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, replaceStrings(item, origin)]))
  }
  return value
}
