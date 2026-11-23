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
  OPS_STATUS_UNCHANGED: '已经是这个状态了，请刷新后再看',
  OPS_PRODUCT_INCOMPLETE: '上架前还有必填项没补齐',
  OPS_OFFER_INCOMPLETE: '这条供给还不能启用：规格已停用，或购买、租赁商品还没填价格',
  OPS_OFFER_DUPLICATE: '这个规格、商家、城市已经有一条供给了',
  OPS_SKU_LIMIT: '规格已到上限（20 个）',
  OPS_MERCHANT_UNKNOWN: '选的商家不是在用的经营机构',
  OPS_MERCHANT_NOT_COLLECTING: '这家商家还没有有效的收款账户，标价商品不能启用；请先在“收款账户”里登记',
  OPS_MEDIA_URL_UNCONTROLLED: '图片地址只能用上传得到的地址或 https 地址',
  OPS_SUB_MCHID_PLACEHOLDER: '这是开发用的占位商户号（1900000xxx），这个环境不能登记，请填真实的二级商户号',
  OPS_IMPORT_HAS_ISSUES: '这批文件有问题，不能导入：请看问题列表，改好后重新预检',
  OPS_IMPORT_NOT_WRITABLE: '这一类这一版只能预检，还不能导入',
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
  // 巡检和专业服务目录（openapi-ops-inspection-catalog.yaml、openapi-ops-proservice.yaml）
  OPS_PROVIDER_UNKNOWN: '服务商不存在、已停用或不是服务商机构',
  OPS_INSPECTION_CATEGORY_UNKNOWN: '类目不存在或已停用',
  OPS_INSPECTION_CATEGORY_CODE_TAKEN: '类目编码已被占用，换一个试试',
  OPS_INSPECTION_CATEGORY_IN_USE: '类目下还有上架的服务，先下架或给服务换类目',
  // 直播间（openapi-ops-live.yaml）
  OPS_LIVE_ROOM_ENDED: '直播间已经结束了，请刷新后再看',
  OPS_LIVE_ROOM_NOT_ENDED: '直播结束后才能设回看',
  // 课程（openapi-ops-course.yaml）
  OPS_COURSE_STATUS_CONFLICT: '课程现在的状态不能这样操作（已经上架、没有上架，或线下班没有目录），请刷新后再看',
  OPS_COURSE_ORGANIZATION_UNKNOWN: '开课机构不存在、已停用，或不是服务商、平台机构',
  OPS_COURSE_EXPERIENCE_TAKEN: '这个体验中心试听入口已经给了别的课程',
  OPS_COURSE_LESSON_IN_USE: '要删的课时已经有人学过，不能删；可以改标题、时长和视频',
  // 商家资料审核（openapi-ops-merchant-profile.yaml）
  OPS_MERCHANT_PROFILE_REVIEW_CLOSED: '这一版已经审核过或被新提交的一版替换了，请刷新后再看',
  // 商品售后（openapi-trade-aftersale.yaml，商家工作台的“售后”）
  TRADE_AFTERSALE_NOT_ELIGIBLE: '这张订单现在不能申请这类售后',
  TRADE_AFTERSALE_ALREADY_OPEN: '这张订单已经有一张没结束的售后单',
  TRADE_AFTERSALE_AMOUNT_EXCEEDS: '退款金额超过了申请金额或订单还能退的钱，请刷新后重新填写',
  TRADE_AFTERSALE_STATE_INVALID: '售后单的状态已经变了（买家撤销、系统自动处理等），请刷新后再看',
  TRADE_AFTERSALE_VERSION_CONFLICT: '售后单刚刚有变化，请刷新后重试',
  TRADE_AFTERSALE_RETURN_ADDRESS_REQUIRED: '请填写寄回地址（还没有设置默认寄回地址）',
  TRADE_AFTERSALE_IMAGE_INVALID: '凭证图片无效：不是本人上传的、已经用过或超过了数量',
  TRADE_AFTERSALE_UPLOAD_LIMITED: '一小时内上传的图片太多，请稍后再试',
  TRADE_AFTERSALE_STORAGE_UNAVAILABLE: '凭证图片存储暂不可用，请稍后再试',
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
