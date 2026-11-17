import type { PlatformChannelCode } from './platform-channel'

// The service types other than inspection have no catalog yet: their entry page only says how the
// type is bought (publish a request, verified providers quote) and what the platform checks.
export interface PlatformServiceChannelContent {
  code: Exclude<PlatformChannelCode, 'INSPECTION'>
  searchPlaceholder: string
  guarantees: readonly string[]
  processSteps: readonly PlatformServiceProcessStep[]
}

export type PlatformServiceProcessStepCode = 'REQUEST' | 'QUOTE' | 'EXECUTE' | 'ACCEPT'

export interface PlatformServiceProcessStep {
  code: PlatformServiceProcessStepCode
  title: string
}
