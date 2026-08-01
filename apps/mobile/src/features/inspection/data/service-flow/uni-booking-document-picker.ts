import {
  BookingDocumentPickerError,
  type BookingDocumentPicker,
  type BookingDocumentPickerResult,
  type BookingSelectedDocument,
} from '../../application/service-flow/booking-document-picker'

interface UniChooseFileTempFile {
  path?: string
  name?: string
  type?: string
  size?: number
}

interface UniChooseFileOptions {
  count: number
  type: 'all'
  extension: string[]
  success: (result: { tempFiles: UniChooseFileTempFile | UniChooseFileTempFile[] }) => void
  fail: (error: { errMsg?: string }) => void
}

export type UniChooseFile = (options: UniChooseFileOptions) => void

export interface NativeBookingPdfPicker {
  (options: {
    count: number
    success: (result: { documents: BookingSelectedDocument[] }) => void
    fail: (error: { errMsg?: string }) => void
  }): void
}

export type NativeBookingPdfReleaser = (filePaths: string[]) => void

const MAX_PDF_SIZE_BYTES = 1_073_741_824

function mapPdfFile(file: UniChooseFileTempFile): BookingSelectedDocument {
  const originalName = file.name?.trim() ?? ''
  const filePath = file.path?.trim() ?? ''
  const suppliedContentType = file.type?.trim().toLowerCase()
  if (!filePath || !originalName.toLowerCase().endsWith('.pdf')) {
    throw new BookingDocumentPickerError(
      'BOOKING_DOCUMENT_NOT_PDF',
      '请选择 PDF 格式的文件',
    )
  }
  if (
    suppliedContentType
    && suppliedContentType !== 'application/pdf'
    && suppliedContentType !== 'file'
  ) {
    throw new BookingDocumentPickerError(
      'BOOKING_DOCUMENT_NOT_PDF',
      '请选择 PDF 格式的文件',
    )
  }
  return validatePdfDocument({
    filePath,
    originalName,
    contentType: 'application/pdf',
    sizeBytes: file.size ?? Number.NaN,
  })
}

function validatePdfDocument(document: BookingSelectedDocument): BookingSelectedDocument {
  if (
    !document.filePath.trim()
    || !document.originalName.trim().toLowerCase().endsWith('.pdf')
    || document.contentType !== 'application/pdf'
  ) {
    throw new BookingDocumentPickerError(
      'BOOKING_DOCUMENT_NOT_PDF',
      '请选择 PDF 格式的文件',
    )
  }
  if (
    !Number.isFinite(document.sizeBytes)
    || document.sizeBytes < 1
    || document.sizeBytes > MAX_PDF_SIZE_BYTES
  ) {
    throw new BookingDocumentPickerError(
      'BOOKING_DOCUMENT_SIZE_INVALID',
      'PDF 文件大小必须在 1 字节到 1 GiB 之间',
    )
  }
  return {
    ...document,
    filePath: document.filePath.trim(),
    originalName: document.originalName.trim(),
  }
}

function toPickerResult(
  documents: BookingSelectedDocument[],
  count: number,
): BookingDocumentPickerResult {
  if (documents.length === 0) {
    return { kind: 'cancelled' }
  }
  if (documents.length > count) {
    throw new BookingDocumentPickerError(
      'BOOKING_DOCUMENT_SELECTION_LIMIT',
      `一次最多选择 ${count} 个 PDF 文件`,
    )
  }
  return {
    kind: 'selected',
    documents: documents.map(validatePdfDocument),
  }
}

function isCancellation(error: { errMsg?: string }): boolean {
  return error.errMsg?.toLowerCase().includes('cancel') ?? false
}

function nativeErrorMessage(error: { errMsg?: string }): string {
  return error.errMsg?.replace(/^[A-Z_]+:\s*/, '').trim() ?? ''
}

