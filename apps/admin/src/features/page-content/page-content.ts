import { http } from '@/core/http'
import type { Page } from '@/core/ui/use-paged-list'

export interface Slide {
  id: string; cityCode: string; version: number; sortOrder: number
  tag: string; titleFirst: string; titleSecond: string; descriptionFirst: string; descriptionSecond: string
  actionLabel: string; imageUrl: string; imageAlt: string; tone: string; target: string; status: string
}
export interface Portal {
  id: string; cityCode: string | null; version: number; sortOrder: number
  contentType: string; itemCode: string; title: string; subtitle: string | null
  highlightedText: string | null; imageUrl: string | null; status: string
}
export interface Change { version: number; operatorName: string; changedAt: string; status: string }
export interface Deliverable { code: string; title: string; imageUrl: string | null }

const slides = '/api/v1/ops/home/carousel'
const portal = '/api/v1/ops/inspection/portal-content'
const imagePath = (id: string) => '/api/v1/ops/inspection/services/'+encodeURIComponent(id)+'/deliverable-images'
const slidePath = (s: Slide) => slides+'/'+encodeURIComponent(s.cityCode)+'/'+encodeURIComponent(s.id)
export const pageContent = {
  slides: (cityCode: string, page: number, size: number) => http.get<Page<Slide>>(slides, { cityCode, page, size }),
  saveSlide: (s: Slide) => s.id ? http.put(slidePath(s), s) : http.post(slides+'/'+encodeURIComponent(s.cityCode), s),
  slideChanges: (s: Slide) => http.get<Change[]>(slidePath(s)+'/changes'),
  portal: (cityCode: string) => http.get<Portal[]>(portal, {cityCode}),
  savePortal: (p: Portal) => p.id ? http.put(portal+'/'+p.id,p) : http.post(portal,p),
  portalChanges: (p: Portal) => http.get<Change[]>(portal+'/'+p.id+'/changes'),
  deliverables: (id: string) => http.get<Deliverable[]>(imagePath(id)),
  saveImage: (id: string, code: string, imageUrl: string, version: number) =>
    http.put<void>(imagePath(id)+'/'+encodeURIComponent(code), {imageUrl, version}),
}
export const stateLabels: Record<string,string> = {
  DRAFT:'草稿', PUBLISHED:'已发布', OFFLINE:'已下架', ACTIVE:'已启用', INACTIVE:'已停用',
}
export const targetLabels: Record<string,string> = { ACTIVITY:'赛事研学', PLAY_GAMES:'趣玩挑战', EXPERIENCE:'体验' }
export const toneLabels: Record<string,string> = { SKY:'天蓝', SUN:'暖橙', VIOLET:'浅紫', MINT:'薄荷绿' }
export const typeLabels: Record<string,string> = { HERO:'横幅', SCENE:'场景说明', GUARANTEE:'服务保障', PROCESS:'服务流程' }
