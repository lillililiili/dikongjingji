export const PLATFORM_SERVICE_CHANNELS = [
  { code: 'AERIAL', label: '我要航拍' },
  { code: 'INSPECTION', label: '我要巡检' },
  { code: 'FLIGHT', label: '飞行服务' },
  { code: 'ROBOT', label: '机器人服务' },
  { code: 'MORE', label: '更多服务' },
] as const

export type PlatformChannelCode =
  (typeof PLATFORM_SERVICE_CHANNELS)[number]['code']

export const DEFAULT_PLATFORM_CHANNEL: PlatformChannelCode = 'AERIAL'
