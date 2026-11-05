import type {
  BookingCodeLabel,
  BookingServiceSnapshot,
  BookingTimeSlotCode,
  CreateReservationPayload,
} from '../../domain/service-flow/booking'

export interface AccessTokenPort {
  getAccessToken(): string | null
}

export interface BookingHttpClient {
  request<T>(input: {
    url: string
    method: 'GET' | 'POST'
    data?: unknown
    headers?: Record<string, string>
  }): Promise<T>
  putFile(input: {
    url: string
    filePath: string
    headers: Record<string, string>
  }): Promise<{ etag: string }>
}

export interface BookingReservation {
  id: string
  reservationNo: string
  customerId: string
  providerId: string | null
  serviceId: string
  serviceTitle: string
  serviceCoverUrl: string | null
  cityCode: string
  status: 'SUBMITTED' | 'PLAN_PUBLISHED' | 'CANCELLED'
  location: {
    address: string
    longitude: number | null
    latitude: number | null
  }
  lineLengthMeters: number | null
  preferredServiceDate: string
  preferredTimeSlotCode: BookingTimeSlotCode
  inspectionItems: BookingCodeLabel[]
  note: string | null
  contact: { name: string; mobile: string }
  attachments: Array<{
    id: string
    originalName: string
    contentType: string
    sizeBytes: number
    status: 'PENDING_UPLOAD' | 'UPLOADED' | 'SCANNING' | 'READY' | 'REJECTED' | 'DELETED'
    scanFailureReason: string | null
  }>
  submittedAt: string
  currentPlanId: string | null
  version: number
}

export interface BookingAvailableTimeSlot {
  slotCode: BookingTimeSlotCode
  startTime: string
  endTime: string
  remainingCount: number
  availability: 'AVAILABLE' | 'FULL' | 'TOO_SOON'
}

export interface BookingAvailableDate {
  serviceDate: string
  slots: BookingAvailableTimeSlot[]
}

interface UploadIntent {
  fileId: string
  uploadUrl: string
  requiredHeaders: Record<string, string>
}

interface CompletedFile {
  id: string
  status: 'PENDING_UPLOAD' | 'UPLOADED' | 'SCANNING' | 'READY' | 'REJECTED' | 'DELETED'
  scanFailureReason: string | null
}

export interface InspectionBookingApi {
  loadServiceSnapshot(serviceId: string, cityCode: string): Promise<BookingServiceSnapshot | null>
  loadAvailableTimeSlots(
    serviceId: string,
    cityCode: string,
    startDate: string,
    days: number,
  ): Promise<BookingAvailableDate[]>
  createReservation(payload: CreateReservationPayload, idempotencyKey: string): Promise<BookingReservation>
  getReservation(reservationId: string): Promise<BookingReservation>
  cancelReservation(
    reservationId: string,
    version: number,
    idempotencyKey: string,
  ): Promise<BookingReservation>
  uploadReservationAttachment(input: {
    reservationId: string
    originalName: string
    contentType: string
    sizeBytes: number
    sha256: string
    filePath: string
    idempotencyKey: string
  }): Promise<{ fileId: string; status: CompletedFile['status']; errorMessage?: string }>
}

