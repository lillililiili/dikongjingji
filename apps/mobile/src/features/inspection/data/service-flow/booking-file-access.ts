import type { BookingFileAccessPort } from '../../application/service-flow/booking-crypto'

export type BookingFileAccessErrorCode = 'UNAVAILABLE' | 'READ_FAILED' | 'MALFORMED'

export class BookingFileAccessError extends Error {
  constructor(readonly code: BookingFileAccessErrorCode, message: string) {
    super(message)
  }
}

function unreadableFile(): BookingFileAccessError {
  return new BookingFileAccessError('READ_FAILED', '无法读取待上传附件')
}

function malformedFileData(): BookingFileAccessError {
  return new BookingFileAccessError('MALFORMED', '附件数据格式错误')
}

function fileAccessUnavailable(): BookingFileAccessError {
  return new BookingFileAccessError('UNAVAILABLE', '当前平台未配置文件读取能力')
}

function toBytes(data: unknown): Uint8Array {
  if (data instanceof ArrayBuffer) return new Uint8Array(data)
  if (ArrayBuffer.isView(data)) {
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength).slice()
  }
  throw unreadableFile()
}

function decodeDataUrl(dataUrl: string): Uint8Array {
  const match = /^data:[^;,]+(?:;[^,]*)?;base64,([A-Za-z0-9+/]*={0,2})$/.exec(dataUrl)
  if (!match || match[1].length % 4 !== 0) throw malformedFileData()

  try {
    const binary = atob(match[1])
    return Uint8Array.from(binary, (character) => character.charCodeAt(0))
  } catch {
    throw malformedFileData()
  }
}

function readWithUniFileSystemManager(filePath: string): Promise<Uint8Array> | null {
  const manager = uni.getFileSystemManager?.()
  if (!manager) return null

  return new Promise((resolve, reject) => {
    manager.readFile({
      filePath,
      success: ({ data }) => {
        try {
          resolve(toBytes(data))
        } catch (error) {
          reject(error)
        }
      },
      fail: () => reject(unreadableFile()),
    })
  })
}

function readWithAppPlusFileReader(filePath: string): Promise<Uint8Array> {
  return new Promise((resolve, reject) => {
    if (typeof plus === 'undefined' || !plus.io?.resolveLocalFileSystemURL || !plus.io.FileReader) {
      reject(fileAccessUnavailable())
      return
    }

    plus.io.resolveLocalFileSystemURL(
      filePath,
      (entry) => {
        const fileEntry = entry as unknown as PlusIoFileEntry
        fileEntry.file(
          (file) => {
            const reader = new plus.io.FileReader()
            reader.onerror = () => reject(unreadableFile())
            reader.onloadend = () => {
              if (typeof reader.result !== 'string') {
                reject(unreadableFile())
                return
              }
              try {
                resolve(decodeDataUrl(reader.result))
              } catch (error) {
                reject(error)
              }
            }
            reader.readAsDataURL(file)
          },
          () => reject(unreadableFile()),
        )
      },
      () => reject(unreadableFile()),
    )
  })
}

export const uniBookingFileAccessPort: BookingFileAccessPort = {
  readBytes(filePath) {
    return readWithUniFileSystemManager(filePath) ?? readWithAppPlusFileReader(filePath)
  },
}
