import type {
  InspectionAcceptance,
  InspectionDeliveryFileType,
  InspectionDownloadIntent,
} from '../../data/service-flow/inspection-plan-api'

export type AcceptDeliveryPort = (
  serviceOrderId: string,
  version: number,
  idempotencyKey: string,
) => Promise<InspectionAcceptance>

export interface AcceptanceCoordinator {
  accept(
    port: AcceptDeliveryPort,
    serviceOrderId: string,
    version: number,
  ): Promise<InspectionAcceptance>
}

export function createAcceptanceCoordinator(
  createIdempotencyKey: () => string,
): AcceptanceCoordinator {
  let pendingIdempotencyKey: string | null = null

  return {
    async accept(port, serviceOrderId, version) {
      pendingIdempotencyKey ??= createIdempotencyKey()
      const acceptance = await port(
        serviceOrderId,
        version,
        pendingIdempotencyKey,
      )
      pendingIdempotencyKey = null
      return acceptance
    },
  }
}

export type DeliveryFileAction = 'PREVIEW' | 'SAVE'

export interface DeliveryFileRuntime {
  download(url: string): Promise<string>
  preview(filePath: string): Promise<void>
  save(filePath: string, suggestedFileName: string): Promise<string>
}

export async function executeDeliveryFileAction(
  intent: InspectionDownloadIntent,
  action: DeliveryFileAction,
  fileType: InspectionDeliveryFileType,
  runtime: DeliveryFileRuntime,
): Promise<string | null> {
  if (intent.method !== 'GET' || !intent.downloadUrl.trim()) {
    throw new Error('下载地址无效，请重新获取')
  }
  if (action === 'PREVIEW'
    && (fileType === 'IMAGE_ARCHIVE' || fileType === 'POINT_KML')) {
    throw new Error('该文件类型不支持在线预览，请下载后查看')
  }

  const temporaryPath = await runtime.download(intent.downloadUrl)
  if (action === 'PREVIEW') {
    await runtime.preview(temporaryPath)
    return null
  }
  return runtime.save(temporaryPath, intent.suggestedFileName)
}
