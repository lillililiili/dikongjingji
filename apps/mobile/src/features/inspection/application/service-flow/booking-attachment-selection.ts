import type { BookingAttachment } from '../../domain/service-flow/booking'
import type { BookingDocumentPicker } from './booking-document-picker'

const MAX_ATTACHMENT_SIZE_BYTES = 1_073_741_824

export type BookingAttachmentSelectionErrorCode =
  | 'BOOKING_IMAGE_FORMAT_UNSUPPORTED'
  | 'BOOKING_IMAGE_SELECTION_LIMIT'
  | 'BOOKING_IMAGE_SIZE_INVALID'

export class BookingAttachmentSelectionError extends Error {
  constructor(
    readonly code: BookingAttachmentSelectionErrorCode,
    message: string,
  ) {
    super(message)
    this.name = 'BookingAttachmentSelectionError'
  }
}

export interface BookingSelectedImage {
  tempFilePath: string
  size?: number
}

interface AttachmentFactoryOptions {
  count: number
  createLocalId: (index: number) => string
}

function imageFormat(filePath: string): { extension: string; contentType: string } {
  const pathWithoutQuery = filePath.trim().split(/[?#]/, 1)[0] ?? ''
  const extension = pathWithoutQuery.match(/\.([^.\\/]+)$/)?.[1]?.toLowerCase()
  if (extension === 'jpg' || extension === 'jpeg') {
    return { extension, contentType: 'image/jpeg' }
  }
  if (extension === 'png') {
    return { extension, contentType: 'image/png' }
  }
  throw new BookingAttachmentSelectionError(
    'BOOKING_IMAGE_FORMAT_UNSUPPORTED',
    '仅支持 JPG、JPEG 或 PNG 图片，请重新选择',
  )
}

export function createBookingImageAttachments(
  files: readonly BookingSelectedImage[],
  options: AttachmentFactoryOptions,
): BookingAttachment[] {
  if (files.length > options.count) {
    throw new BookingAttachmentSelectionError(
      'BOOKING_IMAGE_SELECTION_LIMIT',
      `最多还能选择 ${Math.max(0, options.count)} 个附件`,
    )
  }

  return files.map((file, index) => {
    const filePath = file.tempFilePath.trim()
    const format = imageFormat(filePath)
    const sizeBytes = file.size ?? Number.NaN
    if (
      !Number.isFinite(sizeBytes)
      || sizeBytes < 1
      || sizeBytes > MAX_ATTACHMENT_SIZE_BYTES
    ) {
      throw new BookingAttachmentSelectionError(
        'BOOKING_IMAGE_SIZE_INVALID',
        '图片大小必须在 1 字节到 1 GiB 之间',
      )
    }

    return {
      localId: options.createLocalId(index),
      filePath,
      originalName: `巡检附件-${index + 1}.${format.extension}`,
      contentType: format.contentType,
      sizeBytes,
      sha256: '',
      status: 'PENDING_UPLOAD',
    }
  })
}

interface PickBookingPdfAttachmentsOptions extends AttachmentFactoryOptions {
  picker: BookingDocumentPicker
  isPageActive: () => boolean
}

export async function pickBookingPdfAttachments(
  options: PickBookingPdfAttachmentsOptions,
): Promise<BookingAttachment[]> {
  try {
    const result = await options.picker.pickPdf({ count: options.count })
    if (result.kind === 'cancelled') {
      return []
    }
    if (!options.isPageActive()) {
      options.picker.releasePdfFiles(
        result.documents.map((document) => document.filePath),
      )
      return []
    }
    return result.documents.map((document, index) => ({
      localId: options.createLocalId(index),
      filePath: document.filePath,
      originalName: document.originalName,
      contentType: document.contentType,
      sizeBytes: document.sizeBytes,
      sha256: '',
      status: 'PENDING_UPLOAD',
    }))
  } catch (error) {
    if (!options.isPageActive()) {
      return []
    }
    throw error
  }
}
