/**
 * 后端统一的错误体（各契约里的 ApiError）：成功时直接返回对象（没有外层信封），失败时是
 * { code, message, traceId, details? }。code 是稳定编码，页面按 code 判断，不按中文文案判断。
 */
export interface ApiErrorBody {
  code: string
  message: string
  traceId: string
  details?: Record<string, unknown> | null
}

/** 客户端自己产生的错误码（请求没到后端、或后端没给错误体）。 */
export const CLIENT_ERROR_CODES = {
  network: 'CLIENT_NETWORK_ERROR',
  invalidResponse: 'CLIENT_INVALID_RESPONSE',
} as const

export class ApiRequestError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly serverMessage: string,
    readonly traceId: string | null,
    readonly details: Record<string, unknown> | null = null,
  ) {
    super(`${status} ${code}`)
    this.name = 'ApiRequestError'
  }
}

/**
 * 给人看的说明。通用错误码和运营端各契约里的 OPS_ 错误码在这里统一翻译；没登记的用后端给的中文说明。
 * 新增一块运营功能、契约里加了新的 OPS_ 错误码时，在这里补一行。
 */
const MESSAGES: Record<string, string> = {
  COMMON_UNAUTHORIZED: '登录已失效，请重新登录',
  COMMON_FORBIDDEN: '你的账号没有这项操作的权限',
  COMMON_NOT_FOUND: '要操作的数据不存在，可能已被删除',
  COMMON_INVALID_ARGUMENT: '填写的内容不符合要求，请检查后重试',
  COMMON_RATE_LIMITED: '操作太频繁，请稍后再试',
  COMMON_SERVICE_UNAVAILABLE: '服务暂不可用，请稍后再试',
  OPS_VERSION_CONFLICT: '数据刚被别人修改过，请刷新后重试',
  OPS_IDEMPOTENCY_KEY_REUSED: '重复提交的内容不一致，请关闭弹窗重新填写',
  OPS_CITY_STATUS_UNCHANGED: '城市已经是这个状态了',
  OPS_CITY_UNKNOWN: '城市不在平台城市目录里',
  OPS_LOGIN_NAME_TAKEN: '登录名已被占用，换一个试试',
  OPS_ROLE_NOT_GRANTABLE: '这类机构不能授这个角色',
  OPS_QUALIFICATION_NOT_PENDING: '资质资料不是待审核状态，请刷新后再看',
  OPS_QUALIFICATION_INCOMPLETE: '审核通过前至少要上传一份资质附件（营业执照等）',
  OPS_ATTACHMENT_LIMIT: '附件数量已到上限（10 份）',
  OPS_FILE_STORE_UNAVAILABLE: '附件存储暂不可用，请稍后再试',
  OPS_FEEDBACK_ALREADY_HANDLED: '这条反馈已经被处理过了',
  [CLIENT_ERROR_CODES.network]: '网络异常，请检查网络后重试',
  [CLIENT_ERROR_CODES.invalidResponse]: '服务返回的数据无法识别，请稍后再试',
}

/**
 * 各功能块自己的业务错误码说明（TRADE_、MALL_INQUIRY_、RENTAL_、DEMAND_、LIVE_、SUPPORT_、INS_ 等）：
 * 在功能块的 *-api.ts 里调一次，和契约里的错误码一一对应。和上面的通用说明重复时以上面为准。
 */
const FEATURE_MESSAGES: Record<string, string> = {}

export function registerErrorMessages(messages: Record<string, string>): void {
  for (const [code, message] of Object.entries(messages)) {
    if (!(code in MESSAGES)) FEATURE_MESSAGES[code] = message
  }
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiRequestError) {
    return MESSAGES[error.code] ?? FEATURE_MESSAGES[error.code]
      ?? (error.serverMessage || `请求失败（${error.status}）`)
  }
  if (error instanceof Error && error.message) return error.message
  return '操作失败，请稍后再试'
}

/** 带上追踪号，方便对日志。 */
export function errorMessageWithTrace(error: unknown): string {
  const message = errorMessage(error)
  return error instanceof ApiRequestError && error.traceId ? `${message}（追踪号 ${error.traceId}）` : message
}

export function isApiError(error: unknown, code: string): boolean {
  return error instanceof ApiRequestError && error.code === code
}
