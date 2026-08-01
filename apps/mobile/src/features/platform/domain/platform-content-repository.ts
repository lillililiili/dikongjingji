import type { PlatformChannelCode } from './platform-channel'
import type { PlatformServiceChannelContent } from './platform-service-content'

export interface PlatformContentRepository {
  getServiceChannel(
    channel: PlatformChannelCode,
  ): Promise<PlatformServiceChannelContent | null>
}
