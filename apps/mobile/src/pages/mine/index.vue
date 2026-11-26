<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import RootAppTabBar from '@/app-shell/components/RootAppTabBar.vue'
import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { identityAccountApi } from '@/features/account/data/identity-account-api-client'
import type { EnterpriseList } from '@/features/account/domain/identity-account'
import { ROLE_LABELS } from '@/features/account/domain/identity-account'
import { buildOrgUrl } from '@/features/account/navigation/account-routes'
import {
  getBookingAuthContext as authContext,
  replaceBookingAccessToken,
} from '@/features/inspection/application/service-flow/booking-auth-context'
import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import { enrollmentApi } from '@/features/activity/data/enrollment-api-client'
import type { Enrollment } from '@/features/activity/domain/enrollment'
import {
  ACTIVITY_PARTICIPANTS_PATH,
  buildActivityEnrollmentsUrl,
  buildActivityTicketUrl,
} from '@/features/activity/navigation/activity-routes'
import { DEMAND_PREVIEW_ENABLED } from '@/features/demand/application/demand-preview'
import { FAVORITE_PREVIEW_ENABLED } from '@/features/favorite/application/favorite-preview'
import { buildFavoritesUrl } from '@/features/favorite/navigation/favorite-routes'
import { demandApi } from '@/features/demand/data/demand-api-client'
import { buildMyDemandsUrl } from '@/features/demand/navigation/demand-routes'
import { RENTAL_PREVIEW_ENABLED } from '@/features/rental/application/rental-preview'
import { buildMyRentalOrdersUrl } from '@/features/rental/navigation/rental-routes'
import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import { buildExperienceRecordsUrl, buildVenueBookingsUrl } from '@/features/experience/navigation/experience-routes'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import {
  createLatestRequestCoordinator,
  requestLatestInspectionOrders,
  resolveInspectionOrderDestination,
} from '@/features/inspection/application/service-flow/inspection-order-navigation'
import {
  inspectionAccountApi,
  inspectionOrderApi,
} from '@/features/inspection/data/service-flow/uni-booking-http-client'
import type { InspectionOrderSummary } from '@/features/inspection/domain/service-flow/order-center'
import {
  MINE_TAB_PATH,
  buildInspectionLoginUrl,
  buildInspectionOrderCenterUrl,
} from '@/features/inspection/navigation/inspection-routes'
import PlatformMinePage from '@/features/inspection/presentation/platform/mine/PlatformMinePage.vue'
import { buildMyOrdersUrl, type MyOrderChip } from '@/features/orders/application/my-orders-route'
import { buildAftersalesUrl } from '@/features/trade/navigation/aftersale-routes'
import { SETTINGS_PATH, ABOUT_PATH } from '@/features/settings/navigation/settings-routes'
import { SETTINGS_PREVIEW_ENABLED } from '@/features/settings/application/settings-preview'
import { confirmSignOut } from '@/features/account/application/confirm-sign-out'
import { loadStaffAccess } from '@/features/staff/application/staff-access'
import { STAFF_WORK_MODE_ENABLED } from '@/features/staff/application/staff-work-mode'
import { COURSE_PREVIEW_ENABLED } from '@/features/course/application/course-preview'
import { buildMyCoursesUrl } from '@/features/course/navigation/course-routes'
import { checkinApi } from '@/features/staff/data/checkin-api-client'
import { buildStaffVerifyUrl } from '@/features/staff/navigation/staff-routes'
import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import { buildSupportHelpUrl, buildSupportOpenUrl } from '@/features/support/navigation/support-routes'
import { TRADE_PREVIEW_ENABLED } from '@/features/trade/application/trade-preview'
import { tradeApi } from '@/features/trade/data/trade-api-client'
import type { TradeOrderTab } from '@/features/trade/domain/trade-order'
import {
  resolveAuthenticatedMineUser,
  type MineAcceptanceCard,
  type MineEnrollmentCard,
  type MineOrderStage,
  type PlatformMineAccount,
  type PlatformMineLinkEntry,
  type PlatformMineViewState,
} from '@/features/inspection/presentation/platform/mine/platform-mine'
import { useCurrentCityStore } from '@/shared/city/state/current-city'
import { formatBeijingTime, toBeijingParts } from '@/shared/time/beijing-time'

