import type { PlatformChannelCode } from './platform-channel'

export type PlatformItemKind =
  | 'AERIAL_SERVICE'
  | 'FLIGHT_SERVICE'
  | 'ROBOT_SERVICE'
  | 'ACTIVITY'
  | 'CONTENT'
  | 'PRODUCT'
  | 'SOLUTION'

export type PlatformFeatureIntent =
  | { type: 'SEARCH'; channel: PlatformChannelCode }
  | { type: 'OPEN_MESSAGE_CENTER' }
  | { type: 'OPEN_ITEM'; itemId: string; itemKind: PlatformItemKind }
  | { type: 'OPEN_MORE_SERVICES' }
  | { type: 'OPEN_LOCATION' }

export type PlatformFeatureIntentType = PlatformFeatureIntent['type']
