import { useHomeChannelStore } from '../state/home-channel'
import type { HomeChannelCode } from './home-channels'
import { HOME_ROOT_PATH } from './root-tabs'

// uni.switchTab drops query parameters, so the target channel travels through the store.
export function openHomeChannel(channel: HomeChannelCode): void {
  useHomeChannelStore().selectChannel(channel)
  uni.switchTab({ url: HOME_ROOT_PATH })
}
