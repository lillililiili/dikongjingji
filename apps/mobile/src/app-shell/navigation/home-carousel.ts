import { droneArtwork, robotArtwork, workshopArtwork } from './home-artwork'
import {
  PLAY_GAMES_PATH,
  isHomeEntryTargetVisible,
  type HomeEntryTarget,
} from './home-channels'

export type HomeCarouselTone = 'SKY' | 'SUN' | 'VIOLET' | 'MINT'

export interface HomeCarouselSlide {
  id: string
  tag: string
  titleLines: readonly [string, string]
  descriptionLines: readonly [string, string]
  actionLabel: string
  imageUrl: string
  imageAlt: string
  tone: HomeCarouselTone
  target: HomeEntryTarget
}

export const HOME_CAROUSEL_INTERVAL_MS = 5500

// The four editorial examples from the revised prototype are shown only in preview builds.
// The first two cards open the actual city activity channel (App plan §7.1).
const HOME_CAROUSEL_SLIDES: readonly HomeCarouselSlide[] = [
  {
    id: 'contest',
    tag: '热门精选 · 赛事报名',
    titleLines: ['小小飞行家', '挑战大舞台'],
    descriptionLines: ['青少年无人机编程挑战赛', '东营站 · 8–15 岁'],
    actionLabel: '查看赛事 · 去报名',
    imageUrl: droneArtwork,
    imageAlt: '无人机与机器人探索场景',
    tone: 'SKY',
    target: { type: 'CHANNEL', channel: 'ACTIVITY' },
  },
  {
    id: 'study-tour',
    tag: '周末精选 · 研学报名',
    titleLines: ['把好奇心', '变成创造力'],
    descriptionLines: ['机器人研学体验', '动手编程 · 避障闯关'],
    actionLabel: '查看研学 · 去报名',
    imageUrl: workshopArtwork,
    imageAlt: '孩子们在明亮课堂探索机器人',
    tone: 'SUN',
    target: { type: 'CHANNEL', channel: 'ACTIVITY' },
  },
  {
    id: 'games',
    tag: '趣玩挑战 · 随时开玩',
    titleLines: ['动动脑筋', '一起闯关'],
    descriptionLines: ['机器人迷宫 · 无人机寻星', '虚拟小游戏 · 无需设备'],
    actionLabel: '开始趣玩挑战',
    imageUrl: robotArtwork,
    imageAlt: '可爱的编程机器人',
    tone: 'VIOLET',
    target: { type: 'PAGE', url: PLAY_GAMES_PATH, publiclyAvailable: false },
  },
  {
    id: 'experience',
    tag: '体验精选 · 先试再选',
    titleLines: ['先体验', '再决定'],
    descriptionLines: ['线上虚拟仿真 · 录播演示', '也可预约线下场馆'],
    actionLabel: '探索体验 · 去预约',
    imageUrl: droneArtwork,
    imageAlt: '机器人与无人机的探索乐园',
    tone: 'MINT',
    target: { type: 'CHANNEL', channel: 'EXPERIENCE' },
  },
]

export function resolveHomeCarouselSlides(
  previewEnabled: boolean,
): readonly HomeCarouselSlide[] {
  if (!previewEnabled) return []
  return HOME_CAROUSEL_SLIDES
    .filter((slide) => isHomeEntryTargetVisible(slide.target, previewEnabled))
}
