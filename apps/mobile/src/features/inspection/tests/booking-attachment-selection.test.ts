import { describe, expect, it } from 'vitest'

import type {
  BookingDocumentPicker,
  BookingDocumentPickerResult,
} from '../application/service-flow/booking-document-picker'
import {
  BookingAttachmentSelectionError,
  createBookingImageAttachments,
  pickBookingPdfAttachments,
} from '../application/service-flow/booking-attachment-selection'

describe('booking attachment selection', () => {
  it('keeps the actual JPEG and PNG formats selected by the user', () => {
    const attachments = createBookingImageAttachments(
      [
        { tempFilePath: '/tmp/site.JPG', size: 12 },
        { tempFilePath: '/tmp/detail.jpeg', size: 13 },
        { tempFilePath: '/tmp/map.png', size: 14 },
      ],
      { count: 3, createLocalId: (index) => `image-${index}` },
    )

    expect(attachments).toMatchObject([
      { localId: 'image-0', originalName: '巡检附件-1.jpg', contentType: 'image/jpeg' },
      { localId: 'image-1', originalName: '巡检附件-2.jpeg', contentType: 'image/jpeg' },
      { localId: 'image-2', originalName: '巡检附件-3.png', contentType: 'image/png' },
    ])
  })

  it.each(['/tmp/site.heic', '/tmp/site']) (
    'rejects unsupported or extensionless image %s before it enters the draft',
    (tempFilePath) => {
      expect(() => createBookingImageAttachments(
        [{ tempFilePath, size: 12 }],
        { count: 1, createLocalId: () => 'image-1' },
      )).toThrow(expect.objectContaining({
        code: 'BOOKING_IMAGE_FORMAT_UNSUPPORTED',
      }) as BookingAttachmentSelectionError)
    },
  )

  it.each([0, Number.NaN, 1_073_741_825])(
    'rejects an image with invalid size %s',
    (size) => {
      expect(() => createBookingImageAttachments(
        [{ tempFilePath: '/tmp/site.jpg', size }],
        { count: 1, createLocalId: () => 'image-1' },
      )).toThrow(expect.objectContaining({
        code: 'BOOKING_IMAGE_SIZE_INVALID',
      }) as BookingAttachmentSelectionError)
    },
  )

  it('rejects a defensive picker result above the remaining attachment capacity', () => {
    expect(() => createBookingImageAttachments(
      [
        { tempFilePath: '/tmp/one.jpg', size: 1 },
        { tempFilePath: '/tmp/two.png', size: 1 },
      ],
      { count: 1, createLocalId: () => 'image-1' },
    )).toThrow(expect.objectContaining({
      code: 'BOOKING_IMAGE_SELECTION_LIMIT',
    }) as BookingAttachmentSelectionError)
  })

  it('releases native PDF copies returned after the page unloads', async () => {
    let resolvePicker!: (result: BookingDocumentPickerResult) => void
    const releasedPaths: string[][] = []
    const picker: BookingDocumentPicker = {
      pickPdf: () => new Promise((resolve) => { resolvePicker = resolve }),
      releasePdfFiles: (paths) => releasedPaths.push([...paths]),
    }
    let isPageActive = true

    const selection = pickBookingPdfAttachments({
      picker,
      count: 1,
      isPageActive: () => isPageActive,
      createLocalId: () => 'pdf-1',
    })
    isPageActive = false
    resolvePicker({
      kind: 'selected',
      documents: [{
        filePath: '/private/cache/route.pdf',
        originalName: 'route.pdf',
        contentType: 'application/pdf',
        sizeBytes: 20,
      }],
    })

    await expect(selection).resolves.toEqual([])
    expect(releasedPaths).toEqual([['/private/cache/route.pdf']])
  })

  it('suppresses a picker error returned after the page unloads', async () => {
    let rejectPicker!: (error: Error) => void
    const picker: BookingDocumentPicker = {
      pickPdf: () => new Promise((_resolve, reject) => { rejectPicker = reject }),
      releasePdfFiles: () => {},
    }
    let isPageActive = true

    const selection = pickBookingPdfAttachments({
      picker,
      count: 1,
      isPageActive: () => isPageActive,
      createLocalId: () => 'pdf-1',
    })
    isPageActive = false
    rejectPicker(new Error('late native failure'))

    await expect(selection).resolves.toEqual([])
  })
})
