import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'

import { KIND_LABELS, type ImportApi, type OpsImportBatch } from './import-api'

/**
 * 导入一个预检通过的批次：先确认（会写入、一个事务、默认草稿 / 停用），成功后提示留痕。
 * 返回导入后的批次；取消或失败返回 null（失败已经提示过）。
 */
export async function runImport(api: ImportApi, batch: OpsImportBatch): Promise<OpsImportBatch | null> {
  const confirmed = await confirmAction({
    title: '确认导入',
    message: `把这批“${KIND_LABELS[batch.kind]}”（${batch.rowCount} 行）写进系统？在一个事务里写，失败不留半截数据；`
      + '同一编号已导入过的会更新。表里填的是草稿、停用的，App 上先看不到，核对后再逐条上架。',
    confirmText: '导入',
  })
  if (!confirmed) return null
  try {
    const imported = await api.run(batch.batchId)
    notifyRecorded(`导入${KIND_LABELS[batch.kind]}`, '导入记录')
    return imported
  } catch (failure) {
    showError(failure)
    return null
  }
}
