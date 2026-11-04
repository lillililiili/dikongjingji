import type { HomeChannelCode } from '@/app-shell/navigation/home-channels'

import type { PlatformChannelCode } from './platform-channel'

export type PlatformItemKind =
  | 'AERIAL_SERVICE'
  | 'ACTIVITY'
  | 'CONTENT'
  | 'PRODUCT'
  | 'SOLUTION'

export type PlatformFeatureIntent =
  | { type: 'SEARCH'; channel: PlatformChannelCode }
  | { type: 'HOME_SEARCH'; channel: HomeChannelCode }
  | { type: 'SCAN' }
  | { type: 'OPEN_MESSAGE_CENTER' }
  | { type: 'OPEN_ITEM'; itemId: string; itemKind: PlatformItemKind }
  | { type: 'PUBLISH_DEMAND' }
  | { type: 'OPEN_LOCATION' }

export type PlatformFeatureIntentType = PlatformFeatureIntent['type']