export function createInspectionBookingApi(
  http: BookingHttpClient,
  accessToken: AccessTokenPort = { getAccessToken: () => null },
  publicReadHttp: Pick<BookingHttpClient, 'request'> = http,
): InspectionBookingApi {
  const headers = (idempotencyKey?: string): Record<string, string> => {
    const token = accessToken.getAccessToken()
    return {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
    }
  }

  return {
    async loadServiceSnapshot(serviceId, cityCode) {
      const page = await publicReadHttp.request<{
        items: Array<{
          serviceId: string
          category: { name: string }
          name: string
          summary: string
          coverImage: { url: string } | null
          features: Array<{ code: string; name: string }>
          price: {
            mode: string
            startingAmountMinor?: number | null
            unitLabel?: string | null
          }
        }>
      }>({
        url: `/api/v1/inspection/services?cityCode=${encodeURIComponent(cityCode)}&page=1&size=50`,
        method: 'GET',
      })
      const service = page.items.find((item) => item.serviceId === serviceId)

      if (!service) {
        return null
      }

      return {
        serviceId: service.serviceId,
        categoryName: service.category.name,
        title: service.name,
        summary: service.summary,
        coverImageUrl: service.coverImage?.url ?? null,
        cityCode,
        inspectionItems: service.features.map((feature) => ({
          code: feature.code,
          label: feature.name,
        })),
        priceMode: service.price.mode,
        priceAmountMinor: service.price.startingAmountMinor ?? null,
        priceUnitLabel: service.price.unitLabel ?? null,
        priceLabel: service.price.mode === 'PROJECT_QUOTE' || service.price.startingAmountMinor == null
          ? '按项目报价'
          : `¥${Math.round(service.price.startingAmountMinor / 100)}${service.price.unitLabel ? `/${service.price.unitLabel}` : ''}`,
      }
    },
    async loadAvailableTimeSlots(serviceId, cityCode, startDate, days) {
      const dates = await http.request<BookingAvailableDate[]>({
        url: `/api/v1/inspection/services/${encodeURIComponent(serviceId)}/available-time-slots?cityCode=${encodeURIComponent(cityCode)}&startDate=${encodeURIComponent(startDate)}&days=${encodeURIComponent(days)}`,
        method: 'GET',
        headers: headers(),
      })

      return dates.map((date) => ({
        serviceDate: date.serviceDate,
        slots: date.slots.map((slot) => ({
          slotCode: slot.slotCode,
          startTime: slot.startTime,
          endTime: slot.endTime,
          remainingCount: slot.remainingCount,
          availability: slot.availability,
        })),
      }))
    },
    createReservation(payload, idempotencyKey) {
      return http.request<BookingReservation>({
        url: '/api/v1/inspection/reservations',
        method: 'POST',
        data: payload,
        headers: headers(idempotencyKey),
      })
    },
    getReservation(reservationId) {
      return http.request<BookingReservation>({
        url: `/api/v1/inspection/reservations/${encodeURIComponent(reservationId)}`,
        method: 'GET',
        headers: headers(),
      })
    },
    cancelReservation(reservationId, version, idempotencyKey) {
      return http.request<BookingReservation>({
        url: `/api/v1/inspection/reservations/${encodeURIComponent(reservationId)}/cancel`,
        method: 'POST',
        headers: {
          ...headers(idempotencyKey),
          'If-Match': `"${version}"`,
        },
      })
    },
    async uploadReservationAttachment(input) {
      const intent = await http.request<UploadIntent>({
        url: '/api/v1/inspection/file-objects/upload-intents',
        method: 'POST',
        data: {
          purpose: 'RESERVATION_ATTACHMENT',
          ownerId: input.reservationId,
          originalName: input.originalName,
          contentType: input.contentType,
          sizeBytes: input.sizeBytes,
          sha256: input.sha256,
        },
        headers: headers(input.idempotencyKey),
      })
      const upload = await http.putFile({
        url: intent.uploadUrl,
        filePath: input.filePath,
        headers: intent.requiredHeaders,
      })
      const completed = await http.request<CompletedFile>({
        url: `/api/v1/inspection/file-objects/${encodeURIComponent(intent.fileId)}/complete`,
        method: 'POST',
        data: {
          etag: upload.etag,
          sizeBytes: input.sizeBytes,
          sha256: input.sha256,
        },
        headers: headers(input.idempotencyKey),
      })

      return {
        fileId: completed.id,
        status: completed.status,
        ...(completed.scanFailureReason
          ? { errorMessage: completed.scanFailureReason }
          : {}),
      }
    },
  }
}
