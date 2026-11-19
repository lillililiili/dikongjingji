// The sample products of the development seed carry no pictures of their own; the mall's preview
// covers stand in (preview builds only, and a cover from the order always wins), so a line looks the
// same here as on the product and confirm pages.
import { resolveMallPreviewCover } from '@/features/mall/data/preview/mall-preview-covers'

import type { TradeStatusTone } from '../../application/order-view'
import type { TradeOrderLine } from '../../domain/trade-order'

export function coverOfLine(line: TradeOrderLine): string | null {
  return line.coverImage?.url ?? resolveMallPreviewCover(line.productId)?.url ?? null
}

// Status colours of the board OrderList: orange for "something to do", green for done, grey for over.
export const TRADE_TONE_COLORS: Record<TradeStatusTone, string> = {
  ACTION: '#ba470b',
  DONE: '#11804a',
  MUTED: '#738096',
  ALERT: '#d64f67',
}

export function copyText(text: string, onCopied: () => void): void {
  uni.setClipboardData({
    data: text,
    showToast: false,
    success: onCopied,
  } as UniApp.SetClipboardDataOptions)
}

export function confirmDialog(options: {
  title: string
  content: string
  confirmText: string
  cancelText: string
}): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      ...options,
      confirmColor: '#ff6a00',
      success: (result) => resolve(Boolean(result.confirm)),
      fail: () => resolve(false),
    })
  })
}

export function wait(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
