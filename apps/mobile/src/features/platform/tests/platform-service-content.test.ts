import { describe, expect, it } from 'vitest'

import loaderSource from '../application/load-platform-service-content.ts?raw'
import { loadPlatformServiceContent } from '../application/load-platform-service-content'
import { developmentPlatformContentRepository } from '../data/development/development-platform-content-repository'

describe('platform service development content', () => {
  it('provides typed aerial preview content', async () => {
    const aerial = await developmentPlatformContentRepository.getServiceChannel('AERIAL')

    expect(aerial).toMatchObject({
      code: 'AERIAL',
      hero: {
        title: '专业航拍',
        highlight: '记录每一种精彩',
      },
    })
    expect(aerial?.scenarios).toHaveLength(8)
  })

  it('has no preview content for service types that only quote per project', async () => {
    await expect(developmentPlatformContentRepository.getServiceChannel('SURVEY'))
      .resolves.toBeNull()
  })

  it('returns unavailable without a repository in production', async () => {
    await expect(loadPlatformServiceContent('AERIAL', null)).resolves.toEqual({
      kind: 'unavailable',
      channel: 'AERIAL',
    })
  })

  it('keeps the application loader independent from development fixtures', () => {
    expect(loaderSource).not.toContain('data/development')
    expect(loaderSource).not.toContain('fixture')
    expect(loaderSource).not.toContain('import.meta.env')
  })
})
