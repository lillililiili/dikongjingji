import { useHomeChannelStore } from '../state/home-channel'
import type { HomeChannelCode, HomeMallCategory } from './home-channels'
import { HOME_ROOT_PATH } from './root-tabs'

// uni.switchTab drops query parameters, so the target channel travels through the store.
export function openHomeChannel(channel: HomeChannelCode): void {
  useHomeChannelStore().selectChannel(channel)
  uni.switchTab({ url: HOME_ROOT_PATH })
}

// The mall channel on one category tab, or searching one keyword with its own filters and paging.
export function openHomeMall(request: { category?: HomeMallCategory; keyword?: string }): void {
  useHomeChannelStore().requestMall({ category: request.category ?? null, keyword: request.keyword?.trim() ?? '' })
  uni.switchTab({ url: HOME_ROOT_PATH })
}
