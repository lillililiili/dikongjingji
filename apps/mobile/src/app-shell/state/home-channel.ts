import { defineStore } from 'pinia'

import {
  DEFAULT_HOME_CHANNEL,
  type HomeChannelCode,
} from '../navigation/home-channels'

interface HomeChannelState {
  activeChannel: HomeChannelCode
}

export const useHomeChannelStore = defineStore('home-channel', {
  state: (): HomeChannelState => ({
    activeChannel: DEFAULT_HOME_CHANNEL,
  }),
  actions: {
    selectChannel(channel: HomeChannelCode): void {
      this.activeChannel = channel
    },
  },
})
