import { defineStore } from 'pinia'

import { DEFAULT_PLATFORM_CHANNEL, type PlatformChannelCode } from '../../features/platform/domain/platform-channel'
import {
  DEFAULT_HOME_CHANNEL,
  type HomeChannelCode,
  type HomeMallCategory,
} from '../navigation/home-channels'

// A mall category tab or a mall keyword asked for from another page (the search pages' 按类目找
// and 在商城里筛选). The home page takes it the next time it is shown.
export interface HomeMallRequest {
  category: HomeMallCategory | null
  keyword: string
}

interface HomeChannelState {
  activeChannel: HomeChannelCode
  activeServiceType: PlatformChannelCode
  pendingMall: HomeMallRequest | null
}

export const useHomeChannelStore = defineStore('home-channel', {
  state: (): HomeChannelState => ({
    activeChannel: DEFAULT_HOME_CHANNEL,
    activeServiceType: DEFAULT_PLATFORM_CHANNEL,
    pendingMall: null,
  }),
  actions: {
    selectChannel(channel: HomeChannelCode): void {
      if (channel !== 'SERVICE') this.activeServiceType = DEFAULT_PLATFORM_CHANNEL
      this.activeChannel = channel
    },
    requestMall(request: HomeMallRequest): void {
      this.selectChannel('MALL')
      this.pendingMall = request
    },
    takePendingMall(): HomeMallRequest | null {
      const request = this.pendingMall
      this.pendingMall = null
      return request
    },
  },
})
