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
  // S3-1 第三部分：素材上传（openapi-ops-media.yaml）和各块共用的上线前校验
  OPS_PUBLISH_INCOMPLETE: '上线前还有必填项没填',
  OPS_MEDIA_TYPE_UNSUPPORTED: '只能上传 JPEG、PNG、WebP 图片或 MP4 视频',
  OPS_MEDIA_TOO_LARGE: '图片不能超过 5 MB，视频不能超过 50 MB',
  OPS_MEDIA_STORE_UNAVAILABLE: '素材存储暂不可用，请稍后再试',
  // 活动与场次（openapi-ops-activity.yaml）
  OPS_ACTIVITY_STATUS_CONFLICT: '活动现在的状态不能这样操作，请刷新后再看',
  OPS_ACTIVITY_ORGANIZER_UNKNOWN: '主办或承办机构不存在、已停用，或不是经营机构',
  OPS_ACTIVITY_HAS_ENROLLMENTS: '活动已有报名，不能改收费方式、主办机构和城市',
  OPS_ACTIVITY_SESSION_HAS_ENROLLMENTS: '这一场已有报名：不能删除，也不能改开始、结束时间，只能停售',
  OPS_ACTIVITY_SESSION_STARTED: '场次已经开始，不能再改',
  OPS_ACTIVITY_CAPACITY_BELOW_ENROLLED: '名额不能少于已报名人数',
  OPS_ACTIVITY_SALE_STATUS_UNCHANGED: '场次已经是这个售卖状态了',
  OPS_ACTIVITY_SESSION_LIMIT: '一个活动最多 50 个场次',
  // 场馆与设备（openapi-ops-venue.yaml）
  OPS_VENUE_STATUS_CONFLICT: '当前状态不能做这个操作，请刷新后再看',
  OPS_VENUE_OPERATOR_INVALID: '运营机构不存在、已停用或不是服务商机构',
  OPS_VENUE_PRODUCT_UNKNOWN: '关联的商品不存在，或在场馆所在城市没有在售',
  OPS_VENUE_SESSION_STARTED: '场次已经开始，不能再改',
  OPS_VENUE_SESSION_HAS_BOOKINGS: '这一场已有预约，不能取消，只能停售',
  OPS_VENUE_TOO_MANY_SESSIONS: '一次最多生成 200 场，请缩短日期范围或减少开始时间',
  // 发现内容（openapi-ops-content.yaml）
  OPS_CONTENT_AUTHOR_INVALID: '作者、发布方或发布商家要是在用的服务商或商家；案例不能由平台发布',
  OPS_CONTENT_STATUS_CONFLICT: '内容当前的状态不能做这个操作，请刷新后再看',
  OPS_CONTENT_ARTICLE_UNKNOWN: '专题里挂的图文不存在（视频不能挂在相关内容里）',
  OPS_CONTENT_PRODUCT_UNKNOWN: '商品不存在，或在专题的城市没有在售商家',
  [CLIENT_ERROR_CODES.network]: '网络异常，请检查网络后重试',
  [CLIENT_ERROR_CODES.invalidResponse]: '服务返回的数据无法识别，请稍后再试',
}

export function errorMessage(error: unknown): string {
  if (error instanceof ApiRequestError) {
    return MESSAGES[error.code] ?? (error.serverMessage || `请求失败（${error.status}）`)
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
