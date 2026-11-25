import type { ApiClient } from '@/core/http/api-client'
import type { Page } from '@/core/ui/use-paged-list'
import type { VenueStatus } from './venue-api'

export const types = { SIMULATION: '虚拟仿真', RECORDED_DEMO: '录播演示', SOFTWARE_TRIAL: '软件试用', COURSE_TRIAL: '课程试听' } as const
export type ItemType = keyof typeof types
export type ItemStatus = VenueStatus
export type Choice = { id: string, name: string, mediaUrl?: string|null }
export interface ExperienceForm {
  type: ItemType
  title: string
  summary: string
  coverUrl: string
  loginRequired: boolean
  sortOrder: number
  status: ItemStatus
  version: number | null
  productId: string | null
  simulationTemplate: string | null
  trialMinutes: number | null
  videoUrl: string
  courseId: string | null
}
export interface OnlineItem extends Omit<ExperienceForm, 'summary'|'coverUrl'|'videoUrl'|'version'> {
  id: string
  summary: string | null
  coverUrl: string | null
  videoUrl: string | null
  version: number
  productName: string | null
  courseName: string | null
}
export interface Change { version: number, action: string, operatorName: string, changedAt: string }
export const actions: Record<string,string> = { CREATE: '创建草稿', UPDATE: '保存', PUBLISH: '发布', OFFLINE: '下架' }
export function formFor(item: OnlineItem | null): ExperienceForm {
  return { type: item?.type ?? 'SIMULATION', title: item?.title ?? '', summary: item?.summary ?? '',
    coverUrl: item?.coverUrl ?? '', loginRequired: item?.loginRequired ?? false, sortOrder: item?.sortOrder ?? 0,
    status: item?.status ?? 'DRAFT', version: item?.version ?? null, productId: item?.productId ?? null,
    simulationTemplate: item?.simulationTemplate ?? null, trialMinutes: item?.trialMinutes ?? null,
    videoUrl: item?.videoUrl ?? '', courseId: item?.courseId ?? null }
}
export function onlineExperienceApi(http: ApiClient) {
  const path = '/api/v1/ops/experience-items'
  return {
    list: (f: {keyword: string, status: string, type: string}, page: number, size: number) =>
      http.get<Page<OnlineItem>>(path, { keyword: f.keyword || undefined, status: f.status || undefined, type: f.type || undefined, page, size }),
    get: (id: string) => http.get<OnlineItem>(path + '/' + id),
    choices: (kind: 'PRODUCT'|'COURSE'|'VIDEO', keyword: string) => http.get<Choice[]>(path+'/choices/'+kind,{ keyword }),
    changes: (id: string) => http.get<Change[]>(path+'/'+id+'/changes'),
    save: (id: string, form: ExperienceForm, previousCourseId: string | null) => http.put<OnlineItem>(path+'/'+id, { ...form, previousCourseId }),
  }
}
