import type { TradeStatusTone } from '../../application/order-view'
import type { TradeOrderLine } from '../../domain/trade-order'

export function coverOfLine(line: TradeOrderLine): string | null {
  return line.coverImage?.url ?? null
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
