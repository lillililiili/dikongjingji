import type { BookingReservation } from '../../data/service-flow/inspection-booking-api'

export type InspectionCancellationResult =
  | { kind: 'dismissed' }
  | { kind: 'cancelled'; reservation: BookingReservation }
  | { kind: 'refresh-required' }
  | { kind: 'failed'; message: string }

interface CancellationInput {
  reservation: BookingReservation
  cancelReservation(
    reservationId: string,
    version: number,
    idempotencyKey: string,
  ): Promise<BookingReservation>
  confirm(): Promise<boolean>
  idempotencyKey(): string
}

interface CancellationApiErrorShape {
  status?: unknown
  code?: unknown
}

function isCancellationApiError(error: unknown): error is CancellationApiErrorShape {
  return typeof error === 'object' && error !== null
}

function hasCancellationErrorCode(error: CancellationApiErrorShape, code: string): boolean {
  return error.code === code
}

export function canCancelInspectionOrder(
  status: BookingReservation['status'],
): boolean {
  return status === 'SUBMITTED' || status === 'PLAN_PUBLISHED'
}

export async function requestInspectionOrderCancellation(
  input: CancellationInput,
): Promise<InspectionCancellationResult> {
  if (!canCancelInspectionOrder(input.reservation.status)) {
    return { kind: 'refresh-required' }
  }

  if (!await input.confirm()) return { kind: 'dismissed' }

  try {
    const reservation = await input.cancelReservation(
      input.reservation.id,
      input.reservation.version,
      input.idempotencyKey(),
    )
    return { kind: 'cancelled', reservation }
  } catch (error) {
    if (isCancellationApiError(error)) {
      if (hasCancellationErrorCode(error, 'INS_RESERVATION_NOT_CANCELLABLE')
        || hasCancellationErrorCode(error, 'INS_VERSION_CONFLICT')) {
        return { kind: 'refresh-required' }
      }
      if (error.status === 409
        && hasCancellationErrorCode(error, 'INS_IDEMPOTENCY_KEY_REUSED')) {
        return { kind: 'failed', message: '取消请求冲突，请刷新订单状态后重试' }
      }
    }
    return { kind: 'failed', message: '取消失败，请检查网络后重试' }
  }
}
