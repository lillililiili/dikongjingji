import type {
  PlatformFeatureIntent,
  PlatformFeatureIntentType,
} from '../domain/platform-feature-intent'

export type PlatformFeatureStatus = 'ENABLED' | 'NEXT_PHASE' | 'HIDDEN'

export type PlatformFeatureAvailability =
  Partial<Record<PlatformFeatureIntentType, PlatformFeatureStatus>>

export type PlatformFeatureResolution =
  | { action: 'EXECUTE'; intent: PlatformFeatureIntent }
  | {
    action: 'SHOW_NEXT_PHASE'
    intent: PlatformFeatureIntent
    message: '该功能将在下一阶段接入'
  }
  | { action: 'HIDE'; intent: PlatformFeatureIntent }

export interface PlatformFeatureGateway {
  resolve(intent: PlatformFeatureIntent): PlatformFeatureResolution
}
export function createPlatformFeatureGateway(
  availability: PlatformFeatureAvailability,
): PlatformFeatureGateway {
  return {
    resolve(intent) {
      const status = availability[intent.type] ?? 'HIDDEN'
      if (status === 'ENABLED') return { action: 'EXECUTE', intent }
      if (status === 'NEXT_PHASE') {
        return {
          action: 'SHOW_NEXT_PHASE',
          intent,
          message: '该功能将在下一阶段接入',
        }
      }
      return { action: 'HIDE', intent }
    },
  }
}
