import { describe, expect, it } from 'vitest'

import loaderSource from '../application/load-platform-service-content.ts?raw'
import { loadPlatformServiceContent } from '../application/load-platform-service-content'
import { developmentPlatformContentRepository } from '../data/development/development-platform-content-repository'

describe('platform service development content', () => {
  it('provides the entry copy of the four types that only quote per project', async () => {
    const aerial = await developmentPlatformContentRepository.getServiceChannel('AERIAL')

    expect(aerial).toMatchObject({
      code: 'AERIAL',
      searchPlaceholder: '搜索航拍服务、场景或关键词',
      guarantees: ['资质审核', '持证飞手', '成果可追溯'],
    })
    expect(aerial?.processSteps.map((step) => step.title))
      .toEqual(['发布需求', '比选报价', '服务履约', '成果验收'])
  })

  // No flying in maintenance or support, and support does not always end in a report.
  it('does not promise licensed pilots where nothing flies', async () => {
    const maintenance = await developmentPlatformContentRepository.getServiceChannel('MAINTENANCE')
    const support = await developmentPlatformContentRepository.getServiceChannel('SUPPORT')

    expect(maintenance?.guarantees).toEqual(['资质审核', '服务记录', '报告可追溯'])
    expect(support?.guarantees).toEqual(['资质审核', '服务记录', '报价留档'])
  })

  it('has no entry copy for inspection, which has its own portal', async () => {
    await expect(developmentPlatformContentRepository.getServiceChannel('INSPECTION'))
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