const currentCityStore = useCurrentCityStore()

// 我的参与 (board Mine `joins`): 活动报名, 体验预约 and 体验记录 (S5-4) behind their channels'
// switches, 我的课程 behind the COURSE switch (S5-5). 租赁订单 (F3, §24.3) has no other way in, so it
// stays.
const joinEntries: PlatformMineLinkEntry[] = [
  ...(ACTIVITY_PREVIEW_ENABLED ? [{ code: 'ACTIVITY_ENROLLMENTS', title: '活动报名' }] : []),
  ...(EXPERIENCE_PREVIEW_ENABLED ? [{ code: 'VENUE_BOOKINGS', title: '体验预约' }] : []),
  ...(EXPERIENCE_PREVIEW_ENABLED ? [{ code: 'EXPERIENCE_RECORDS', title: '体验记录' }] : []),
  ...(COURSE_PREVIEW_ENABLED ? [{ code: 'MY_COURSES', title: '我的课程' }] : []),
  ...(RENTAL_PREVIEW_ENABLED ? [{ code: 'RENTAL_ORDERS', title: '租赁订单' }] : []),
]

// The rows of the board that exist: 企业组织 and 权限管理 are not built.
// A signed-in account has 联系客服, a guest 关于 (boards Mine and MineGuest). 工作模式 (S3-3) is last and
// only for an operator of an organization that hosts activities or runs venues, as the server says.
interface RowValues {
  participants?: string
  demands?: string
  staffWorkMode?: boolean
  enterprise?: string
}
function resolveLinkRows(signedIn: boolean, values: RowValues): PlatformMineLinkEntry[] {
  return [
    ...(ACTIVITY_PREVIEW_ENABLED
      ? [{ code: 'ACTIVITY_PARTICIPANTS', title: '参与人管理', ...(signedIn && values.participants ? { valueText: values.participants } : {}) }]
      : []),
    // S5-7 企业组织 (board Mine rows, between 参与人管理 and 需求与报价).
    ...(ACCOUNT_PREVIEW_ENABLED
      ? [{ code: 'ENTERPRISE', title: '企业组织', ...(signedIn && values.enterprise ? { valueText: values.enterprise } : {}) }]
      : []),
    ...(DEMAND_PREVIEW_ENABLED
      ? [{ code: 'DEMANDS', title: '需求与报价', ...(signedIn && values.demands ? { valueText: values.demands } : {}) }]
      : []),
    // 收藏与对比 right under 需求与报价, as on board Mine (plan 08 S5-6).
    ...(FAVORITE_PREVIEW_ENABLED ? [{ code: 'FAVORITES', title: '收藏与对比' }] : []),
    ...(SUPPORT_PREVIEW_ENABLED ? [{ code: 'HELP', title: '帮助与反馈' }] : []),
    ...(SUPPORT_PREVIEW_ENABLED && signedIn ? [{ code: 'SUPPORT', title: '联系客服' }] : []),
    ...(SETTINGS_PREVIEW_ENABLED && !signedIn ? [{ code: 'ABOUT', title: '关于' }] : []),
    ...(STAFF_WORK_MODE_ENABLED && signedIn && values.staffWorkMode
      ? [{ code: 'STAFF_WORK_MODE', title: '工作模式', valueText: '核销员 · 仅授权可见' }]
      : []),
  ]
}
// Rows a guest may open without signing in (board MineGuest).
const GUEST_LINKS = new Set(['HELP', 'ABOUT'])
const accessToken = () => getBookingAuthContext().getAccessToken()
const signedIn = () => Boolean(accessToken())

// The signed-in account's own profile; null until read, and again after signing out.
const account = ref<PlatformMineAccount | null>(null)
let accountToken: string | null = null

