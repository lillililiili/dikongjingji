import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'

import { errorMessageWithTrace } from '@/core/http/api-error'

export interface ConfirmOptions {
  title: string
  message: string
  confirmText?: string
  /** 不可逆或影响线上展示的操作用红色确认按钮。 */
  danger?: boolean
}

/**
 * 操作确认：所有写操作提交前都先问一次，说清楚会改什么、谁能看到变化。用户取消时返回 false，不抛错。
 */
export async function confirmAction(options: ConfirmOptions): Promise<boolean> {
  try {
    await ElMessageBox.confirm(options.message, options.title, {
      confirmButtonText: options.confirmText ?? '确认',
      cancelButtonText: '取消',
      type: options.danger ? 'warning' : 'info',
      confirmButtonClass: options.danger ? 'el-button--danger' : undefined,
      distinguishCancelAndClose: false,
    })
    return true
  } catch {
    return false
  }
}

/**
 * 操作留痕提示：写操作成功后告诉操作人“已记录”——后端每个运营写接口都把操作人、时间和说明写进对应的记录
 * （城市改动记录、机构操作记录、反馈的处理人），这里提示去哪儿看。
 */
export function notifyRecorded(action: string, where: string): void {
  ElNotification({
    type: 'success',
    title: `已${action}`,
    message: `操作人和时间已记录，可在“${where}”里查看。`,
    duration: 4000,
  })
}

/** 统一的失败提示（带追踪号，方便对后端日志）。 */
export function showError(error: unknown): void {
  ElMessage({ type: 'error', message: errorMessageWithTrace(error), duration: 5000, showClose: true })
}
