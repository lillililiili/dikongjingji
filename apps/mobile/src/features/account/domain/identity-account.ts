// Account and enterprise organization (S5-7, S5-8): what the App reads from
// contracts/openapi-identity-account.yaml 1.0.0, and the small rules the pages share.

export type MemberRole = 'ADMIN' | 'MEMBER'
export type IdentityType = 'PERSONAL' | 'ENTERPRISE'
export type DevicePlatform = 'ANDROID' | 'IOS' | 'H5' | 'MP_WEIXIN' | 'OTHER'
export type DataSectionCode = 'ACCOUNT' | 'PARTICIPANTS' | 'ADDRESSES' | 'ORDERS' | 'ENTERPRISES'
export type AccountCodePurpose = 'VERIFY_CURRENT' | 'BIND_NEW' | 'EXPORT'

export interface CurrentIdentity {
  type: IdentityType
  enterpriseId: string | null
  enterpriseName: string | null
  role: MemberRole | null
}

export interface SmsCodeSent {
  maskedPhone: string
  resendAfterSeconds: number
  expiresInSeconds: number
}

export interface LoginToken {
  accessToken: string
  tokenType: 'Bearer'
  expiresInSeconds: number
}

export interface PhoneLoginResult extends LoginToken {
  // Renews the login (POST /api/v1/auth/refresh), like the password login's.
  refreshToken: string
  refreshExpiresInSeconds: number
  registered: boolean
}

export interface PersonalPasswordStatus {
  eligible: boolean
  hasPassword: boolean
  canSetWithoutCode: boolean
}

export function newPasswordError(password: string): string {
  const characters = Array.from(password)
  if (characters.length < 8 || characters.length > 64 || /[\s\u0000-\u001f\u007f-\u009f]/.test(password)
      || !/[A-Za-z]/.test(password) || !/[0-9]/.test(password)) {
    return '密码需为 8–64 位，包含字母和数字，不能含空格'
  }
  // Count UTF-8 bytes on H5 and native JS engines, including surrogate pairs.
  let bytes = 0
  for (const char of characters) {
    const point = char.codePointAt(0)!
    bytes += point <= 0x7f ? 1 : point <= 0x7ff ? 2 : point <= 0xffff ? 3 : 4
  }
  return bytes > 72 ? '密码过长，请减少汉字或表情字符' : ''
}

export interface LoginDevice {
  deviceKey: string | null
  deviceName: string | null
  platform: DevicePlatform | null
  cityCode: string | null
}

export interface AccountSession {
  sessionId: string
  deviceName: string
  platform: DevicePlatform
  cityName: string | null
  signedInAt: string
  lastActiveAt: string
  current: boolean
}

export interface ClosureCondition {
  code: string
  title: string
  met: boolean
  detail: string
}

export interface AccountClosure {
  status: 'NONE' | 'PENDING'
  requestedAt: string | null
  dueAt: string | null
  conditions: ClosureCondition[]
}

export interface DataSection {
  code: DataSectionCode
  title: string
  description: string
  count: number | null
}

export interface DataExport {
  exportId: string
  status: 'PENDING' | 'READY' | 'FAILED' | 'EXPIRED'
  sections: DataSectionCode[]
  requestedAt: string
  readyAt: string | null
  expiresAt: string | null
  sizeBytes: number | null
}

export interface DataExportOverview {
  maskedPhone: string | null
  sections: DataSection[]
  exports: DataExport[]
}

export interface DownloadIntent {
  downloadUrl: string
  fileName: string
  expiresAt: string
}

export interface Enterprise {
  enterpriseId: string
  name: string
  maskedCreditCode: string
  cityName: string | null
  qualificationReviewed: boolean
  role: MemberRole
  memberCount: number
  createdAt: string
}

export interface ReceivedInvitation {
  invitationId: string
  kind: 'PHONE' | 'LINK'
  enterpriseName: string
  qualificationReviewed: boolean
  invitedByName: string
  role: MemberRole
  sentAt: string
  expiresAt: string
  status: 'PENDING' | 'EXPIRED' | 'ACCEPTED' | 'DECLINED' | 'REVOKED'
  maskedPhone: string | null
}

export interface EnterpriseList {
  identity: CurrentIdentity
  items: Enterprise[]
  invitations: ReceivedInvitation[]
}

export interface EnterpriseMember {
  memberId: string
  displayName: string
  maskedPhone: string | null
  role: MemberRole
  self: boolean
  joinedAt: string
}

export interface PendingInvitation {
  invitationId: string
  kind: 'PHONE' | 'LINK'
  maskedPhone: string | null
  role: MemberRole
  invitedByName: string
  sentAt: string
  expiresAt: string
}

export interface EnterpriseMembers {
  enterpriseId: string
  myRole: MemberRole
  members: EnterpriseMember[]
  invitations: PendingInvitation[]
}

export interface CreatedInvitation {
  invitation: PendingInvitation
  inviteCode: string | null
}

