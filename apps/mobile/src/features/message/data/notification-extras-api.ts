import {
  toSystemNoticeLink,
  type AgreementUpdate,
  type SystemNotice,
  type SystemNoticeAgreement,
  type SystemNoticeLink,
} from '../domain/system-notice'
import type { NotificationPreferences } from '../domain/notification-preferences'

type ExtrasRequest = <T>(input: {
  url: string
  method: 'GET' | 'POST' | 'PUT'
  headers?: Record<string, string>
  authMode?: 'captured'
  data?: unknown
}) => Promise<T>

interface LinkDto {
  type?: string
  agreement?: string | null
}

interface SystemNoticeDto {
  id: string
  title: string
  summary: string
  paragraphs?: string[]
  facts?: Array<{ label: string; value: string }>
  items?: Array<{ title: string; description: string; link: LinkDto | null }>
  footnote?: string | null
  action?: { label: string; link: LinkDto } | null
  publishedAt: string
}

export type PushPlatform = 'ANDROID' | 'IOS'

export interface PushDeviceInput {
  platform: PushPlatform
  pushClientId: string
  cityCode: string | null
}

export interface NotificationExtrasApi {
  readPreferences(accessToken: string): Promise<NotificationPreferences>
  updatePreferences(accessToken: string, change: Partial<NotificationPreferences>): Promise<NotificationPreferences>
  registerPushDevice(accessToken: string, device: PushDeviceInput): Promise<void>
  unregisterPushDevice(accessToken: string, device: Omit<PushDeviceInput, 'cityCode'>): Promise<void>
  // Public: announcements carry no personal data, and 协议与规则 is readable before signing in.
  readSystemNotice(noticeId: string): Promise<SystemNotice>
  latestAgreementUpdate(agreement: SystemNoticeAgreement): Promise<AgreementUpdate | null>
}

export function toSystemNotice(dto: SystemNoticeDto): SystemNotice {
  const action = dto.action ? toSystemNoticeLink(dto.action.link) : null
  return {
    id: dto.id,
    title: dto.title,
    summary: dto.summary,
    paragraphs: dto.paragraphs ?? [],
    facts: dto.facts ?? [],
    items: (dto.items ?? []).map((item) => ({
      title: item.title,
      description: item.description,
      link: toSystemNoticeLink(item.link),
    })),
    footnote: dto.footnote ?? null,
    // An action whose link this version does not know is left out rather than shown dead.
    action: dto.action && action ? { label: dto.action.label, link: action as SystemNoticeLink } : null,
    publishedAt: dto.publishedAt,
  }
}

export function createNotificationExtrasApi(request: ExtrasRequest): NotificationExtrasApi {
  const headers = (accessToken: string) => ({ Authorization: `Bearer ${accessToken}` })
  return {
    readPreferences(accessToken) {
      return request<NotificationPreferences>({
        url: '/api/v1/notifications/preferences',
        method: 'GET',
        headers: headers(accessToken),
      })
    },
    updatePreferences(accessToken, change) {
      return request<NotificationPreferences>({
        url: '/api/v1/notifications/preferences',
        method: 'PUT',
        headers: { ...headers(accessToken), 'Content-Type': 'application/json' },
        data: change,
      })
    },
    async registerPushDevice(accessToken, device) {
      await request<unknown>({
        url: '/api/v1/notifications/push-devices',
        method: 'PUT',
        headers: { ...headers(accessToken), 'Content-Type': 'application/json' },
        data: device,
      })
    },
    async unregisterPushDevice(accessToken, device) {
      await request<void>({
        url: '/api/v1/notifications/push-devices/unregister',
        method: 'POST',
        authMode: 'captured',
        headers: { ...headers(accessToken), 'Content-Type': 'application/json' },
        data: device,
      })
    },
    async readSystemNotice(noticeId) {
      return toSystemNotice(await request<SystemNoticeDto>({
        url: `/api/v1/system-notices/${encodeURIComponent(noticeId)}`,
        method: 'GET',
      }))
    },
    async latestAgreementUpdate(agreement) {
      const dto = await request<{ latest: AgreementUpdate | null }>({
        url: `/api/v1/system-notices/agreement-updates?agreement=${agreement}`,
        method: 'GET',
      })
      return dto.latest ?? null
    },
  }
}
