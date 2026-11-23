<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import { formatTradeAmount } from '@/features/trade/application/order-view'
import { createTradeKey as createIdempotencyKey } from '@/features/trade/application/trade-keys'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { toBeijingParts } from '@/shared/time/beijing-time'

import { identityAccountApi } from '../../data/identity-account-api-client'
import type { Enterprise, EnterpriseList, EnterpriseMembers, EnterpriseOrderPage } from '../../domain/identity-account'
import { ROLE_LABELS, avatarChar, enterpriseOrderTag, inputValue, isCreditCode } from '../../domain/identity-account'
import ConfirmSheet from '../shared/ConfirmSheet.vue'

// 企业组织 (board OrgDetail, 390 wide; Ref24Org is its finished look). One enterprise the account is
// in: its card and profile, the orders paid as the enterprise, the first members. Without an
// enterprise the page offers the invitations sent to this number and a form to create one.
const props = defineProps<{ accessToken: string; enterpriseId: string | null; cityCode: string | null; cityName: string | null }>()
const emit = defineEmits<{ back: []; members: [enterpriseId: string]; invitation: [invitationId: string]; changed: []; unauthorized: [] }>()

const BACK = 'M15 5l-7 7 7 7'
const PEOPLE = 'M9 11a3 3 0 1 0 0-6a3 3 0 1 0 0 6zM3 20c.8-3 3-5 6-5s5.2 2 6 5M17 11a2.5 2.5 0 1 0 0-5M21 19c-.4-2-1.6-3.4-3.5-4'
const BUILDING = 'M4 20V8l6-4v16M10 20h10V10l-10-2M14 13h2M14 16h2'
const CHEVRON = 'M9 5l7 7-7 7'
const AVATAR_TONES = [
  { bg: '#ffe3cc', fg: '#ba470b' },
  { bg: '#dbe9fc', fg: '#1d63c4' },
  { bg: '#d6f2e2', fg: '#11804a' },
]

const list = ref<EnterpriseList | null>(null)
const chosenId = ref<string | null>(props.enterpriseId)
const members = ref<EnterpriseMembers | null>(null)
const orders = ref<EnterpriseOrderPage | null>(null)
const error = ref('')
const profileOpen = ref(false)
const leaving = ref(false)
const busy = ref(false)
// The create form (no enterprise yet).
const name = ref('')
const creditCode = ref('')
const createKey = ref(createIdempotencyKey())

const enterprise = computed<Enterprise | null>(() => {
  const items = list.value?.items ?? []
  return items.find((item) => item.enterpriseId === chosenId.value)
    ?? items.find((item) => item.enterpriseId === list.value?.identity.enterpriseId)
    ?? items[0]
    ?? null
})

const stats = computed(() => [
  { n: orders.value?.counts.pendingPayment ?? 0, t: '待付款' },
  { n: orders.value?.counts.inProgress ?? 0, t: '进行中' },
  { n: orders.value?.counts.completed ?? 0, t: '已完成' },
])

const profileRows = computed(() => {
  const item = enterprise.value
  if (!item) return []
  const created = toBeijingParts(new Date(item.createdAt))
  return [
    { k: '组织名称', v: item.name, c: '#202c42' },
    { k: '认证状态', v: item.qualificationReviewed ? '资料已认证' : '资料待平台审核', c: item.qualificationReviewed ? '#11804a' : '#ba470b' },
    { k: '信用代码', v: item.maskedCreditCode, c: '#202c42' },
    { k: '所在城市', v: item.cityName?.replace(/市$/, '') ?? '未填写', c: '#202c42' },
    { k: '成员', v: `${item.memberCount} 人`, c: '#202c42' },
    { k: '创建时间', v: `${created.year}/${created.month}/${created.day}`, c: '#202c42' },
  ]
})

const canCreate = computed(() => name.value.trim().length >= 2 && isCreditCode(creditCode.value) && !busy.value)

function orderSub(order: EnterpriseOrderPage['items'][number]): string {
  const parts = toBeijingParts(new Date(order.createdAt))
  return `订单 · 经办：${order.operatorName} · ${formatTradeAmount(order.payableMinor)} · ${parts.month}/${parts.day}`
}

function failure(reason: unknown): void {
  if ((reason as { status?: number }).status === 401) emit('unauthorized')
  error.value = reason instanceof Error ? reason.message : '读取失败，请重试'
}