// Everything the page shows that was read for the current token. A number that could not be read
// stays undefined, and the page shows nothing for it.
const orderCounts = ref<Partial<Record<MineOrderStage, number>>>({})
const enrollment = ref<Enrollment | null>(null)
const acceptanceOrder = ref<InspectionOrderSummary | null>(null)
const rowValues = ref<RowValues>({})
let loadedFor: string | null = null

// S5-7: the enterprises the account is in, read with the account (preview builds).
const enterprises = ref<EnterpriseList | null>(null)

function resolveMineUser() {
  const token = accessToken()
  const own = token && token === accountToken ? account.value : null
  const user = resolveAuthenticatedMineUser(Boolean(token), currentCityStore.currentCity?.name, own)
  if (!user || !ACCOUNT_PREVIEW_ENABLED || !own) return user
  const identity = own.identity
  const acting = identity?.type === 'ENTERPRISE' && identity.enterpriseName
    ? { name: identity.enterpriseName, roleLabel: identity.role ? ROLE_LABELS[identity.role] : '企业身份' }
    : undefined
  // Board Mine: the phone line replaces the organization line, and the switch sits on the right.
  const { certificationLabel: _organization, ...personal } = user
  return {
    ...personal,
    ...(own.maskedPhone ? { maskedPhone: own.maskedPhone } : {}),
    ...(acting ? { enterpriseIdentity: acting } : {}),
    switchLabel: acting ? '切换个人身份' : '切换企业身份',
  }
}

function toEnrollmentCard(item: Enrollment): MineEnrollmentCard {
  const people = item.tickets.filter((ticket) => ticket.status === 'ACTIVE').length
  const at = item.session.checkInOpensAt ?? item.session.startsAt
  const parts = toBeijingParts(new Date(at))
  const time = `${parts.month}/${parts.day} ${formatBeijingTime(at)}`
  return {
    enrollmentId: item.enrollmentId,
    title: `${item.activity.title} · ${people} 人`,
    meta: [`${time} ${item.session.checkInOpensAt ? '起签到' : '开始'}`, item.location.name].filter(Boolean).join(' · '),
  }
}

function toAcceptanceCard(order: InspectionOrderSummary): MineAcceptanceCard {
  const visit = order.pendingAcceptanceVisitNo
  return {
    id: order.reservationId,
    title: visit && (order.visitCount ?? 1) > 1 ? `${order.serviceTitle}（第 ${visit} 次）` : order.serviceTitle,
    // The board names the provider here; order summaries carry no provider name yet (R6).
    meta: [order.locationSummary, '报告已交付'].filter(Boolean).join(' · '),
  }
}

function buildState(): PlatformMineViewState {
  const user = resolveMineUser()
  return {
    kind: 'ready',
    content: {
      user,
      orderCounts: user ? orderCounts.value : {},
      ...(user && enrollment.value ? { enrollmentCard: toEnrollmentCard(enrollment.value) } : {}),
      ...(user && acceptanceOrder.value ? { acceptanceCard: toAcceptanceCard(acceptanceOrder.value) } : {}),
      joinEntries,
      linkRows: resolveLinkRows(Boolean(user), rowValues.value),
    },
  }
}

// Known before the first request, so a signed-in user never sees the guest card flash by.
const state = ref<PlatformMineViewState>(buildState())
const refresh = () => { state.value = buildState() }
const orderRequest = createLatestRequestCoordinator()

function restoreDefaultCity(): void {
  if (currentCityStore.currentCity) return
  const code = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_CODE?.trim()
  const name = import.meta.env.VITE_INSPECTION_DEFAULT_CITY_NAME?.trim()
  if (code && name) currentCityStore.setCurrentCity({ code, name })
}

// Signing out or in as someone else drops what was read for the previous account.
function resetForToken(token: string | null): boolean {
  if (token === loadedFor) return false
  loadedFor = token
  orderCounts.value = {}
  enrollment.value = null
  acceptanceOrder.value = null
  rowValues.value = {}
  return true
}

