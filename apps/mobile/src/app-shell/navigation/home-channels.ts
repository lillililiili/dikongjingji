import type { LineIconName } from '@/shared/ui/line-icons'

export type HomeChannelCode =
  | 'RECOMMEND'
  | 'SERVICE'
  | 'ACTIVITY'
  | 'EXPERIENCE'
  | 'MALL'

export interface HomeChannelDefinition {
  code: HomeChannelCode
  label: string
  searchPlaceholder: string
  publiclyAvailable: boolean
}

// The category tabs inside the mall channel. Kept here as plain codes so the shell does not
// depend on the mall feature; the mall channel owns their meaning.
export type HomeMallCategory = 'DRONE' | 'ROBOT' | 'PARTS' | 'RENTAL' | 'COURSE'

// Where a home entry leads: a channel of the home page (the mall channel optionally on one of its
// category tabs), or a page of its own.
export type HomeEntryTarget =
  | { type: 'CHANNEL'; channel: HomeChannelCode; mallCategory?: HomeMallCategory }
  | { type: 'PAGE'; url: string; publiclyAvailable: boolean }

export interface HomeChannelCard {
  channel: HomeChannelCode
  title: string
  subtitle: string
  /** The board's line icon in the card's corner (HomeFull.html). */
  icon: LineIconName
}

export interface HomeKingEntry {
  code: string
  label: string
  target: HomeEntryTarget
  /** The board's line icon (HomeFull.html `renderVals`); the tile colour follows its position. */
  icon: LineIconName
}

const INSPECTION_SEARCH_PLACEHOLDER = '搜索巡检服务、场景或关键词'

export const PLAY_GAMES_PATH = '/pages/play/index'

// Both this entry and the direct game routes follow the PLAY release switch.
export const PLAY_GAMES_TARGET: HomeEntryTarget = {
  type: 'PAGE',
  url: PLAY_GAMES_PATH,
  publiclyAvailable: __RELEASE_FEATURE_PLAY__,
}

export const DEFAULT_HOME_CHANNEL: HomeChannelCode = 'RECOMMEND'

// publiclyAvailable of the three preview channels is their feature's release switch
// (src/app-shell/release/release-features.json): false on public builds until it is turned on.
export const HOME_CHANNELS: readonly HomeChannelDefinition[] = [
  { code: 'RECOMMEND', label: '推荐', searchPlaceholder: '搜索设备、赛事研学或服务', publiclyAvailable: true },
  { code: 'SERVICE', label: '专业服务', searchPlaceholder: INSPECTION_SEARCH_PLACEHOLDER, publiclyAvailable: true },
  { code: 'ACTIVITY', label: '赛事研学', searchPlaceholder: '搜索赛事、培训、研学', publiclyAvailable: __RELEASE_FEATURE_ACTIVITY__ },
  { code: 'EXPERIENCE', label: '体验', searchPlaceholder: '搜索体验项目或场馆', publiclyAvailable: __RELEASE_FEATURE_EXPERIENCE__ },
  { code: 'MALL', label: '商城', searchPlaceholder: '搜索设备、配件或课程', publiclyAvailable: __RELEASE_FEATURE_MALL__ },
]

const HOME_CHANNEL_CARDS: readonly (HomeChannelCard & { publicSubtitle?: string })[] = [
  { channel: 'SERVICE', title: '找专业服务', subtitle: '巡检 · 航拍 · 测绘', publicSubtitle: '巡检', icon: 'service' },
  { channel: 'EXPERIENCE', title: '去体验', subtitle: '仿真 · 演示 · 到馆', icon: 'screenPlay' },
]

const toChannel = (channel: HomeChannelCode): HomeEntryTarget => ({ type: 'CHANNEL', channel })

const toMall = (mallCategory: HomeMallCategory): HomeEntryTarget => ({
  type: 'CHANNEL',
  channel: 'MALL',
  mallCategory,
})

const HOME_KING_ENTRIES: readonly HomeKingEntry[] = [
  { code: 'drone', label: '无人机', target: toMall('DRONE'), icon: 'drone' },
  { code: 'robot', label: '机器人', target: toMall('ROBOT'), icon: 'robot' },
  { code: 'games', label: '趣玩挑战', target: PLAY_GAMES_TARGET, icon: 'game' },
  { code: 'rental', label: '设备租赁', target: toMall('RENTAL'), icon: 'rental' },
  { code: 'course', label: '培训课程', target: toMall('COURSE'), icon: 'course' },
  { code: 'contest', label: '赛事报名', target: toChannel('ACTIVITY'), icon: 'trophy' },
  { code: 'study-tour', label: '研学体验', target: toChannel('ACTIVITY'), icon: 'studyTour' },
  { code: 'venue', label: '线下场馆', target: toChannel('EXPERIENCE'), icon: 'venue' },
]

function isChannelVisible(
  channel: HomeChannelCode,
  previewEnabled: boolean,
): boolean {
  return resolveVisibleHomeChannels(previewEnabled)
    .some((candidate) => candidate.code === channel)
}

export function isHomeEntryTargetVisible(
  target: HomeEntryTarget,
  previewEnabled: boolean,
): boolean {
  return target.type === 'CHANNEL'
    ? isChannelVisible(target.channel, previewEnabled)
    : previewEnabled || target.publiclyAvailable
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
    .map(({ channel, title, subtitle, publicSubtitle, icon }) => ({
      channel,
      title,
      subtitle: previewEnabled ? subtitle : publicSubtitle ?? subtitle,
      icon,
    }))
}

export function resolveHomeKingEntries(
  previewEnabled: boolean,
): readonly HomeKingEntry[] {
  return HOME_KING_ENTRIES
    .filter((entry) => isHomeEntryTargetVisible(entry.target, previewEnabled))
}
