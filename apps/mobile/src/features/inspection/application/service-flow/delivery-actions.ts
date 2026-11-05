import type {
  InspectionAcceptance,
  InspectionDeliveryFileType,
  InspectionDownloadIntent,
} from '../../data/service-flow/inspection-plan-api'
import type { DeliverySaveTarget } from '../../data/service-flow/delivery-file-storage'

export type AcceptDeliveryPort = (
  serviceOrderId: string,
  visitId: string,
  version: number,
  idempotencyKey: string,
) => Promise<InspectionAcceptance>

export interface AcceptanceCoordinator {
  accept(
    port: AcceptDeliveryPort,
    serviceOrderId: string,
    visitId: string,
    version: number,
  ): Promise<InspectionAcceptance>
}

export function createAcceptanceCoordinator(
  createIdempotencyKey: () => string,
): AcceptanceCoordinator {
  // A failed attempt keeps its key so the retry replays; each visit has its own.
  const pendingIdempotencyKeys = new Map<string, string>()

  return {
    async accept(port, serviceOrderId, visitId, version) {
      const idempotencyKey = pendingIdempotencyKeys.get(visitId) ?? createIdempotencyKey()
      pendingIdempotencyKeys.set(visitId, idempotencyKey)
      const acceptance = await port(
        serviceOrderId,
        visitId,
        version,
        idempotencyKey,
      )
      pendingIdempotencyKeys.delete(visitId)
      return acceptance
    },
  }
}

export type DeliveryFileAction = 'PREVIEW' | 'SAVE'

export interface DeliveryFileRuntime {
  pickSaveTarget(): Promise<DeliverySaveTarget | null>
  download(url: string): Promise<string>
  preview(filePath: string): Promise<void>
  save(
    filePath: string,
    suggestedFileName: string,
    target: DeliverySaveTarget,
  ): Promise<string>
}

export type DeliverySaveOutcome =
  | { status: 'CANCELLED' }
  | { status: 'SAVED'; savedName: string }

export async function executeDeliverySaveAction(
  createIntent: () => Promise<InspectionDownloadIntent>,
  fileType: InspectionDeliveryFileType,
  runtime: DeliveryFileRuntime,
): Promise<DeliverySaveOutcome> {
  const target = await runtime.pickSaveTarget()
  if (!target) return { status: 'CANCELLED' }

  const intent = await createIntent()
  const savedName = await executeDeliveryFileAction(
    intent,
    'SAVE',
    fileType,
    runtime,
    target,
  )
  if (!savedName) throw new Error('文件保存结果无效')
  return { status: 'SAVED', savedName }
}

export async function executeDeliveryFileAction(
  intent: InspectionDownloadIntent,
  action: DeliveryFileAction,
  fileType: InspectionDeliveryFileType,
  runtime: DeliveryFileRuntime,
  saveTarget?: DeliverySaveTarget,
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
  if (!saveTarget) throw new Error('请选择文件保存位置')
  return runtime.save(temporaryPath, intent.suggestedFileName, saveTarget)
}
