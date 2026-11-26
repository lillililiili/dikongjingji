// Keep development media on the same API that served the record, including LAN discovery on App.
import { isMediaUrl } from './media-url'

let activeApiBase = ''

export function setMediaApiBase(apiBase: string): void {
  activeApiBase = apiBase.replace(/\/$/, '')
}

const developmentPath = /^\/api\/v1\/dev-media\/(?:images\/(?:home|preview|inspection)\/[a-z0-9-]+\.(?:jpg|png|webp)|videos\/preview\/[a-z0-9-]+\.mp4)$/
const publishedCoverFile = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\.(?:jpg|png|webp)$/
const publishedCoverPrefix = '/api/v1/dev-media/ops/covers/'
const publishedVideoFile = /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\.mp4$/
const publishedVideoPrefix = '/api/v1/dev-media/ops/videos/'
const merchantMediaPath = /^\/api\/v1\/(?:mall|ops)\/media\/(?:[\w-]+\/)*[\w-]+\.(?:jpe?g|png|webp|mp4)$/

export function resolveBusinessMediaUrl(
  value: unknown,
  apiBase = activeApiBase || import.meta.env.VITE_INSPECTION_API_BASE_URL || '',
  preview = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__,
): string | null {
  if (typeof value !== 'string') return null
  const url = value.trim()
  if (!url || /[\s\\]/.test(url)) return null
  const absolute = /^(https?:\/\/[^/?#@]+)(\/[^?#]*)?(?:[?#].*)?$/i.exec(url)
  const origin = apiBase.replace(/\/$/, '')
  const path = absolute ? absolute[2] || '/' : url
  if (absolute && /^https?:\/\/media\.dev\.localhost(?::\d+)?$/i.test(absolute[1])) {
    const filename = path.slice('/ops-media/'.length)
    return preview && origin && url === 'https://media.dev.localhost/ops-media/' + filename && publishedCoverFile.test(filename)
      ? origin + publishedCoverPrefix + filename : null
  }
  if (path.startsWith('/api/v1/dev-media/')) {
    const allowed = developmentPath.test(path)
      || (path.startsWith(publishedCoverPrefix) && publishedCoverFile.test(path.slice(publishedCoverPrefix.length)))
    return preview && allowed && origin && (!absolute || absolute[1] === origin)
      ? origin + path : null
  }
  // HTTPS, or an address the on-site demo build has already pointed at the demo laptop (media-url.ts).
  if (absolute && isMediaUrl(url)) return url
  if (origin && merchantMediaPath.test(path) && (!absolute || absolute[1] === origin)
    && (preview || /^https:\/\//i.test(origin))) return origin + path
  return null
}

export function businessImage(
  image: { url?: string | null; alt?: string | null } | null | undefined,
): { url: string; alt: string } | null {
  const url = resolveBusinessMediaUrl(image?.url)
  return url ? { url, alt: image?.alt ?? '' } : null
}

// Only a public discovery video's playUrl can select the endpoint for locally uploaded MP4s.
export function resolveDiscoveryVideoUrl(
  value: unknown,
  apiBase = activeApiBase || import.meta.env.VITE_INSPECTION_API_BASE_URL || '',
  preview = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__,
): string | null {
  if (typeof value !== 'string') return null
  const source = value.trim()
  const origin = apiBase.replace(/\/$/, '')
  for (const prefix of ['https://media.dev.localhost/ops-media/', publishedVideoPrefix, origin + publishedVideoPrefix]) {
    if (source.startsWith(prefix)) {
      const filename = source.slice(prefix.length)
      return preview && origin && publishedVideoFile.test(filename) ? origin + publishedVideoPrefix + filename : null
    }
  }
  return resolveBusinessMediaUrl(source, apiBase, preview)
}

export function isDevelopmentMediaUrl(url: string | null): boolean {
  return Boolean(url && /\/api\/v1\/dev-media\//.test(url) && resolveBusinessMediaUrl(url))
}
