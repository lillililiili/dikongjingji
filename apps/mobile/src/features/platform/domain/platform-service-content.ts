import type { ProServiceType } from '@/features/proservice/domain/proservice'

import type { PlatformChannelCode } from './platform-channel'

export type PlatformProServiceChannel = Exclude<PlatformChannelCode, 'INSPECTION'>

// The fixed copy of the four types on the 专业服务 channel (HomeSvc board): what the search box
// says and the flow. The services themselves come from the catalog API (F2).
export interface PlatformServiceChannelContent {
  code: PlatformProServiceChannel
  serviceType: ProServiceType
  searchPlaceholder: string
  processSteps: readonly PlatformServiceProcessStep[]
}

export type PlatformServiceProcessStepCode = 'REQUEST' | 'QUOTE' | 'EXECUTE' | 'ACCEPT'

export interface PlatformServiceProcessStep {
  code: PlatformServiceProcessStepCode
  title: string
}

// These four types have no booking path: the flow starts with a published request and ends with
// accepting the result.
const REQUEST_FLOW: readonly PlatformServiceProcessStep[] = [
  { code: 'REQUEST', title: '发布需求' },
  { code: 'QUOTE', title: '比选报价' },
  { code: 'EXECUTE', title: '服务履约' },
  { code: 'ACCEPT', title: '成果验收' },
]

export const PLATFORM_SERVICE_CHANNEL_CONTENT: Record<PlatformProServiceChannel, PlatformServiceChannelContent> = {
  AERIAL: {
    code: 'AERIAL',
    serviceType: 'AERIAL',
    searchPlaceholder: '搜索航拍服务、场景或关键词',
    processSteps: REQUEST_FLOW,
  },
  SURVEY: {
    code: 'SURVEY',
    serviceType: 'SURVEY',
    searchPlaceholder: '搜索测绘服务、场景或关键词',
    processSteps: REQUEST_FLOW,
  },
  MAINTENANCE: {
    code: 'MAINTENANCE',
    serviceType: 'MAINTENANCE',
    searchPlaceholder: '搜索设备维护服务、场景或关键词',
    processSteps: REQUEST_FLOW,
  },
  SUPPORT: {
    code: 'SUPPORT',
    serviceType: 'TECH_SUPPORT',
    searchPlaceholder: '搜索技术支持服务、场景或关键词',
    processSteps: REQUEST_FLOW,
  },
}

// How many services of a type the channel shows before 查看全部服务.
export const PLATFORM_SERVICE_PREVIEW_SIZE = 3
