import type { HomeCarouselSlide } from '@/app-shell/navigation/home-carousel'
import { isHomeEntryTargetVisible, PLAY_GAMES_TARGET, type HomeEntryTarget } from '@/app-shell/navigation/home-channels'
import { resolveBusinessMediaUrl } from '../../../shared/http/business-media'
import { publicRequest } from '@/shared/http/public-api-request'

type CarouselTarget = 'ACTIVITY' | 'EXPERIENCE' | 'PLAY_GAMES'
interface CarouselItem extends Omit<HomeCarouselSlide, 'target'> {
  target: CarouselTarget
}

const targets: Record<CarouselTarget, HomeEntryTarget> = {
  ACTIVITY: { type: 'CHANNEL', channel: 'ACTIVITY' },
  EXPERIENCE: { type: 'CHANNEL', channel: 'EXPERIENCE' },
  PLAY_GAMES: PLAY_GAMES_TARGET,
}

export async function loadHomeCarousel(cityCode: string, preview: boolean): Promise<HomeCarouselSlide[]> {
  if (!Object.values(targets).some(target => isHomeEntryTargetVisible(target, preview))) return []
  const { items } = await publicRequest<{ items: CarouselItem[] }>({
    method: 'GET',
    url: `/api/v1/home/carousel?cityCode=${encodeURIComponent(cityCode)}`,
  })
  return items.flatMap(item => {
    const target = targets[item.target]
    if (!target || !isHomeEntryTargetVisible(target, preview)) return []
    const imageUrl = resolveBusinessMediaUrl(item.imageUrl)
    return imageUrl ? [{ ...item, imageUrl, target }] : []
  })
}
