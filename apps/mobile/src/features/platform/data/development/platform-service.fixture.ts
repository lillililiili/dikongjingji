import type {
  PlatformServiceChannelContent,
  PlatformServiceProcessStep,
} from '../../domain/platform-service-content'

// Copy from the 2026-11-14 prototype board HomeSvc. These four types have no booking path: the flow
// starts with a published request and ends with accepting the result.
const REQUEST_FLOW: readonly PlatformServiceProcessStep[] = [
  { code: 'REQUEST', title: '发布需求' },
  { code: 'QUOTE', title: '比选报价' },
  { code: 'EXECUTE', title: '服务履约' },
  { code: 'ACCEPT', title: '成果验收' },
]

// Maintenance and support involve no flying, so they do not claim licensed pilots; support does
// not always end in a report, so it does not claim a traceable one.
export const DEVELOPMENT_PLATFORM_SERVICE_CONTENT = [
  {
    code: 'AERIAL',
    searchPlaceholder: '搜索航拍服务、场景或关键词',
    guarantees: ['资质审核', '持证飞手', '成果可追溯'],
    processSteps: REQUEST_FLOW,
  },
  {
    code: 'SURVEY',
    searchPlaceholder: '搜索测绘服务、场景或关键词',
    guarantees: ['资质审核', '持证飞手', '成果可追溯'],
    processSteps: REQUEST_FLOW,
  },
  {
    code: 'MAINTENANCE',
    searchPlaceholder: '搜索设备维护服务、场景或关键词',
    guarantees: ['资质审核', '服务记录', '报告可追溯'],
    processSteps: REQUEST_FLOW,
  },
  {
    code: 'SUPPORT',
    searchPlaceholder: '搜索技术支持服务、场景或关键词',
    guarantees: ['资质审核', '服务记录', '报价留档'],
    processSteps: REQUEST_FLOW,
  },
] satisfies PlatformServiceChannelContent[]