// Read once per access token. A failure leaves the neutral "已登录" card: the profile is not
// worth an error message, and the order request below reports a rejected login.
async function loadAccount(): Promise<void> {
  const token = accessToken()
  if (!token) {
    account.value = null
    accountToken = null
    return
  }
  if (token === accountToken) return
  try {
    const loaded = await inspectionAccountApi.loadCurrent(token)
    if (accessToken() !== token) {
      // The request layer renewed the expired token while the page was loading. Everything read
      // with the old token is dropped by the checks above and below, so read it all again with the
      // renewed one; otherwise the card stays at "已登录" and the counts stay empty.
      if (accessToken()) {
        void loadAccount()
        loadSignedIn()
      }
      return
    }
    account.value = loaded
    accountToken = token
    refresh()
  } catch {
    // Keep the neutral card.
  }
}

// One count per call; a failed call leaves the stage without a number.
async function readCount(token: string, stage: MineOrderStage, read: () => Promise<number>): Promise<void> {
  try {
    const count = await read()
    if (accessToken() !== token) return
    orderCounts.value = { ...orderCounts.value, [stage]: count }
    refresh()
  } catch {
    // No number for this stage.
  }
}

async function loadInspection(token: string): Promise<void> {
  const result = await requestLatestInspectionOrders(
    orderRequest,
    accessToken,
    (input) => inspectionOrderApi.list(input),
  )
  if (result.status !== 'success' || accessToken() !== token) return
  const { stageCounts, items } = result.value
  orderCounts.value = { ...orderCounts.value, TO_ACCEPT: stageCounts.AWAITING_ACCEPTANCE }
  let pending = items.find((item) => item.stage === 'AWAITING_ACCEPTANCE') ?? null
  if (!pending && stageCounts.AWAITING_ACCEPTANCE > 0) {
    try {
      const page = await inspectionOrderApi.list({ stage: 'AWAITING_ACCEPTANCE', page: 1, size: 1 })
      pending = page.items.find((item) => item.stage === 'AWAITING_ACCEPTANCE') ?? null
    } catch {
      // The count stays; only the card is missing.
    }
  }
  if (accessToken() !== token) return
  acceptanceOrder.value = pending
  refresh()
}

async function loadEnrollments(token: string): Promise<void> {
  if (!ACTIVITY_PREVIEW_ENABLED) return
  try {
    const page = await enrollmentApi.loadEnrollments(token, 'UPCOMING', 1, 10)
    if (accessToken() !== token) return
    orderCounts.value = { ...orderCounts.value, TO_ATTEND: page.total }
    // The card shows a sign-up that is settled (free or paid) and has someone going.
    enrollment.value = page.items.find((item) =>
      (item.payment.status === 'FREE' || item.payment.status === 'PAID')
      && item.tickets.some((ticket) => ticket.status === 'ACTIVE')) ?? null
    refresh()
  } catch {
    // No number, no card.
  }
}

async function loadRowValues(token: string): Promise<void> {
  const tasks: Promise<void>[] = []
  if (ACTIVITY_PREVIEW_ENABLED) {
    tasks.push(enrollmentApi.loadParticipants(token).then((people) => {
      if (accessToken() === token) rowValues.value = { ...rowValues.value, participants: `${people.length} 人` }
    }))
  }
  if (DEMAND_PREVIEW_ENABLED) {
    tasks.push(Promise.all([
      demandApi.list(token, 'CHOSEN', 1, 1),
      demandApi.list(token, null, 1, 1),
    ]).then(([chosen, all]) => {
      if (accessToken() !== token) return
      const text = chosen.total > 0 ? `${chosen.total} 份已选定` : all.total > 0 ? `${all.total} 份` : undefined
      rowValues.value = { ...rowValues.value, demands: text }
    }))
  }
  if (ACCOUNT_PREVIEW_ENABLED) {
    tasks.push(identityAccountApi.listEnterprises(token).then((list) => {
      if (accessToken() !== token) return
      enterprises.value = list
      const current = list.items.find((item) => item.enterpriseId === list.identity.enterpriseId) ?? list.items[0]
      const text = current?.name ?? (list.invitations.length ? `${list.invitations.length} 个邀请待确认` : undefined)
      rowValues.value = { ...rowValues.value, enterprise: text }
    }))
  }
  if (STAFF_WORK_MODE_ENABLED) {
    tasks.push(loadStaffAccess(checkinApi, token).then((allowed) => {
      if (accessToken() === token) rowValues.value = { ...rowValues.value, staffWorkMode: allowed }
    }))
  }
  await Promise.allSettled(tasks)
  refresh()
}

