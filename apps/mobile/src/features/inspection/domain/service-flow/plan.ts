export const PLAN_AGREEMENT_VERSION = 'inspection-plan-v2'

export interface PlanStatusCarrier {
  status: 'DRAFT' | 'PUBLISHED' | 'CONFIRMED' | 'EXPIRED' | 'WITHDRAWN'
  validUntil: string
}

export function isPlanConfirmable(
  plan: PlanStatusCarrier,
  now = new Date(),
): boolean {
  const validUntil = new Date(plan.validUntil)
  return plan.status === 'PUBLISHED'
    && !Number.isNaN(validUntil.getTime())
    && validUntil.getTime() > now.getTime()
}

export function formatPlanAmount(amountMinor: number): string {
  if (!Number.isSafeInteger(amountMinor) || amountMinor < 0) {
    return '¥--'
  }

  return `¥${(amountMinor / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

// Canvas prices drop the fen when there are none: ¥18,600, but ¥5,580.50.
export function formatPlanPrice(amountMinor: number): string {
  if (!Number.isSafeInteger(amountMinor) || amountMinor < 0) {
    return '¥--'
  }

  return `¥${(amountMinor / 100).toLocaleString('zh-CN', {
    minimumFractionDigits: amountMinor % 100 === 0 ? 0 : 2,
    maximumFractionDigits: 2,
  })}`
}
