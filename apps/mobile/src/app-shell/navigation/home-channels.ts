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

// Where a home entry leads: a channel of the home page, or a page of its own.
export type HomeEntryTarget =
  | { type: 'CHANNEL'; channel: HomeChannelCode }
  | { type: 'PAGE'; url: string; publiclyAvailable: boolean }

export interface HomeChannelCard {
  channel: HomeChannelCode
  title: string
  subtitle: string
}

export type HomeKingEntryTone = 'ORANGE' | 'BLUE' | 'GREEN' | 'VIOLET' | 'YELLOW'

export interface HomeKingEntry {
  code: string
  label: string
  target: HomeEntryTarget
  icon: string
  tone: HomeKingEntryTone
}

const INSPECTION_SEARCH_PLACEHOLDER = '搜索巡检服务、场景或关键词'

export const PLAY_GAMES_PATH = '/pages/play/index'

// The two games work without a server, but the entry stays out of public builds until the user accepts them.
const PLAY_GAMES_TARGET: HomeEntryTarget = {
  type: 'PAGE',
  url: PLAY_GAMES_PATH,
  publiclyAvailable: false,
}

export const DEFAULT_HOME_CHANNEL: HomeChannelCode = 'RECOMMEND'

export const HOME_CHANNELS: readonly HomeChannelDefinition[] = [
  { code: 'RECOMMEND', label: '推荐', searchPlaceholder: '搜索设备、赛事研学或服务', publiclyAvailable: true },
  { code: 'SERVICE', label: '专业服务', searchPlaceholder: INSPECTION_SEARCH_PLACEHOLDER, publiclyAvailable: true },
  { code: 'ACTIVITY', label: '赛事研学', searchPlaceholder: '搜索赛事、培训、研学', publiclyAvailable: false },
  { code: 'EXPERIENCE', label: '体验', searchPlaceholder: '搜索体验项目或场馆', publiclyAvailable: false },
  { code: 'MALL', label: '商城', searchPlaceholder: '搜索设备、配件或课程', publiclyAvailable: false },
]

const HOME_CHANNEL_CARDS: readonly (HomeChannelCard & { publicSubtitle?: string })[] = [
  { channel: 'SERVICE', title: '找专业服务', subtitle: '巡检 · 航拍 · 测绘', publicSubtitle: '巡检' },
  { channel: 'EXPERIENCE', title: '去体验', subtitle: '仿真 · 演示 · 到馆' },
]

const toChannel = (channel: HomeChannelCode): HomeEntryTarget => ({ type: 'CHANNEL', channel })

const HOME_KING_ENTRIES: readonly HomeKingEntry[] = [
  { code: 'drone', label: '无人机', target: toChannel('MALL'), icon: 'paperplane', tone: 'BLUE' },
  { code: 'robot', label: '机器人', target: toChannel('MALL'), icon: 'gear', tone: 'VIOLET' },
  { code: 'games', label: '趣玩挑战', target: PLAY_GAMES_TARGET, icon: 'flag', tone: 'ORANGE' },
  { code: 'rental', label: '设备租赁', target: toChannel('MALL'), icon: 'shop', tone: 'GREEN' },
  { code: 'course', label: '培训课程', target: toChannel('MALL'), icon: 'calendar', tone: 'YELLOW' },
  { code: 'contest', label: '赛事报名', target: toChannel('ACTIVITY'), icon: 'medal', tone: 'BLUE' },
  { code: 'study-tour', label: '研学体验', target: toChannel('ACTIVITY'), icon: 'map', tone: 'GREEN' },
  { code: 'venue', label: '线下场馆', target: toChannel('EXPERIENCE'), icon: 'location', tone: 'GREEN' },
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
    .filter((entry) => isHomeEntryTargetVisible(entry.target, previewEnabled))
}
