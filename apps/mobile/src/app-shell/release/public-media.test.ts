import { afterEach, describe, expect, it, vi } from 'vitest'
import { isDevelopmentMediaUrl, resolveBusinessMediaUrl, resolveDiscoveryVideoUrl, setMediaApiBase } from '../../shared/http/business-media'

const base = 'http://127.0.0.1:18420'
const path = '/api/v1/dev-media/videos/preview/robot-coding.mp4'

afterEach(() => { setMediaApiBase(''); vi.unstubAllEnvs() })

describe('API-backed business media', () => {
  it('preserves a real HTTPS URL and never invents a source for missing media', () => {
    for (const preview of [true, false]) {
      expect(resolveBusinessMediaUrl('https://cdn.example.org/video.mp4?signature=abc', base, preview))
        .toBe('https://cdn.example.org/video.mp4?signature=abc')
      for (const bad of [null, '', 'f3200000-0000-4000-8000-000000000001', '/static/videos/preview/robot-coding.mp4', 'http://untrusted.example/a.jpg']) {
        expect(resolveBusinessMediaUrl(bad, base, preview)).toBeNull()
      }
    }
  })

  it('accepts only same-API development paths in previews and supports revalidating a resolved URL', () => {
    expect(resolveBusinessMediaUrl(path, base, true)).toBe(base + path)
    expect(resolveBusinessMediaUrl(base + path, base, true)).toBe(base + path)
    for (const bad of ['https://other.example' + path, path.replace('preview/', '../'), '/api/v1/dev-media/tokens']) {
      expect(resolveBusinessMediaUrl(bad, base, true)).toBeNull()
    }
    expect(resolveBusinessMediaUrl(path, base, false)).toBeNull()
    expect(resolveBusinessMediaUrl('https://api.example' + path, 'https://api.example', false)).toBeNull()
    vi.stubEnv('DEV', true)
    setMediaApiBase(base)
    expect(isDevelopmentMediaUrl(resolveBusinessMediaUrl(path))).toBe(true)
  })

  it('resolves a database-linked inspection image in a preview', () => {
    const report = '/api/v1/dev-media/images/inspection/detail-report-cover.png'
    expect(resolveBusinessMediaUrl(report, base, true)).toBe(base + report)
    expect(resolveBusinessMediaUrl(report, base, false)).toBeNull()
    expect(resolveBusinessMediaUrl(report.replace('inspection/', 'inspection/platform/'), base, true)).toBeNull()
  })

  it('resolves a local operator cover through the publication-checked endpoint', () => {
    const filename = '0b7c4c9e-1f7a-4c3e-9d55-2b3a4c5d6e7f.png'
    const legacy = 'https://media.dev.localhost/ops-media/' + filename
    const sameApi = '/api/v1/dev-media/ops/covers/' + filename
    for (const input of [legacy, sameApi, base + sameApi]) {
      expect(resolveBusinessMediaUrl(input, base, true)).toBe(base + sameApi)
      expect(resolveBusinessMediaUrl(input, base, false)).toBeNull()
    }
    for (const input of [legacy.replace('/ops-media/', '/private/'), legacy.replace('.png', '.mp4'),
      legacy + '?token=x', sameApi.replace(filename, 'draft.png'), 'https://other.example' + sameApi]) {
      expect(resolveBusinessMediaUrl(input, base, true)).toBeNull()
    }
    const hosted = 'https://cdn.example.org/ops-media/' + filename
    expect(resolveBusinessMediaUrl(hosted, base, false)).toBe(hosted)
  })

  it('uses the publication-checked MP4 route only for discovery video playback in a preview', () => {
    const filename = 'db1869ab-512b-40c7-b0c3-de57b99146fe.mp4'
    const stored = 'https://media.dev.localhost/ops-media/' + filename
    const route = '/api/v1/dev-media/ops/videos/' + filename
    for (const input of [stored, route, base + route]) {
      expect(resolveDiscoveryVideoUrl(input, base, true)).toBe(base + route)
      expect(resolveDiscoveryVideoUrl(input, base, false)).toBeNull()
      expect(resolveBusinessMediaUrl(input, base, true)).toBeNull()
    }
    for (const invalid of [stored + '?private=1', route.replace(filename, 'clip.mp4'), 'https://other.example' + route]) {
      expect(resolveDiscoveryVideoUrl(invalid, base, true)).toBeNull()
    }
    const hosted = 'https://cdn.example.org/clips/flight.mp4?signature=clip'
    expect(resolveDiscoveryVideoUrl(hosted, base, false)).toBe(hosted)
    expect(resolveDiscoveryVideoUrl(path, base, true)).toBe(base + path)
  })
})
