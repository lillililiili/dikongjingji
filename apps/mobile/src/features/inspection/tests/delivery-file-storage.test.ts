import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  createDeliveryFileStorage,
  type DeliveryDirectoryStorageBridge,
} from '../data/service-flow/delivery-file-storage'

describe('delivery file storage', () => {
  afterEach(() => { vi.unstubAllGlobals() })

  it('returns null when the Android directory picker is cancelled', async () => {
    vi.stubGlobal('plus', { os: { name: 'Android' } })
    const storage = createDeliveryFileStorage(bridgeWith({
      pick: (options) => options.fail?.({
        code: 'DELIVERY_DIRECTORY_PICKER_CANCELLED',
        errMsg: '已取消保存',
      }),
    }))

    await expect(storage.pickSaveTarget()).resolves.toBeNull()
  })

  it('surfaces a picker-unavailable failure', async () => {
    vi.stubGlobal('plus', { os: { name: 'Android' } })
    const storage = createDeliveryFileStorage(bridgeWith({
      pick: (options) => options.fail?.({
        code: 'DELIVERY_DIRECTORY_PICKER_UNAVAILABLE',
        errMsg: '当前设备无法打开文件夹选择器',
      }),
    }))

    await expect(storage.pickSaveTarget())
      .rejects.toThrow('当前设备无法打开文件夹选择器')
  })

  it('passes the selected URI and absolute source path to Android storage', async () => {
    const calls: unknown[] = []
    vi.stubGlobal('plus', {
      os: { name: 'Android' },
      io: { convertLocalFileSystemURL: () => '/absolute/file.zip' },
    })
    const storage = createDeliveryFileStorage(bridgeWith({ calls }))
    const target = await storage.pickSaveTarget()

    await expect(storage.save('_doc/uniapp_temp/file.zip', '现场影像.zip', target!))
      .resolves.toBe('现场影像.zip')

    expect(calls).toContainEqual(expect.objectContaining({
      directoryUri: 'content://tree/selected',
      sourcePath: '/absolute/file.zip',
      suggestedFileName: '现场影像.zip',
      contentType: 'application/zip',
    }))
  })

  it.each([
    ['archive.zip', 'application/zip'],
    ['points.kml', 'application/vnd.google-earth.kml+xml'],
    ['findings.xlsx', 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'],
    ['report.pdf', 'application/pdf'],
    ['unknown.bin', 'application/octet-stream'],
  ])('maps %s to its Android content type', async (fileName, contentType) => {
    const calls: Array<{ contentType: string }> = []
    vi.stubGlobal('plus', {
      os: { name: 'Android' },
      io: { convertLocalFileSystemURL: () => '/absolute/file' },
    })
    const storage = createDeliveryFileStorage(bridgeWith({ calls }))
    const target = await storage.pickSaveTarget()

    await storage.save('_doc/uniapp_temp/file', fileName, target!)

    expect(calls).toContainEqual(expect.objectContaining({ contentType }))
  })

  it('keeps the current uni.saveFile fallback outside Android', async () => {
    const bridge = bridgeWith()
    const pick = vi.spyOn(bridge, 'pick')
    const saveFile = vi.fn((options: {
      success: (result: { savedFilePath: string }) => void
    }) => options.success({ savedFilePath: '_doc/uniapp_save/file.zip' }))
    vi.stubGlobal('plus', { os: { name: 'iOS' } })
    vi.stubGlobal('uni', { saveFile })

    const storage = createDeliveryFileStorage(bridge)
    const target = await storage.pickSaveTarget()

    await expect(storage.save('/tmp/file.zip', '现场影像.zip', target!))
      .resolves.toBe('_doc/uniapp_save/file.zip')

    expect(pick).not.toHaveBeenCalled()
    expect(saveFile).toHaveBeenCalled()
  })
})

function bridgeWith(overrides: {
  pick?: DeliveryDirectoryStorageBridge['pick']
  save?: DeliveryDirectoryStorageBridge['save']
  calls?: unknown[]
} = {}): DeliveryDirectoryStorageBridge {
  return {
    pick: overrides.pick ?? ((options) => options.success?.({
      directoryUri: 'content://tree/selected',
    })),
    save: overrides.save ?? ((options) => {
      overrides.calls?.push({
        directoryUri: options.directoryUri,
        sourcePath: options.sourcePath,
        suggestedFileName: options.suggestedFileName,
        contentType: options.contentType,
      })
      options.success?.({ finalDisplayName: options.suggestedFileName })
    }),
  }
}
