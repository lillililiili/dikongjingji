import { describe, expect, it } from 'vitest'

import {
  DEFAULT_PLATFORM_CHANNEL,
  PLATFORM_SERVICE_CHANNELS,
} from '../domain/platform-channel'
import type { PlatformFeatureIntent } from '../domain/platform-feature-intent'
import {
  createPlatformFeatureGateway,
  type PlatformFeatureAvailability,
} from '../application/platform-feature-gateway'

describe('platform service channels', () => {
  it('opens aerial photography by default and keeps the approved channel order', () => {
    expect(DEFAULT_PLATFORM_CHANNEL).toBe('AERIAL')
    expect(PLATFORM_SERVICE_CHANNELS.map((channel) => channel.code)).toEqual([
      'AERIAL',
      'INSPECTION',
      'FLIGHT',
      'ROBOT',
      'MORE',
    ])
    expect(PLATFORM_SERVICE_CHANNELS.map((channel) => channel.label)).toEqual([
      '我要航拍',
      '我要巡检',
      '飞行服务',
      '机器人服务',
      '更多服务',
    ])
  })
})

describe('platform feature gateway', () => {
  const intent: PlatformFeatureIntent = {
    type: 'OPEN_MORE_SERVICES',
  }

  it('executes an enabled feature', () => {
    const availability: PlatformFeatureAvailability = {
      OPEN_MORE_SERVICES: 'ENABLED',
    }
    expect(createPlatformFeatureGateway(availability).resolve(intent)).toEqual({
      action: 'EXECUTE',
      intent,
    })
  })

  it('shows the next-phase message for a development-only feature', () => {
    const availability: PlatformFeatureAvailability = {
      OPEN_MORE_SERVICES: 'NEXT_PHASE',
    }
    expect(createPlatformFeatureGateway(availability).resolve(intent)).toEqual({
      action: 'SHOW_NEXT_PHASE',
      intent,
      message: '该功能将在下一阶段接入',
    })
  })

  it('hides a closed production feature', () => {
    const availability: PlatformFeatureAvailability = {
      OPEN_MORE_SERVICES: 'HIDDEN',
    }
    expect(createPlatformFeatureGateway(availability).resolve(intent)).toEqual({
      action: 'HIDE',
      intent,
    })
  })
})