function mapNativePickerError(error: { errMsg?: string }): BookingDocumentPickerError {
  const nativeMessage = error.errMsg?.trim() ?? ''
  const message = nativeErrorMessage(error)

  if (nativeMessage.startsWith('BOOKING_DOCUMENT_SELECTION_LIMIT:')) {
    return new BookingDocumentPickerError(
      'BOOKING_DOCUMENT_SELECTION_LIMIT',
      message || '一次最多选择允许数量的 PDF 文件',
    )
  }
  if (nativeMessage.startsWith('BOOKING_DOCUMENT_SIZE_INVALID:')) {
    return new BookingDocumentPickerError(
      'BOOKING_DOCUMENT_SIZE_INVALID',
      message || 'PDF 文件大小不符合要求',
    )
  }
  if (nativeMessage.startsWith('BOOKING_DOCUMENT_NOT_PDF:')) {
    return new BookingDocumentPickerError(
      'BOOKING_DOCUMENT_NOT_PDF',
      message || '请选择 PDF 格式的文件',
    )
  }
  if (nativeMessage.startsWith('BOOKING_DOCUMENT_PICKER_UNAVAILABLE:')) {
    return new BookingDocumentPickerError(
      'BOOKING_DOCUMENT_PICKER_UNAVAILABLE',
      message || '当前无法使用 PDF 文件选择器，请稍后重试',
    )
  }

  return new BookingDocumentPickerError(
    'BOOKING_DOCUMENT_PICKER_FAILED',
    '无法选择 PDF 文件，请稍后重试',
  )
}

function releaseNativeDocuments(
  nativeReleaser: NativeBookingPdfReleaser | undefined,
  documents: readonly BookingSelectedDocument[],
): void {
  if (!nativeReleaser) {
    return
  }
  const filePaths = [...new Set(
    documents.map((document) => document.filePath.trim()).filter(Boolean),
  )]
  if (filePaths.length === 0) {
    return
  }
  try {
    nativeReleaser(filePaths)
  } catch {
    // Preserve the validation error; native cleanup is best-effort.
  }
}

export function createUniBookingDocumentPicker(
  chooseFile: UniChooseFile | undefined,
  nativePicker?: NativeBookingPdfPicker,
  nativeReleaser?: NativeBookingPdfReleaser,
): BookingDocumentPicker {
  return {
    pickPdf({ count }): Promise<BookingDocumentPickerResult> {
      if (nativePicker) {
        return new Promise((resolve, reject) => {
          try {
            nativePicker({
              count,
              success: ({ documents }) => {
                try {
                  resolve(toPickerResult(documents, count))
                } catch (error) {
                  releaseNativeDocuments(nativeReleaser, documents)
                  reject(error)
                }
              },
              fail: (error) => {
                if (isCancellation(error)) {
                  resolve({ kind: 'cancelled' })
                  return
                }
                reject(mapNativePickerError(error))
              },
            })
          } catch {
            reject(new BookingDocumentPickerError(
              'BOOKING_DOCUMENT_PICKER_FAILED',
              '无法打开 PDF 文件选择器，请稍后重试',
            ))
          }
        })
      }
      if (!chooseFile) {
        return Promise.reject(new BookingDocumentPickerError(
          'BOOKING_DOCUMENT_PICKER_UNAVAILABLE',
          '当前设备暂不支持选择 PDF 文件，请更换设备后重试',
        ))
      }

      return new Promise((resolve, reject) => {
        try {
          chooseFile({
            count,
            type: 'all',
            extension: ['pdf'],
            success: ({ tempFiles }) => {
              try {
                const selectedFiles = Array.isArray(tempFiles) ? tempFiles : [tempFiles]
                resolve(toPickerResult(selectedFiles.map(mapPdfFile), count))
              } catch (error) {
                reject(error)
              }
            },
            fail: (error) => {
              if (isCancellation(error)) {
                resolve({ kind: 'cancelled' })
                return
              }
              reject(new BookingDocumentPickerError(
                'BOOKING_DOCUMENT_PICKER_FAILED',
                '无法选择 PDF 文件，请检查文件访问权限后重试',
              ))
            },
          })
        } catch {
          reject(new BookingDocumentPickerError(
            'BOOKING_DOCUMENT_PICKER_FAILED',
            '无法打开 PDF 文件选择器，请稍后重试',
          ))
        }
      })
    },
    releasePdfFiles(filePaths: readonly string[]): void {
      if (!nativeReleaser) {
        return
      }
      const uniquePaths = [...new Set(filePaths.map((path) => path.trim()).filter(Boolean))]
      if (uniquePaths.length > 0) {
        nativeReleaser(uniquePaths)
      }
    },
  }
}

const platformChooseFile = typeof uni === 'undefined'
  ? undefined
  : (uni as unknown as { chooseFile?: UniChooseFile }).chooseFile

export const uniBookingDocumentPicker = createUniBookingDocumentPicker(platformChooseFile)
