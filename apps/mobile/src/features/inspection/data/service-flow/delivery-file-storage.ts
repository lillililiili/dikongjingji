const CONTENT_TYPES: Record<string, string> = {
  zip: 'application/zip',
  kml: 'application/vnd.google-earth.kml+xml',
  xlsx: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  pdf: 'application/pdf',
}

export type DeliverySaveTarget =
  | { kind: 'ANDROID_TREE'; directoryUri: string }
  | { kind: 'APPLICATION_STORAGE' }

export interface DeliveryDirectoryStorageBridge {
  pick(options: {
    success?: (result: { directoryUri: string }) => void
    fail?: (error: { code: string; errMsg: string }) => void
  }): void
  save(options: {
    directoryUri: string
    sourcePath: string
    suggestedFileName: string
    contentType: string
    success?: (result: { finalDisplayName: string }) => void
    fail?: (error: { code: string; errMsg: string }) => void
  }): void
}

export interface DeliveryFileStorage {
  pickSaveTarget(): Promise<DeliverySaveTarget | null>
  save(
    filePath: string,
    suggestedFileName: string,
    target: DeliverySaveTarget,
  ): Promise<string>
}

function isAndroidApp(): boolean {
  return typeof plus !== 'undefined' && plus.os.name === 'Android'
}

function sanitizeFileName(value: string): string {
  const fileName = value.trim().replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')
  if (!fileName) throw new Error('交付文件名称无效')
  return fileName
}

function saveToApplicationStorage(filePath: string): Promise<string> {
  return new Promise((resolve, reject) => {
    uni.saveFile({
      tempFilePath: filePath,
      success: ({ savedFilePath }) => savedFilePath
        ? resolve(savedFilePath)
        : reject(new Error('文件保存失败，请检查设备存储权限')),
      fail: () => reject(new Error('文件保存失败，请检查设备存储权限')),
    })
  })
}

export function createDeliveryFileStorage(
  bridge: DeliveryDirectoryStorageBridge,
): DeliveryFileStorage {
  return {
    pickSaveTarget() {
      if (!isAndroidApp()) {
        return Promise.resolve({ kind: 'APPLICATION_STORAGE' })
      }
      return new Promise((resolve, reject) => {
        bridge.pick({
          success: ({ directoryUri }) => resolve({
            kind: 'ANDROID_TREE',
            directoryUri,
          }),
          fail: ({ code, errMsg }) => code === 'DELIVERY_DIRECTORY_PICKER_CANCELLED'
            ? resolve(null)
            : reject(new Error(errMsg)),
        })
      })
    },
    save(filePath, suggestedFileName, target) {
      const fileName = sanitizeFileName(suggestedFileName)
      if (target.kind === 'APPLICATION_STORAGE') {
        return saveToApplicationStorage(filePath)
      }
      const extension = fileName.split('.').pop()?.toLowerCase() ?? ''
      const sourcePath = filePath.startsWith('file://')
        ? filePath.slice('file://'.length)
        : filePath.startsWith('/')
          ? filePath
          : plus.io.convertLocalFileSystemURL(filePath)
      return new Promise((resolve, reject) => {
        bridge.save({
          directoryUri: target.directoryUri,
          sourcePath,
          suggestedFileName: fileName,
          contentType: CONTENT_TYPES[extension] ?? 'application/octet-stream',
          success: ({ finalDisplayName }) => resolve(finalDisplayName),
          fail: ({ errMsg }) => reject(new Error(errMsg)),
        })
      })
    },
  }
}

export async function saveDeliveryFile(
  temporaryPath: string,
  suggestedFileName: string,
): Promise<string> {
  // Temporary compatibility export until Task3 injects the UTS bridge from the delivery page.
  sanitizeFileName(suggestedFileName)
  return saveToApplicationStorage(temporaryPath)
}
