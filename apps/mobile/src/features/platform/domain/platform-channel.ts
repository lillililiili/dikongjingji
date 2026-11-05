export const PLATFORM_SERVICE_CHANNELS = [
  { code: 'INSPECTION', label: '巡检' },
  { code: 'AERIAL', label: '航拍' },
  { code: 'SURVEY', label: '测绘' },
  { code: 'MAINTENANCE', label: '设备维护' },
  { code: 'SUPPORT', label: '技术支持' },
] as const

export type PlatformChannelCode =
  (typeof PLATFORM_SERVICE_CHANNELS)[number]['code']

export const DEFAULT_PLATFORM_CHANNEL: PlatformChannelCode = 'INSPECTION'

export function resolveVisiblePlatformChannels(
  previewEnabled: boolean,
): readonly PlatformChannelCode[] {
  return previewEnabled
    ? PLATFORM_SERVICE_CHANNELS.map((channel) => channel.code)
    : [DEFAULT_PLATFORM_CHANNEL]
}