async function loadDetail(): Promise<void> {
  const item = enterprise.value
  members.value = null
  orders.value = null
  if (!item) return
  const [memberResult, orderResult] = await Promise.allSettled([
    identityAccountApi.listMembers(props.accessToken, item.enterpriseId),
    identityAccountApi.listEnterpriseOrders(props.accessToken, item.enterpriseId, 1, 5),
  ])
  if (memberResult.status === 'fulfilled') members.value = memberResult.value
  if (orderResult.status === 'fulfilled') orders.value = orderResult.value
}

async function load(): Promise<void> {
  error.value = ''
  try {
    list.value = await identityAccountApi.listEnterprises(props.accessToken)
    await loadDetail()
  } catch (reason) {
    failure(reason)
  }
}

async function choose(enterpriseId: string): Promise<void> {
  chosenId.value = enterpriseId
  await loadDetail()
}

async function create(): Promise<void> {
  if (!canCreate.value) return
  busy.value = true
  try {
    const created = await identityAccountApi.createEnterprise(props.accessToken, createKey.value, {
      name: name.value.trim(),
      creditCode: creditCode.value.trim().toUpperCase(),
      cityCode: props.cityCode,
    })
    chosenId.value = created.enterpriseId
    createKey.value = createIdempotencyKey()
    uni.showToast({ title: '企业已创建，你是管理员', icon: 'none' })
    emit('changed')
    await load()
  } catch (reason) {
    uni.showToast({ title: reason instanceof Error ? reason.message : '创建失败，请重试', icon: 'none' })
  } finally {
    busy.value = false
  }
}

async function leave(): Promise<void> {
  const item = enterprise.value
  if (!item || busy.value) return
  busy.value = true
  try {
    await identityAccountApi.leaveEnterprise(props.accessToken, item.enterpriseId)
    leaving.value = false
    chosenId.value = null
    uni.showToast({ title: `已退出 ${item.name}`, icon: 'none' })
    emit('changed')
    await load()
  } catch (reason) {
    leaving.value = false
    uni.showToast({ title: reason instanceof Error ? reason.message : '退出失败，请重试', icon: 'none' })
  } finally {
    busy.value = false
  }
}

const leaveText = computed(() => {
  const item = enterprise.value
  if (!item) return ''
  return item.memberCount <= 1
    ? '你是这家企业唯一的成员，退出后企业解散，待确认的邀请一并撤销。以企业身份下的订单记录仍保留。'
    : '退出后不能再以企业身份下单和查看企业订单，已有的订单仍归企业所有。'
})

onMounted(load)
</script>

