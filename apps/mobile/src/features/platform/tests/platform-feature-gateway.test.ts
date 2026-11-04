import { describe, expect, it } from 'vitest'

import {
  DEFAULT_PLATFORM_CHANNEL,
  PLATFORM_SERVICE_CHANNELS,
  resolveVisiblePlatformChannels,
} from '../domain/platform-channel'
import type { PlatformFeatureIntent } from '../domain/platform-feature-intent'
import {
  createPlatformFeatureGateway,
  type PlatformFeatureAvailability,
} from '../application/platform-feature-gateway'

describe('platform service channels', () => {
  it('opens inspection by default and keeps the approved type order', () => {
    expect(DEFAULT_PLATFORM_CHANNEL).toBe('INSPECTION')
    expect(PLATFORM_SERVICE_CHANNELS.map((channel) => channel.code)).toEqual([
      'INSPECTION',
      'AERIAL',
      'SURVEY',
      'MAINTENANCE',
      'SUPPORT',
    ])
    expect(PLATFORM_SERVICE_CHANNELS.map((channel) => channel.label)).toEqual([
      '巡检',
      '航拍',
      '测绘',
      '设备维护',
      '技术支持',
    ])
  })

  it('keeps only inspection in public builds', () => {
    expect(resolveVisiblePlatformChannels(false)).toEqual(['INSPECTION'])
    expect(resolveVisiblePlatformChannels(true)).toHaveLength(5)
  })
})

describe('platform feature gateway', () => {
  const intent: PlatformFeatureIntent = {
    type: 'PUBLISH_DEMAND',
  }

  it('executes an enabled feature', () => {
    const availability: PlatformFeatureAvailability = {
      PUBLISH_DEMAND: 'ENABLED',
    }
    expect(createPlatformFeatureGateway(availability).resolve(intent)).toEqual({
      action: 'EXECUTE',
      intent,
    })
  })

  it('shows the next-phase message for a development-only feature', () => {
    const availability: PlatformFeatureAvailability = {
      PUBLISH_DEMAND: 'NEXT_PHASE',
    }
    expect(createPlatformFeatureGateway(availability).resolve(intent)).toEqual({
      action: 'SHOW_NEXT_PHASE',
      intent,
      message: '该功能将在下一阶段接入',
    })
  })

  it('hides a closed production feature', () => {
    const availability: PlatformFeatureAvailability = {
      PUBLISH_DEMAND: 'HIDDEN',
    }
    expect(createPlatformFeatureGateway(availability).resolve(intent)).toEqual({
      action: 'HIDE',
      intent,
    })
  })
})
