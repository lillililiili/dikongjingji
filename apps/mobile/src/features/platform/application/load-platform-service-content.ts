import type { PlatformChannelCode } from '../domain/platform-channel'
import type { PlatformContentRepository } from '../domain/platform-content-repository'
import type { PlatformServiceChannelContent } from '../domain/platform-service-content'

export type PlatformServiceContentResult =
  | { kind: 'ready'; content: PlatformServiceChannelContent }
  | { kind: 'unavailable'; channel: PlatformChannelCode }

export async function loadPlatformServiceContent(
  channel: PlatformChannelCode,
  repository: PlatformContentRepository | null,
): Promise<PlatformServiceContentResult> {
  if (!repository) return { kind: 'unavailable', channel }
  const content = await repository.getServiceChannel(channel)
  return content
    ? { kind: 'ready', content }
    : { kind: 'unavailable', channel }
}
