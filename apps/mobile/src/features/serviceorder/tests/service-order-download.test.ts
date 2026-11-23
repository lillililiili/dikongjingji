import { describe, expect, it } from 'vitest'

import { resolveServiceOrderDownloadUrl } from '../application/service-order-download'

const resolve = async (path: string) => `https://api.example.test${path}`

describe('service order file links (§28.2 rule 8)', () => {
  it('puts the short-lived link after the API address', async () => {
    await expect(resolveServiceOrderDownloadUrl({ url: '/api/v1/service-order-files/abc', expiresAt: 'x' }, resolve))
      .resolves.toBe('https://api.example.test/api/v1/service-order-files/abc')
  })

  it('refuses anything that is not a service-order file link', async () => {
    for (const url of ['', '/api/v1/service-order-files/', 'https://minio.local/bucket/a.pdf', '/api/v1/other/abc']) {
      await expect(resolveServiceOrderDownloadUrl({ url, expiresAt: 'x' }, resolve)).rejects.toThrow('下载链接无效')
    }
  })
})
