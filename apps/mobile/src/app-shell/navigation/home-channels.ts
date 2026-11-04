export type HomeChannelCode =
  | 'RECOMMEND'
  | 'SERVICE'
  | 'ACTIVITY'
  | 'EXPERIENCE'
  | 'DRONE'
  | 'ROBOT'
  | 'PARTS'
  | 'RENTAL'
  | 'COURSE'

export interface HomeChannelDefinition {
  code: HomeChannelCode
  label: string
  searchPlaceholder: string
  publiclyAvailable: boolean
}

export interface HomeChannelCard {
  channel: HomeChannelCode
  title: string
  subtitle: string
}

export type HomeKingEntryTone = 'ORANGE' | 'BLUE' | 'GREEN'

export interface HomeKingEntry {
  code: string
  label: string
  channel: HomeChannelCode
  icon: string
  tone: HomeKingEntryTone
}

const INSPECTION_SEARCH_PLACEHOLDER = '搜索巡检服务、场景或关键词'

export const DEFAULT_HOME_CHANNEL: HomeChannelCode = 'RECOMMEND'

export const HOME_CHANNELS: readonly HomeChannelDefinition[] = [
  { code: 'RECOMMEND', label: '推荐', searchPlaceholder: '搜索设备、活动或服务', publiclyAvailable: true },
  { code: 'SERVICE', label: '专业服务', searchPlaceholder: INSPECTION_SEARCH_PLACEHOLDER, publiclyAvailable: true },
  { code: 'ACTIVITY', label: '活动', searchPlaceholder: '搜索赛事、培训、研学', publiclyAvailable: false },
  { code: 'EXPERIENCE', label: '体验', searchPlaceholder: '搜索体验项目或场馆', publiclyAvailable: false },
  { code: 'DRONE', label: '无人机', searchPlaceholder: '搜索无人机型号或品牌', publiclyAvailable: false },
  { code: 'ROBOT', label: '机器人', searchPlaceholder: '搜索机器人型号或品牌', publiclyAvailable: false },
  { code: 'PARTS', label: '配件', searchPlaceholder: '搜索配件型号或品牌', publiclyAvailable: false },
  { code: 'RENTAL', label: '租赁', searchPlaceholder: '搜索租赁设备', publiclyAvailable: false },
  { code: 'COURSE', label: '课程', searchPlaceholder: '搜索课程或培训机构', publiclyAvailable: false },
]

const HOME_CHANNEL_CARDS: readonly (HomeChannelCard & { publicSubtitle?: string })[] = [
  { channel: 'SERVICE', title: '找专业服务', subtitle: '巡检 · 航拍 · 测绘', publicSubtitle: '巡检' },
  { channel: 'EXPERIENCE', title: '去体验', subtitle: '仿真 · 演示 · 到馆' },
]

const HOME_KING_ENTRIES: readonly HomeKingEntry[] = [
  { code: 'drone', label: '无人机', channel: 'DRONE', icon: 'paperplane', tone: 'ORANGE' },
  { code: 'robot', label: '机器人', channel: 'ROBOT', icon: 'gear', tone: 'BLUE' },
  { code: 'service', label: '专业服务', channel: 'SERVICE', icon: 'list', tone: 'ORANGE' },
  { code: 'rental', label: '设备租赁', channel: 'RENTAL', icon: 'shop', tone: 'BLUE' },
  { code: 'course', label: '培训课程', channel: 'ACTIVITY', icon: 'calendar', tone: 'GREEN' },
  { code: 'contest', label: '赛事活动', channel: 'ACTIVITY', icon: 'medal', tone: 'BLUE' },
  { code: 'study-tour', label: '研学体验', channel: 'ACTIVITY', icon: 'map', tone: 'GREEN' },
  { code: 'venue', label: '线下场馆', channel: 'EXPERIENCE', icon: 'location', tone: 'GREEN' },
]

function isChannelVisible(
  channel: HomeChannelCode,
  previewEnabled: boolean,
): boolean {
  return resolveVisibleHomeChannels(previewEnabled)
    .some((candidate) => candidate.code === channel)
}

export function resolveVisibleHomeChannels(
  previewEnabled: boolean,
): readonly HomeChannelDefinition[] {
  return previewEnabled
    ? HOME_CHANNELS
    : HOME_CHANNELS.filter((channel) => channel.publiclyAvailable)
}

// Public builds only search the inspection catalog, so they must not promise devices or activities.
export function resolveHomeSearchPlaceholder(
  channel: HomeChannelCode,
  previewEnabled: boolean,
): string {
  if (!previewEnabled) return INSPECTION_SEARCH_PLACEHOLDER
  return HOME_CHANNELS.find((candidate) => candidate.code === channel)
    ?.searchPlaceholder ?? INSPECTION_SEARCH_PLACEHOLDER
}

export function isHomeChannelSearchable(channel: HomeChannelCode): boolean {
  return channel === 'RECOMMEND' || channel === 'SERVICE'
}

export function resolveHomeChannelCards(
  previewEnabled: boolean,
): readonly HomeChannelCard[] {
  return HOME_CHANNEL_CARDS
    .filter((card) => isChannelVisible(card.channel, previewEnabled))
    .map(({ channel, title, subtitle, publicSubtitle }) => ({
      channel,
      title,
      subtitle: previewEnabled ? subtitle : publicSubtitle ?? subtitle,
    }))
}

export function resolveHomeKingEntries(
  previewEnabled: boolean,
): readonly HomeKingEntry[] {
  return HOME_KING_ENTRIES
    .filter((entry) => isChannelVisible(entry.channel, previewEnabled))
}
