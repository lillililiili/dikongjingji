export interface BookingSelectedDocument {
  filePath: string
  originalName: string
  contentType: 'application/pdf'
  sizeBytes: number
}

export type BookingDocumentPickerResult =
  | { kind: 'selected'; documents: BookingSelectedDocument[] }
  | { kind: 'cancelled' }

export interface BookingDocumentPicker {
  pickPdf(input: { count: number }): Promise<BookingDocumentPickerResult>
  /** Releases App private PDF copies after they are no longer needed. */
  releasePdfFiles(filePaths: readonly string[]): void
}

export type BookingDocumentPickerErrorCode =
  | 'BOOKING_DOCUMENT_PICKER_UNAVAILABLE'
  | 'BOOKING_DOCUMENT_PICKER_FAILED'
  | 'BOOKING_DOCUMENT_NOT_PDF'
  | 'BOOKING_DOCUMENT_SELECTION_LIMIT'
  | 'BOOKING_DOCUMENT_SIZE_INVALID'

export class BookingDocumentPickerError extends Error {
  constructor(
    readonly code: BookingDocumentPickerErrorCode,
    message: string,
  ) {
    super(message)
    this.name = 'BookingDocumentPickerError'
  }
}
