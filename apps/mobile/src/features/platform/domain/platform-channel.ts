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

// The four other service types open with the PROSERVICE release switch (src/app-shell/release/release-features.json).
export function resolveVisiblePlatformChannels(
  previewEnabled: boolean,
): readonly PlatformChannelCode[] {
  return previewEnabled || __RELEASE_FEATURE_PROSERVICE__
    ? PLATFORM_SERVICE_CHANNELS.map((channel) => channel.code)
    : [DEFAULT_PLATFORM_CHANNEL]
}
