import type { ServiceOrderDownloadIntent } from '../domain/service-order'

// A delivered file is only ever opened through the short-lived link the server hands out (§28.2
// rule 8): a relative path with a one-time token, put after the API address. Anything else is
// refused, so no stored address can reach the page.
export const SERVICE_ORDER_FILE_LINK_PREFIX = '/api/v1/service-order-files/'

export async function resolveServiceOrderDownloadUrl(
  intent: ServiceOrderDownloadIntent,
  resolveApiUrl: (path: string) => Promise<string>,
): Promise<string> {
  const path = intent.url.trim()
  if (!path.startsWith(SERVICE_ORDER_FILE_LINK_PREFIX) || path.length === SERVICE_ORDER_FILE_LINK_PREFIX.length) {
    throw new Error('下载链接无效，请重新获取')
  }
  return resolveApiUrl(path)
}
