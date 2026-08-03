import { describe, expect, it } from 'vitest'

import {
  createAcceptanceCoordinator,
  executeDeliveryFileAction,
  executeDeliverySaveAction,
  type DeliveryFileRuntime,
} from '../application/service-flow/delivery-actions'
import type {
  InspectionAcceptance,
  InspectionDownloadIntent,
} from '../data/service-flow/inspection-plan-api'

describe('F07 acceptance retry', () => {
  it('keeps one idempotency key across an unknown-result retry and clears it only after success', async () => {
    const calls: string[] = []
    let keySequence = 0
    let shouldFail = true
    const coordinator = createAcceptanceCoordinator(() => `accept-key-${++keySequence}`)
    const accept = async (_orderId: string, _version: number, key: string): Promise<InspectionAcceptance> => {
      calls.push(key)
      if (shouldFail) {
        shouldFail = false
        throw new Error('network result unknown')
      }
      return acceptance()
    }

    await expect(coordinator.accept(accept, 'order-1', 5)).rejects.toThrow('network result unknown')
    await expect(coordinator.accept(accept, 'order-1', 6)).resolves.toEqual(acceptance())
    await coordinator.accept(accept, 'order-1', 7)

    expect(calls).toEqual(['accept-key-1', 'accept-key-1', 'accept-key-2'])
  })
})

describe('F07 controlled file actions', () => {
  it('does not create a download intent when directory selection is cancelled', async () => {
    const createIntent = vi.fn(async () => intent('images.zip'))
    const runtime = runtimeWith({ pickSaveTarget: async () => null })

    await expect(executeDeliverySaveAction(createIntent, 'IMAGE_ARCHIVE', runtime))
      .resolves.toEqual({ status: 'CANCELLED' })

    expect(createIntent).not.toHaveBeenCalled()
    expect(runtime.savedPaths).toEqual([])
  })

  it('selects, requests, downloads and saves in that order', async () => {
    const order: string[] = []
    const target = { kind: 'ANDROID_TREE', directoryUri: 'content://tree/selected' } as const
    const runtime = runtimeWith({
      pickSaveTarget: async () => { order.push('pick'); return target },
      download: async () => { order.push('download'); return '/tmp/file' },
      save: async (_path, _name, receivedTarget) => {
        order.push('save')
        expect(receivedTarget).toEqual(target)
        return 'images.zip'
      },
    })
    const createIntent = async () => { order.push('intent'); return intent('images.zip') }

    await expect(executeDeliverySaveAction(createIntent, 'IMAGE_ARCHIVE', runtime))
      .resolves.toEqual({ status: 'SAVED', savedName: 'images.zip' })

    expect(order).toEqual(['pick', 'intent', 'download', 'save'])
  })

  it('does not create an output document when the network download fails', async () => {
    const save = vi.fn(async () => 'unexpected.zip')
    const runtime = runtimeWith({
      download: async () => { throw new Error('network failed') },
      save,
    })

    await expect(executeDeliverySaveAction(
      async () => intent('images.zip'),
      'IMAGE_ARCHIVE',
      runtime,
    )).rejects.toThrow('network failed')

    expect(save).not.toHaveBeenCalled()
  })

  it('reports a PDF preview failure instead of claiming success', async () => {
    const runtime = runtimeWith({
      preview: async () => { throw new Error('preview unsupported') },
    })

    await expect(executeDeliveryFileAction(
      intent('report.pdf'), 'PREVIEW', 'INSPECTION_REPORT', runtime,
    )).rejects.toThrow('preview unsupported')
  })

  it.each(['IMAGE_ARCHIVE', 'POINT_KML'] as const)(
    'persists %s through saveFile and returns the saved path',
    async (fileType) => {
      const runtime = runtimeWith({})
      await expect(executeDeliveryFileAction(
        intent(fileType === 'POINT_KML' ? 'points.kml' : 'images.zip'),
        'SAVE',
        fileType,
        runtime,
        { kind: 'APPLICATION_STORAGE' },
      )).resolves.toBe('/saved/delivery-file')
      expect(runtime.savedPaths).toEqual(['/tmp/delivery-file'])
    },
  )

  it('surfaces saveFile failure for the download-all ZIP', async () => {
    const runtime = runtimeWith({
      save: async () => { throw new Error('save failed') },
    })

    await expect(executeDeliveryFileAction(
      intent('all-files.zip'), 'SAVE', 'IMAGE_ARCHIVE', runtime,
      { kind: 'APPLICATION_STORAGE' },
    )).rejects.toThrow('save failed')
  })
})

function runtimeWith(overrides: Partial<DeliveryFileRuntime>): DeliveryFileRuntime & { savedPaths: string[] } {
  const savedPaths: string[] = []
  return {
    savedPaths,
    pickSaveTarget: async () => ({ kind: 'APPLICATION_STORAGE' }),
    download: async () => '/tmp/delivery-file',
    preview: async () => undefined,
    save: async (path) => { savedPaths.push(path); return '/saved/delivery-file' },
    ...overrides,
  }
}

function intent(fileName: string): InspectionDownloadIntent {
  return {
    method: 'GET',
    downloadUrl: 'https://files.example.test/short-lived',
    suggestedFileName: fileName,
    expiresAt: '2026-07-28T10:00:00Z',
  }
}

function acceptance(): InspectionAcceptance {
  return {
    id: 'acceptance-1',
    serviceOrderId: 'order-1',
    deliveryId: 'delivery-1',
    acceptedBy: 'customer-1',
    acceptedAt: '2026-07-28T09:00:00Z',
    orderStatus: 'ACCEPTED',
  }
}
