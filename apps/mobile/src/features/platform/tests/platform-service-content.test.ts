import { describe, expect, it } from 'vitest'

import loaderSource from '../application/load-platform-service-content.ts?raw'
import { loadPlatformServiceContent } from '../application/load-platform-service-content'
import { developmentPlatformContentRepository } from '../data/development/development-platform-content-repository'

describe('platform service development content', () => {
  it('provides typed aerial, flight and robot preview content', async () => {
    const aerial = await developmentPlatformContentRepository.getServiceChannel('AERIAL')
    const flight = await developmentPlatformContentRepository.getServiceChannel('FLIGHT')
    const robot = await developmentPlatformContentRepository.getServiceChannel('ROBOT')

    expect(aerial).toMatchObject({
      code: 'AERIAL',
      hero: {
        title: '专业航拍',
        highlight: '记录每一种精彩',
      },
    })
    expect(flight).toMatchObject({
      code: 'FLIGHT',
      hero: {
        title: '放心飞 安全飞',
        highlight: '合规飞',
      },
    })
    expect(robot).toMatchObject({
      code: 'ROBOT',
      hero: {
        title: '机器人服务',
        highlight: '让生活更智能',
      },
    })
    expect(aerial?.scenarios).toHaveLength(8)
    expect(flight?.scenarios).toHaveLength(8)
    expect(robot?.scenarios).toHaveLength(8)
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
