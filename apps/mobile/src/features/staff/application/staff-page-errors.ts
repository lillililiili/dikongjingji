// How a page of 工作模式 answers a failed read: sign in again, say the account may not verify,
// or show a message with 重试.
export type StaffLoadFailure = { kind: 'login' } | { kind: 'forbidden' } | { kind: 'error'; message: string }

export function classifyStaffFailure(error: unknown): StaffLoadFailure {
  const status = typeof error === 'object' && error !== null && 'status' in error
    ? Number((error as { status: unknown }).status)
    : 0
  if (status === 401) return { kind: 'login' }
  if (status === 403) return { kind: 'forbidden' }
  if (status === 404) return { kind: 'error', message: '没有找到这条核销记录' }
  if (status === 0) return { kind: 'error', message: '网络连接失败，请稍后重试' }
  return { kind: 'error', message: '读取失败，请稍后重试' }
}

/** What a refused resolution says (the codes of contracts/openapi-checkin-workbench.yaml). */
export function resolutionErrorText(error: unknown): string {
  const code = typeof error === 'object' && error !== null && 'code' in error ? (error as { code: unknown }).code : null
  switch (code) {
    case 'CHECKIN_SEATS_NOT_ENOUGH':
      return '当前场次余位不足，不能放行'
    case 'CHECKIN_RELEASE_NOT_ALLOWED':
      return '这张凭证现在不能放行，请选择不予核销'
    case 'CHECKIN_ALREADY_RESOLVED':
      return '这条异常已经登记过'
    case 'CHECKIN_ACTION_NOT_ALLOWED':
      return '这条记录不能这样处理'
    case 'COMMON_INVALID_ARGUMENT':
      return '请先勾选已人工核验身份，备注不超过 200 字'
    default:
      return '登记没有成功，请稍后重试'
  }
}