<template>
  <view class="org" data-testid="org-page">
    <view class="org__top">
      <view class="org__head">
        <button class="org__icon-btn" aria-label="返回" data-testid="org-back" @click="emit('back')">
          <LineIcon :d="BACK" :size="22" color="#202c42" :stroke-width="2" />
        </button>
        <text class="org__title">企业组织</text>
        <button v-if="enterprise" class="org__icon-btn org__icon-btn--right" aria-label="成员与角色" data-testid="org-members-icon"
                @click="emit('members', enterprise.enterpriseId)">
          <LineIcon :d="PEOPLE" :size="22" color="#202c42" :stroke-width="1.8" />
        </button>
      </view>

      <view class="org__body">
        <text v-if="error" class="org__error">{{ error }}</text>

        <view v-if="list && list.items.length > 1" class="org__chips">
          <button
            v-for="item in list.items"
            :key="item.enterpriseId"
            :class="['org__chip', { 'org__chip--on': item.enterpriseId === enterprise?.enterpriseId }]"
            @click="choose(item.enterpriseId)"
          >{{ item.name }}</button>
        </view>

        <template v-if="enterprise">
          <button class="org__card" aria-haspopup="dialog" data-testid="org-card" @click="profileOpen = true">
            <view class="org__badge"><LineIcon :d="BUILDING" :size="26" color="#2f80ed" :stroke-width="1.8" /></view>
            <view class="org__card-copy">
              <text class="org__name" data-testid="org-name">{{ enterprise.name }}</text>
              <view class="org__tags">
                <text :class="['org__tag', enterprise.qualificationReviewed ? 'org__tag--ok' : 'org__tag--wait']">
                  {{ enterprise.qualificationReviewed ? '资料已认证' : '资料待审核' }}
                </text>
                <text class="org__tag" data-testid="org-my-role">我是{{ ROLE_LABELS[enterprise.role] }}</text>
              </view>
            </view>
            <view class="org__profile-link">
              <text>组织资料</text>
              <LineIcon :d="CHEVRON" :size="12" color="#627187" :stroke-width="2" />
            </view>
          </button>

          <view class="org__stats" data-testid="org-stats">
            <view v-for="(stat, index) in stats" :key="stat.t" class="org__stat" :style="{ borderLeftColor: index === 0 ? 'transparent' : '#e5ebf3' }">
              <text class="org__stat-n">{{ stat.n }}</text>
              <text class="org__stat-t">{{ stat.t }}</text>
            </view>
          </view>

          <view class="org__section-head">
            <text class="org__section-title"><text class="org__bar" />企业订单</text>
          </view>
          <view class="org__list" data-testid="org-orders">
            <view v-if="orders && orders.items.length === 0" class="org__empty">还没有以企业身份付款的订单。在“我的”切换到企业身份后下单，订单会记在企业名下。</view>
            <view v-for="(order, index) in orders?.items ?? []" :key="order.orderId" class="org__deal" :style="{ borderTopColor: index === 0 ? 'transparent' : '#f0f1f3' }">
              <view class="org__deal-line">
                <text class="org__deal-title">{{ order.title }}</text>
                <text class="org__deal-tag" :style="{ background: enterpriseOrderTag(order.status).bg, color: enterpriseOrderTag(order.status).fg }">{{ enterpriseOrderTag(order.status).text }}</text>
              </view>
              <text class="org__deal-sub">{{ orderSub(order) }}</text>
            </view>
          </view>

          <view class="org__section-head">
            <text class="org__section-title"><text class="org__bar" />成员</text>
            <button class="org__invite" data-testid="org-invite" @click="emit('members', enterprise.enterpriseId)">
              <text>邀请成员</text>
              <LineIcon :d="CHEVRON" :size="12" color="#ba470b" :stroke-width="2" />
            </button>
          </view>
          <view class="org__list">
            <button
              v-for="(member, index) in (members?.members ?? []).slice(0, 3)"
              :key="member.memberId"
              class="org__member"
              :style="{ borderTopColor: index === 0 ? 'transparent' : '#f0f1f3' }"
              @click="emit('members', enterprise.enterpriseId)"
            >
              <text class="org__avatar" :style="{ background: AVATAR_TONES[index % 3].bg, color: AVATAR_TONES[index % 3].fg }">{{ avatarChar(member.displayName) }}</text>
              <text class="org__member-name">{{ member.displayName }}{{ member.self ? '（我）' : '' }}</text>
              <text :class="['org__role', { 'org__role--admin': member.role === 'ADMIN' }]">{{ ROLE_LABELS[member.role] }}</text>
              <LineIcon :d="CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
            </button>
          </view>

          <button class="org__leave" data-testid="org-leave" @click="leaving = true">退出企业</button>
        </template>

        <template v-else-if="list">
          <view v-if="list.invitations.length" class="org__list org__invites">
            <text class="org__invites-title">待你确认的邀请</text>
            <button
              v-for="invitation in list.invitations"
              :key="invitation.invitationId"
              class="org__member"
              data-testid="org-received-invitation"
              @click="emit('invitation', invitation.invitationId)"
            >
              <view class="org__badge org__badge--small"><LineIcon :d="BUILDING" :size="18" color="#2f80ed" :stroke-width="1.8" /></view>
              <text class="org__member-name">{{ invitation.enterpriseName }} · {{ invitation.invitedByName }}邀请你加入</text>
              <LineIcon :d="CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
            </button>
          </view>
          <view class="org__create" data-testid="org-create">
            <text class="org__create-title">创建企业</text>
            <text class="org__create-text">创建后你是管理员，可以邀请同事加入；成员可以切换到企业身份下单，订单记在企业名下。资质由平台审核后显示“资料已认证”。</text>
            <view class="org__field">
              <text class="org__field-label">企业名称</text>
              <input class="org__input" :value="name" maxlength="40" placeholder="与营业执照一致" placeholder-class="org__placeholder"
                     data-testid="org-create-name" @input="name = inputValue($event)" />
            </view>
            <view class="org__field">
              <text class="org__field-label">信用代码</text>
              <input class="org__input" :value="creditCode" maxlength="18" placeholder="18 位统一社会信用代码" placeholder-class="org__placeholder"
                     data-testid="org-create-code" @input="creditCode = inputValue($event).toUpperCase()" />
            </view>
            <view class="org__field">
              <text class="org__field-label">所在城市</text>
              <text class="org__input org__input--static">{{ cityName || '未选择（可不填）' }}</text>
            </view>
            <text v-if="creditCode && !isCreditCode(creditCode)" class="org__error">信用代码是 18 位数字或大写字母（不含 I、O、Z、S、V）</text>
            <button :class="['org__primary', { 'org__primary--off': !canCreate }]" :disabled="!canCreate" data-testid="org-create-submit" @click="create">创建企业</button>
          </view>
        </template>
      </view>
    </view>

    <template v-if="profileOpen && enterprise">
      <view class="org__mask" @click="profileOpen = false" />
      <view class="org__sheet" role="dialog" aria-label="组织资料" data-testid="org-profile">
        <text class="org__sheet-title">组织资料</text>
        <view class="org__rows">
          <view v-for="row in profileRows" :key="row.k" class="org__row">
            <text class="org__row-k">{{ row.k }}</text>
            <text class="org__row-v" :style="{ color: row.c }">{{ row.v }}</text>
          </view>
        </view>
        <text class="org__fine">资料变更和资质审核由平台处理，请联系平台客服</text>
        <button class="org__primary" @click="profileOpen = false">知道了</button>
      </view>
    </template>

    <ConfirmSheet
      v-if="leaving && enterprise"
      :title="`退出 ${enterprise.name}？`"
      :text="leaveText"
      cancel-text="再想想"
      confirm-text="确认退出"
      :busy="busy"
      test-id="org-leave-confirm"
      @cancel="leaving = false"
      @confirm="leave"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board OrgDetail (390 wide). */
