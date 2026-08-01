import { describe, expect, it } from 'vitest'
import androidPickerSource from '../../../../uni_modules/inspection-document-picker/utssdk/app-android/index.uts?raw'
import bookingPageSource from '../presentation/booking/pages/InspectionBookingPage.vue?raw'

import type { BookingDocumentPickerError } from '../application/service-flow/booking-document-picker'
import {
  createUniBookingDocumentPicker,
  type NativeBookingPdfPicker,
  type UniChooseFile,
} from '../data/service-flow/uni-booking-document-picker'

const iosPickerModules = import.meta.glob(
  '../../../../uni_modules/inspection-document-picker/utssdk/app-ios/index.uts',
  { eager: true, query: '?raw', import: 'default' },
) as Record<string, string>
const iosPickerSource = Object.values(iosPickerModules)[0] ?? ''

function createChooseFile(
  callback: (options: Parameters<UniChooseFile>[0]) => void,
): UniChooseFile {
  return (options) => callback(options)
}

function createNativePicker(
  callback: (options: Parameters<NativeBookingPdfPicker>[0]) => void,
): NativeBookingPdfPicker {
  return (options) => callback(options)
}

describe('booking PDF document picker', () => {
  it('forwards remaining capacity and returns selected PDFs as typed documents', async () => {
    let requestedCount = 0
    let requestedExtensions: string[] = []
    let requestedType = ''
    const picker = createUniBookingDocumentPicker(createChooseFile((options) => {
      requestedCount = options.count
      requestedExtensions = options.extension
      requestedType = options.type
      options.success({
        tempFiles: [{
          path: '/tmp/route.pdf',
          name: 'route.pdf',
          type: 'application/pdf',
          size: 321,
        }],
      })
    }))

    await expect(picker.pickPdf({ count: 4 })).resolves.toEqual({
      kind: 'selected',
      documents: [{
        filePath: '/tmp/route.pdf',
        originalName: 'route.pdf',
        contentType: 'application/pdf',
        sizeBytes: 321,
      }],
    })
    expect(requestedCount).toBe(4)
    expect(requestedType).toBe('all')
    expect(requestedExtensions).toEqual(['pdf'])
  })

  it('distinguishes cancellation so the caller can leave the draft unchanged', async () => {
    const picker = createUniBookingDocumentPicker(createChooseFile((options) => {
      options.fail({ errMsg: 'chooseFile:fail cancel' })
    }))

    await expect(picker.pickPdf({ count: 1 })).resolves.toEqual({ kind: 'cancelled' })
  })

  it('accepts Android category file metadata when the selected name is a PDF', async () => {
    const picker = createUniBookingDocumentPicker(createChooseFile((options) => {
      options.success({
        tempFiles: [{
          path: '/tmp/route.pdf',
          name: 'route.pdf',
          type: 'file',
          size: 321,
        }],
      })
    }))

    await expect(picker.pickPdf({ count: 1 })).resolves.toMatchObject({
      kind: 'selected',
      documents: [{ originalName: 'route.pdf', contentType: 'application/pdf' }],
    })
  })

  it('rejects a selected non-PDF before it can enter the draft', async () => {
    const picker = createUniBookingDocumentPicker(createChooseFile((options) => {
      options.success({
        tempFiles: [{
          path: '/tmp/route.jpg',
          name: 'route.jpg',
          type: 'image/jpeg',
          size: 321,
        }],
      })
    }))

    await expect(picker.pickPdf({ count: 1 })).rejects.toMatchObject({
      code: 'BOOKING_DOCUMENT_NOT_PDF',
    } satisfies Partial<BookingDocumentPickerError>)
  })

  it('turns real picker failures into a visible error outcome for the page', async () => {
    const picker = createUniBookingDocumentPicker(createChooseFile((options) => {
      options.fail({ errMsg: 'chooseFile:fail permission denied' })
    }))

    await expect(picker.pickPdf({ count: 1 })).rejects.toMatchObject({
      code: 'BOOKING_DOCUMENT_PICKER_FAILED',
    } satisfies Partial<BookingDocumentPickerError>)
  })

  it('rejects unavailable platform support instead of silently doing nothing', async () => {
    const picker = createUniBookingDocumentPicker(undefined)

    await expect(picker.pickPdf({ count: 1 })).rejects.toMatchObject({
      code: 'BOOKING_DOCUMENT_PICKER_UNAVAILABLE',
    } satisfies Partial<BookingDocumentPickerError>)
  })

  it('treats an empty picker success as cancellation', async () => {
    const picker = createUniBookingDocumentPicker(createChooseFile((options) => {
      options.success({ tempFiles: [] })
    }))

    await expect(picker.pickPdf({ count: 1 })).resolves.toEqual({ kind: 'cancelled' })
  })

  it('rejects picker results above the remaining attachment capacity', async () => {
    const picker = createUniBookingDocumentPicker(createChooseFile((options) => {
      options.success({
        tempFiles: [
          { path: '/tmp/one.pdf', name: 'one.pdf', size: 1 },
          { path: '/tmp/two.pdf', name: 'two.pdf', size: 1 },
        ],
      })
    }))

    await expect(picker.pickPdf({ count: 1 })).rejects.toMatchObject({
      code: 'BOOKING_DOCUMENT_SELECTION_LIMIT',
    } satisfies Partial<BookingDocumentPickerError>)
  })

  it.each([0, Number.NaN, 1_073_741_825])(
    'rejects a PDF with invalid size %s',
    async (size) => {
      const picker = createUniBookingDocumentPicker(createChooseFile((options) => {
        options.success({
          tempFiles: [{ path: '/tmp/route.pdf', name: 'route.pdf', size }],
        })
      }))

      await expect(picker.pickPdf({ count: 1 })).rejects.toMatchObject({
        code: 'BOOKING_DOCUMENT_SIZE_INVALID',
      } satisfies Partial<BookingDocumentPickerError>)
    },
  )

  it('uses the Android system picker result instead of uni.chooseFile', async () => {
    let requestedCount = 0
    const picker = createUniBookingDocumentPicker(
      undefined,
      createNativePicker((options) => {
        requestedCount = options.count
        options.success({
          documents: [{
            filePath: '/data/user/0/app/cache/route.pdf',
            originalName: 'route.pdf',
            contentType: 'application/pdf',
            sizeBytes: 512,
          }],
        })
      }),
    )

    await expect(picker.pickPdf({ count: 3 })).resolves.toMatchObject({
      kind: 'selected',
      documents: [{ filePath: '/data/user/0/app/cache/route.pdf' }],
    })
    expect(requestedCount).toBe(3)
  })

  it('releases native temporary files when native result validation fails', async () => {
    const releasedPaths: string[][] = []
    const picker = createUniBookingDocumentPicker(
      undefined,
      createNativePicker((options) => {
        options.success({
          documents: [{
            filePath: '/data/user/0/app/cache/invalid.pdf',
            originalName: 'invalid.pdf',
            contentType: 'application/pdf',
            sizeBytes: 0,
          }],
        })
      }),
      (paths) => releasedPaths.push(paths),
    )

    await expect(picker.pickPdf({ count: 1 })).rejects.toMatchObject({
      code: 'BOOKING_DOCUMENT_SIZE_INVALID',
    } satisfies Partial<BookingDocumentPickerError>)
    expect(releasedPaths).toEqual([['/data/user/0/app/cache/invalid.pdf']])
  })

  it.each([
    ['BOOKING_DOCUMENT_SELECTION_LIMIT: 可选择的附件数量不足', 'BOOKING_DOCUMENT_SELECTION_LIMIT'],
    ['BOOKING_DOCUMENT_SIZE_INVALID: PDF 文件大小不符合要求', 'BOOKING_DOCUMENT_SIZE_INVALID'],
    ['BOOKING_DOCUMENT_NOT_PDF: 请选择 PDF 格式的文件', 'BOOKING_DOCUMENT_NOT_PDF'],
    ['BOOKING_DOCUMENT_PICKER_UNAVAILABLE: 正在处理已选择的 PDF 文件', 'BOOKING_DOCUMENT_PICKER_UNAVAILABLE'],
    ['native provider failed unexpectedly', 'BOOKING_DOCUMENT_PICKER_FAILED'],
  ] as const)('maps Android native error %s to %s', async (errMsg, code) => {
    const picker = createUniBookingDocumentPicker(
      undefined,
      createNativePicker((options) => options.fail({ errMsg })),
    )

    await expect(picker.pickPdf({ count: 1 })).rejects.toMatchObject({ code })
  })

  it('releases each Android private cache path once and keeps H5 release as a no-op', () => {
    const releasedPaths: string[][] = []
    const androidPicker = createUniBookingDocumentPicker(
      undefined,
      undefined,
      (paths) => releasedPaths.push(paths),
    )
    const h5Picker = createUniBookingDocumentPicker(createChooseFile(() => {}))

    androidPicker.releasePdfFiles([
      '/data/user/0/app/cache/inspection-pdf/route.pdf',
      '/data/user/0/app/cache/inspection-pdf/route.pdf',
      '/data/user/0/app/cache/inspection-pdf/site.pdf',
    ])
    h5Picker.releasePdfFiles(['/tmp/route.pdf'])

    expect(releasedPaths).toEqual([[
      '/data/user/0/app/cache/inspection-pdf/route.pdf',
      '/data/user/0/app/cache/inspection-pdf/site.pdf',
    ]])
  })

  it('keeps Android cleanup and activity listeners within the private picker lifecycle', () => {
    expect(androidPickerSource).toContain("UTSAndroid.onAppActivityResult(resultListener)")
    expect(androidPickerSource).toContain("UTSAndroid.onAppActivityDestroy(destroyListener)")
    expect(androidPickerSource).toContain('UTSAndroid.offAppActivityResult(resultListener)')
    expect(androidPickerSource).toContain('UTSAndroid.offAppActivityDestroy(destroyListener)')
    expect(androidPickerSource).toContain("new File(context.getCacheDir(), PRIVATE_CACHE_DIRECTORY)")
    expect(androidPickerSource).toContain('candidatePath.startsWith(`${rootPath}/`)')
  })

  it('wires App-iOS to the same typed picker and release lifecycle', () => {
    expect(bookingPageSource).toContain('// #ifdef APP-IOS')
    expect(bookingPageSource).toContain(
      "import { pickInspectionPdfDocuments, releaseInspectionPdfDocuments } from '@/uni_modules/inspection-document-picker'",
    )
    expect(bookingPageSource.replace(/\r\n/g, '\n')).toContain(
      'pickInspectionPdfDocuments,\n  releaseInspectionPdfDocuments,',
    )
  })

  it('uses the iOS system PDF picker with capacity and lifecycle guards', () => {
    expect(iosPickerSource).toContain("UIDocumentPickerViewController(allowedUTIs = ['com.adobe.pdf'], mode = UIDocumentPickerMode.import)")
    expect(iosPickerSource).toContain('picker.allowsMultipleSelection = options.count > 1')
    expect(iosPickerSource).toContain('urls.length > this.options.count')
    expect(iosPickerSource).toContain('activePickerDelegate')
    expect(iosPickerSource).toContain('pickerInFlight')
    expect(iosPickerSource).toContain('documentPickerWasCancelled')
  })

  it('copies iOS security-scoped files into the private temporary directory and safely releases them', () => {
    expect(iosPickerSource).toContain('startAccessingSecurityScopedResource()')
    expect(iosPickerSource).toContain('stopAccessingSecurityScopedResource()')
    expect(iosPickerSource).toContain('FileManager.default.copyItem')
    expect(iosPickerSource).toContain('MAX_PDF_SIZE_BYTES = 1_073_741_824')
    expect(iosPickerSource).toContain('standardizedFileURL.path')
    expect(iosPickerSource).toContain('candidatePath.startsWith(`${rootPath}/`)')
    expect(iosPickerSource).toContain('removeCopiedDocuments(documents)')
    expect(iosPickerSource).not.toContain('Data(contentsOf')
  })
})