function loadSignedIn(): void {
  const token = accessToken()
  resetForToken(token)
  refresh()
  if (!token) return
  const goods = (stage: MineOrderStage, tab: TradeOrderTab) =>
    readCount(token, stage, async () => (await tradeApi.list(token, tab, 1, 1)).total)
  if (TRADE_PREVIEW_ENABLED) {
    void goods('TO_PAY', 'TO_PAY')
    void goods('TO_SHIP', 'TO_SHIP')
  }
  void loadInspection(token)
  void loadEnrollments(token)
  void loadRowValues(token)
}

function showUnavailable(): void {
  uni.showToast({ title: '该能力将在下一阶段接入', icon: 'none' })
}

function openCustomerService(): void {
  if (!SUPPORT_PREVIEW_ENABLED) {
    showUnavailable()
    return
  }
  uni.navigateTo({ url: buildSupportOpenUrl({ kind: 'PLATFORM' }) })
}

function openSettings(): void {
  if (!signedIn()) openLogin()
  else uni.navigateTo({ url: SETTINGS_PATH })
}

// After signing in the user comes back to this page, now with their own profile and orders.
function openLogin(): void {
  uni.navigateTo({ url: buildInspectionLoginUrl(MINE_TAB_PATH) })
}

function openLink(code: string): void {
  if (!signedIn() && !GUEST_LINKS.has(code)) {
    openLogin()
    return
  }
  if (code === 'HELP') uni.navigateTo({ url: buildSupportHelpUrl() })
  if (code === 'ABOUT') uni.navigateTo({ url: ABOUT_PATH })
  if (code === 'SUPPORT') openCustomerService()
  if (code === 'ACTIVITY_ENROLLMENTS') uni.navigateTo({ url: buildActivityEnrollmentsUrl() })
  if (code === 'ACTIVITY_PARTICIPANTS') uni.navigateTo({ url: ACTIVITY_PARTICIPANTS_PATH })
  if (code === 'VENUE_BOOKINGS') uni.navigateTo({ url: buildVenueBookingsUrl() })
  if (code === 'EXPERIENCE_RECORDS') uni.navigateTo({ url: buildExperienceRecordsUrl() })
  if (code === 'DEMANDS') uni.navigateTo({ url: buildMyDemandsUrl() })
  if (code === 'ENTERPRISE') uni.navigateTo({ url: buildOrgUrl(account.value?.identity?.enterpriseId ?? null) })
  if (code === 'FAVORITES') uni.navigateTo({ url: buildFavoritesUrl() })
  if (code === 'RENTAL_ORDERS') uni.navigateTo({ url: buildMyRentalOrdersUrl() })
  if (code === 'MY_COURSES') uni.navigateTo({ url: buildMyCoursesUrl() })
  if (code === 'STAFF_WORK_MODE') uni.navigateTo({ url: buildStaffVerifyUrl() })
}

// 全部 ›: 我的订单, every kind of order in one list (plan 06 R6-1, preview builds). A public build
// has no goods orders yet and keeps the service orders, whose list has a 全部 tab.
function openAllOrders(): void {
  if (!signedIn()) {
    openLogin()
    return
  }
  uni.navigateTo({ url: TRADE_PREVIEW_ENABLED ? buildMyOrdersUrl() : buildInspectionOrderCenterUrl('ALL') })
}

