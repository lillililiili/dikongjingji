export const NOTICE_DETAIL_PATH = '/pages/message/notice'

// A 系统通知 opens by its stable id; the page reads the announcement from the server.
export function buildNoticeDetailUrl(noticeId: string): string {
  return `${NOTICE_DETAIL_PATH}?id=${encodeURIComponent(noticeId)}`
}

export function parseNoticeId(value: unknown): string | null {
  return typeof value === 'string' && /^[0-9a-fA-F-]{36}$/.test(value) ? value : null
}
