import {
  DAY_MS,
  formatBeijingTime,
  toBeijingDate,
  toBeijingParts,
} from '../../../shared/time/beijing-time'

import type { MessageCategory } from '../domain/message'

export const MESSAGE_CATEGORY_LABELS: Record<MessageCategory, string> = {
  SERVICE_PROGRESS: '服务进度',
}

// "今天 14:05", "昨天 09:30", otherwise "11/3 16:20". Days are Beijing days.
export function formatMessageTime(iso: string, now: Date): string {
  const at = new Date(iso)
  const time = formatBeijingTime(iso)
  const day = toBeijingDate(at)
  if (day === toBeijingDate(now)) return `今天 ${time}`
  if (day === toBeijingDate(new Date(now.getTime() - DAY_MS))) return `昨天 ${time}`
  const parts = toBeijingParts(at)
  return `${parts.month}/${parts.day} ${time}`
}
