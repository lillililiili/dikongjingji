import type { PlatformChannelCode } from '../../domain/platform-channel'
import type { PlatformContentRepository } from '../../domain/platform-content-repository'
import type { PlatformServiceChannelContent } from '../../domain/platform-service-content'
import { DEVELOPMENT_PLATFORM_SERVICE_CONTENT } from './platform-service.fixture'

export const developmentPlatformContentRepository: PlatformContentRepository = {
  async getServiceChannel(
    channel: PlatformChannelCode,
  ): Promise<PlatformServiceChannelContent | null> {
    return DEVELOPMENT_PLATFORM_SERVICE_CONTENT.find(
      (content) => content.code === channel,
    ) ?? null
  },
}