button { margin: 0; padding: 0; border: 0; background: transparent; font-family: inherit; line-height: normal; text-align: left; }
button::after { border: 0; }
.org { display: flex; min-height: 100vh; flex-direction: column; color: #202c42; background: #f5f7fc; }
.org__top { display: flex; flex: 1; flex-direction: column; background: linear-gradient(180deg, #e6f0ff 0, #f5f7fc 180px); padding-top: env(safe-area-inset-top); }
.org__head { display: flex; padding: 8px; align-items: center; gap: 4px; }
.org__icon-btn { display: flex; width: 44px; height: 44px; align-items: center; justify-content: center; }
.org__icon-btn--right { margin-left: auto; }
.org__title { font-size: 18px; font-weight: 700; line-height: 25px; white-space: nowrap; }
.org__body { display: flex; padding: 0 16px calc(24px + env(safe-area-inset-bottom)); flex-direction: column; gap: 10px; }
.org__error { color: #d64f67; font-size: 13px; line-height: 18px; }
.org__chips { display: flex; flex-wrap: wrap; gap: 8px; }
.org__chip { height: 32px; padding: 0 12px; border: 1px solid #dcdfe4; border-radius: 999px; background: #fff; font-size: 13px; line-height: 30px; box-sizing: border-box; }
.org__chip--on { border-color: #ff6a00; color: #ba470b; background: #fff0e3; }
.org__card { display: flex; width: 100%; padding: 14px; align-items: center; gap: 12px; box-sizing: border-box; border-radius: 14px; color: #202c42; background: #fff; }
.org__badge { display: flex; width: 52px; height: 52px; flex: none; align-items: center; justify-content: center; border-radius: 14px; background: #e6f0ff; }
.org__badge--small { width: 34px; height: 34px; border-radius: 10px; }
.org__card-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 6px; }
.org__name { overflow: hidden; font-size: 18px; font-weight: 700; line-height: 25px; text-overflow: ellipsis; white-space: nowrap; }
.org__tags { display: flex; gap: 6px; }
.org__tag { padding: 1px 6px; border-radius: 6px; color: #536178; background: #eef2f8; font-size: 12px; line-height: 18px; white-space: nowrap; }
.org__tag--ok { color: #11804a; background: #e2f6ed; }
.org__tag--wait { color: #ba470b; background: #fff0e3; }
.org__profile-link { display: flex; flex: none; align-items: center; gap: 2px; color: #627187; font-size: 13px; line-height: 18px; white-space: nowrap; }
.org__stats { display: grid; padding: 12px 0; grid-template-columns: repeat(3, minmax(0, 1fr)); border-radius: 14px; background: #fff; }
.org__stat { display: flex; min-height: 52px; flex-direction: column; align-items: center; gap: 2px; border-left: 1px solid transparent; }
.org__stat-n { font-size: 22px; font-weight: 700; line-height: 30px; }
.org__stat-t { color: #627187; font-size: 12px; line-height: 17px; white-space: nowrap; }
.org__section-head { display: flex; min-height: 36px; margin-top: 4px; align-items: center; justify-content: space-between; gap: 8px; }
.org__section-title { display: flex; align-items: center; gap: 8px; font-size: 17px; font-weight: 700; line-height: 24px; white-space: nowrap; }
.org__bar { display: inline-block; width: 4px; height: 16px; margin-right: 8px; border-radius: 2px; background: #ff6a00; vertical-align: -2px; }
.org__invite { display: flex; min-height: 44px; padding-left: 8px; align-items: center; gap: 2px; color: #ba470b; font-size: 13px; white-space: nowrap; }
.org__list { display: flex; padding: 0 14px; flex-direction: column; border-radius: 14px; background: #fff; }
.org__empty { padding: 14px 0; color: #627187; font-size: 13px; line-height: 19px; }
.org__deal { display: flex; min-height: 68px; padding: 12px 0; flex-direction: column; gap: 4px; box-sizing: border-box; border-top: 1px solid transparent; }
.org__deal-line { display: flex; min-width: 0; align-items: center; gap: 8px; }
.org__deal-title { min-width: 0; flex: 1; overflow: hidden; font-size: 15px; font-weight: 500; line-height: 21px; text-overflow: ellipsis; white-space: nowrap; }
.org__deal-tag { flex: none; padding: 1px 6px; border-radius: 6px; font-size: 12px; line-height: 18px; white-space: nowrap; }
.org__deal-sub { overflow: hidden; color: #627187; font-size: 12px; line-height: 17px; text-overflow: ellipsis; white-space: nowrap; }
.org__member { display: flex; min-height: 56px; align-items: center; gap: 10px; border-top: 1px solid transparent; color: #202c42; }
.org__avatar { width: 34px; height: 34px; flex: none; border-radius: 50%; font-size: 14px; font-weight: 700; line-height: 34px; text-align: center; }
.org__member-name { min-width: 0; flex: 1; overflow: hidden; font-size: 15px; line-height: 21px; text-overflow: ellipsis; white-space: nowrap; }
.org__role { flex: none; padding: 1px 6px; border-radius: 6px; color: #536178; background: #eef2f8; font-size: 12px; line-height: 18px; white-space: nowrap; }
.org__role--admin { color: #ba470b; background: #fff0e3; }
.org__leave { height: 46px; margin-top: 8px; border-radius: 14px; color: #d64f67; background: #fff; font-size: 15px; line-height: 46px; text-align: center; }
.org__invites { padding-top: 12px; }
.org__invites-title { font-size: 15px; font-weight: 700; line-height: 21px; }
.org__create { display: flex; padding: 16px 14px; flex-direction: column; gap: 12px; border-radius: 14px; background: #fff; }
.org__create-title { font-size: 17px; font-weight: 700; line-height: 24px; }
.org__create-text { color: #627187; font-size: 13px; line-height: 19px; }
.org__field { display: flex; height: 50px; padding: 0 14px; align-items: center; gap: 10px; border-radius: 12px; background: #f5f7fc; }
.org__field-label { width: 64px; flex: none; color: #627187; font-size: 14px; }
.org__input { min-width: 0; height: 50px; flex: 1; font-size: 15px; line-height: 50px; }
.org__input--static { color: #202c42; }
.org__placeholder { color: #91a0b4; }
.org__primary { height: 46px; border-radius: 999px; color: #fff; background: #ff6a00; font-size: 16px; font-weight: 500; line-height: 46px; text-align: center; }
.org__primary--off, .org__primary[disabled] { color: #fff; background: #c9d3e1; }
.org__mask { position: fixed; inset: 0; z-index: 900; background: rgba(0, 0, 0, 0.45); }
.org__sheet { position: fixed; right: 0; bottom: 0; left: 0; z-index: 901; display: flex; padding: 20px 16px calc(24px + env(safe-area-inset-bottom)); flex-direction: column; gap: 12px; border-radius: 20px 20px 0 0; background: #fff; }
.org__sheet-title { font-size: 18px; font-weight: 700; line-height: 25px; }
.org__rows { display: flex; flex-direction: column; }
.org__row { display: flex; min-height: 40px; padding: 8px 0; align-items: flex-start; gap: 12px; border-top: 1px solid #f0f1f3; font-size: 14px; line-height: 20px; }
.org__row-k { width: 64px; flex: none; color: #627187; }
.org__row-v { min-width: 0; flex: 1; }
.org__fine { color: #738096; font-size: 12px; line-height: 17px; }
</style>