export interface EnterpriseOrderSummary {
  orderId: string
  orderNo: string
  source: string
  title: string
  status: string
  payableMinor: number
  operatorName: string
  createdAt: string
}

export interface EnterpriseOrderPage {
  total: number
  counts: { pendingPayment: number; inProgress: number; completed: number }
  items: EnterpriseOrderSummary[]
}

// ---- rules shared by the pages ----

const PHONE = /^1[3-9]\d{9}$/
const CODE = /^\d{6}$/
// 统一社会信用代码: 18 digits or capitals without I, O, Z, S, V; the server checks the same.
const CREDIT_CODE = /^[0-9A-HJ-NPQRTUWXY]{18}$/

export function isPhoneNumber(value: string): boolean {
  return PHONE.test(value)
}

export function isSmsCode(value: string): boolean {
  return CODE.test(value)
}

export function isCreditCode(value: string): boolean {
  return CREDIT_CODE.test(value.trim().toUpperCase())
}

/** Only digits, at most max of them (the boards' phone and code inputs). */
export function digitsOnly(value: unknown, max: number): string {
  return String(value ?? '').replace(/\D/g, '').slice(0, max)
}

/** 138****2026; anything that is not a full number is returned as typed. */
export function maskPhone(phone: string): string {
  return isPhoneNumber(phone) ? `${phone.slice(0, 3)}****${phone.slice(7)}` : phone
}

// The two roles of the default decision (plan 08 §5). The boards draw 负责人 / 经办人 / 验收人; the
// first is our 管理员, the second our 成员, and 验收人 is not a role yet.
export const ROLE_LABELS: Record<MemberRole, string> = { ADMIN: '管理员', MEMBER: '成员' }
export const ROLE_GUIDE: Record<MemberRole, string> = {
  ADMIN: '管理成员和角色，可以移除成员',
  MEMBER: '以企业身份下单、付款，查看企业订单，邀请成员',
}

/** The grey line under a device: 东营 · 当前在线, or 东营 · 11/01 09:12 登录. */
export function sessionMeta(session: AccountSession, formatTime: (iso: string) => string): string {
  const city = session.cityName ? session.cityName.replace(/市$/, '') : null
  const when = session.current ? '当前在线' : `${formatTime(session.signedInAt)} 登录`
  return [city, when].filter(Boolean).join(' · ')
}

export function isTabletName(name: string): boolean {
  return /ipad|平板|tab/i.test(name)
}

/** The text of the code button: 获取验证码, or 58 秒后重新获取 while it may not be sent again. */
export function codeButtonText(secondsLeft: number): string {
  return secondsLeft > 0 ? `${secondsLeft} 秒后重新获取` : '获取验证码'
}

/** 1.2 MB, 860 KB. */
export function formatBytes(size: number | null): string {
  if (!size || size <= 0) return ''
  if (size >= 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(size / 1024))} KB`
}

/** The enterprise line of the 我的 card: 东营港务物流 · 成员. */
export function identityLine(identity: CurrentIdentity): string | null {
  if (identity.type !== 'ENTERPRISE' || !identity.enterpriseName) return null
  return identity.role ? `${identity.enterpriseName} · ${ROLE_LABELS[identity.role]}` : identity.enterpriseName
}

/** Where an invitation link points: the invitation page with its id and code. */
export function invitationLinkPath(invitationId: string, code: string): string {
  return `/pages/mine/org-invite?invitationId=${encodeURIComponent(invitationId)}&code=${encodeURIComponent(code)}`
}

/** The value of a uni input event (H5 and App both put it on detail.value). */
export function inputValue(event: Event): string {
  const detail = (event as unknown as { detail?: { value?: unknown } }).detail
  return String(detail?.value ?? (event.target as HTMLInputElement | null)?.value ?? '')
}

// The tag of an enterprise order (board OrgDetail 需求与合同 uses the same tones).
export function enterpriseOrderTag(status: string): { text: string; bg: string; fg: string } {
  if (status === 'PENDING_PAYMENT') return { text: '待付款', bg: '#fff0e3', fg: '#ba470b' }
  if (status === 'PAID' || status === 'SHIPPED') return { text: status === 'PAID' ? '已付款' : '已发货', bg: '#e6f0ff', fg: '#1d63c4' }
  if (status === 'COMPLETED') return { text: '已完成', bg: '#e2f6ed', fg: '#11804a' }
  if (status === 'REFUNDING') return { text: '退款中', bg: '#fff0e3', fg: '#ba470b' }
  if (status === 'REFUNDED') return { text: '已退款', bg: '#eef2f8', fg: '#536178' }
  if (status === 'CLOSED') return { text: '已关闭', bg: '#eef2f8', fg: '#536178' }
  return { text: '处理中', bg: '#eef2f8', fg: '#536178' }
}

/** The first character on a member's round avatar. */
export function avatarChar(name: string): string {
  return Array.from(name.trim())[0] ?? '?'
}
