// Rental entries that are not built (§24.1): talking to the merchant waits for the customer
// service sessions of step G. They go through this typed gate instead of ad-hoc toasts; rental
// pages are only shown in preview builds, so the gate only ever explains.
export type RentalIntent = { type: 'CONTACT_MERCHANT' }

const NOTICES: Record<RentalIntent['type'], string> = {
  CONTACT_MERCHANT: '咨询功能即将开放',
}

export function resolveRentalIntent(intent: RentalIntent): { action: 'SHOW_NOTICE'; message: string } {
  return { action: 'SHOW_NOTICE', message: NOTICES[intent.type] }
}

export function showRentalGate(intent: RentalIntent): void {
  uni.showToast({ title: resolveRentalIntent(intent).message, icon: 'none' })
}