// The stage's status chip of 我的订单.
const STAGE_CHIPS: Record<MineOrderStage, MyOrderChip> = {
  TO_PAY: 'pay',
  TO_SHIP: 'ship',
  TO_ATTEND: 'attend',
  TO_ACCEPT: 'accept',
  REFUND: 'refund',
}

// Each stage opens 我的订单 on its chip; a public build opens the existing list that holds it.
function openStage(stage: MineOrderStage): void {
  if (!signedIn()) {
    openLogin()
    return
  }
  if (TRADE_PREVIEW_ENABLED) {
    // 退款/售后 opens the after-sales list (board Mine → AftersaleList, S4-2); the other stages open
    // 我的订单 on their chip, where 退款/售后 also lists the cases with “全部售后单 ›”.
    uni.navigateTo({ url: stage === 'REFUND' ? buildAftersalesUrl() : buildMyOrdersUrl({ chip: STAGE_CHIPS[stage] }) })
    return
  }
  if (stage === 'TO_ACCEPT') {
    uni.navigateTo({ url: buildInspectionOrderCenterUrl('AWAITING_ACCEPTANCE') })
    return
  }
  if (stage === 'TO_ATTEND') {
    if (ACTIVITY_PREVIEW_ENABLED) uni.navigateTo({ url: buildActivityEnrollmentsUrl('UPCOMING') })
    else showUnavailable()
    return
  }
  // 待付款, 待发货/履约 and 退款/售后 are goods orders, which a public build does not have yet.
  showUnavailable()
}

// 切换企业身份 / 切换个人身份 (board Mine, S5-7): the server reissues the token with the new identity;
// orders placed afterwards record the enterprise as the payer. Without an enterprise the button opens
// 企业组织 (create or confirm an invitation); with several the user picks one.
async function switchTo(enterpriseId: string | null): Promise<void> {
  const token = authContext().getAccessToken()
  if (!token) return
  try {
    const result = await identityAccountApi.switchIdentity(token, enterpriseId)
    // Only the access token changes; the login's refresh token stays.
    replaceBookingAccessToken(result)
    accountToken = null
    uni.showToast({ title: enterpriseId ? `已切换为 ${result.identity.enterpriseName}` : '已切换为个人身份', icon: 'none' })
    await loadAccount()
    loadSignedIn()
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '切换失败，请重试', icon: 'none' })
  }
}

function switchIdentity(): void {
  if (account.value?.identity?.type === 'ENTERPRISE') {
    void switchTo(null)
    return
  }
  const items = enterprises.value?.items ?? []
  if (items.length === 0) {
    uni.navigateTo({ url: buildOrgUrl() })
    return
  }
  if (items.length === 1) {
    void switchTo(items[0].enterpriseId)
    return
  }
  uni.showActionSheet({
    itemList: items.map((item) => `${item.name}（${ROLE_LABELS[item.role]}）`),
    success: (result) => void switchTo(items[result.tapIndex]?.enterpriseId ?? null),
  })
}

function openEnrollment(): void {
  if (enrollment.value) uni.navigateTo({ url: buildActivityTicketUrl(enrollment.value.enrollmentId) })
}

function openAcceptance(): void {
  const order = acceptanceOrder.value
  const url = order ? resolveInspectionOrderDestination(order) : null
  if (url) uni.navigateTo({ url })
  else loadSignedIn()
}

onShow(() => {
  restoreDefaultCity()
  void loadAccount()
  loadSignedIn()
})
</script>

<template>
  <view data-testid="mine-page">
    <PlatformMinePage
      :state="state"
      :settings-enabled="SETTINGS_PREVIEW_ENABLED"
      :goods-orders-entry="TRADE_PREVIEW_ENABLED"
      @retry="loadSignedIn"
      @login="openLogin"
      @settings="openSettings"
      @logout="confirmSignOut"
      @select-stage="openStage"
      @view-all-orders="openAllOrders"
      @open-enrollment="openEnrollment"
      @open-acceptance="openAcceptance"
      @select-link="openLink"
      @switch-identity="switchIdentity"
    />
    <RootAppTabBar active="MINE" />
  </view>
</template>
