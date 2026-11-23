<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { formatBeijingTime, toBeijingParts } from '@/shared/time/beijing-time'

import { identityAccountApi } from '../../data/identity-account-api-client'
import type { ReceivedInvitation } from '../../domain/identity-account'
import { ROLE_GUIDE, ROLE_LABELS } from '../../domain/identity-account'
import ConfirmSheet from '../shared/ConfirmSheet.vue'

// 加入组织邀请 (board OrgInvite, 390 wide): the invited person's side. A phone invitation is only for
// the signed-in number; a link invitation carries its code. 同意加入 opens the enterprise.
const props = defineProps<{ accessToken: string; invitationId: string; code: string | null }>()
const emit = defineEmits<{ back: []; joined: [enterpriseId: string]; unauthorized: [] }>()

const BUILDING = 'M4 20V8l6-4v16M10 20h10V10l-10-2M14 13h2M14 16h2'
const DOT = 'M12 12h.01'

const invitation = ref<ReceivedInvitation | null>(null)
const error = ref('')
const asking = ref(false)
const busy = ref(false)

function when(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${String(parts.month).padStart(2, '0')}/${String(parts.day).padStart(2, '0')} ${formatBeijingTime(iso)}`
}

const rows = computed(() => {
  const item = invitation.value
  if (!item) return []
  return [
    { k: '邀请人', v: item.invitedByName },
    { k: '加入角色', v: ROLE_LABELS[item.role] },
    { k: '发出时间', v: when(item.sentAt) },
    { k: '有效期', v: `${when(item.expiresAt)} 前` },
    { k: '加入账号', v: item.maskedPhone ? `${item.maskedPhone}（当前登录）` : '当前登录账号' },
  ]
})

const notes = [
  '以企业身份下的订单记在企业名下，企业成员都能看到',
  '管理员可以调整你的角色或把你移出企业',
  '加入后可以在“我的 → 企业组织”退出',
]

async function load(): Promise<void> {
  try {
    invitation.value = await identityAccountApi.readInvitation(props.accessToken, props.invitationId, props.code)
  } catch (reason) {
    if ((reason as { status?: number }).status === 401) emit('unauthorized')
    error.value = (reason as { status?: number }).status === 404
      ? '邀请不存在，或不是发给当前登录手机号的'
      : reason instanceof Error ? reason.message : '读取失败，请重试'
  }
}

async function accept(): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    const enterprise = await identityAccountApi.acceptInvitation(props.accessToken, props.invitationId, props.code)
    uni.showToast({ title: `已加入 ${enterprise.name}`, icon: 'none' })
    emit('joined', enterprise.enterpriseId)
  } catch (reason) {
    uni.showToast({ title: reason instanceof Error ? reason.message : '加入失败，请重试', icon: 'none' })
    void load()
  } finally {
    busy.value = false
  }
}

async function decline(): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    invitation.value = await identityAccountApi.declineInvitation(props.accessToken, props.invitationId, props.code)
    asking.value = false
  } catch (reason) {
    uni.showToast({ title: reason instanceof Error ? reason.message : '操作失败，请重试', icon: 'none' })
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <view class="invite" data-testid="org-invite-page">
    <AppPageHeader title="加入组织邀请" plain test-id="org-invite" @back="emit('back')" />
    <view class="invite__body">
      <text v-if="error" class="invite__error" data-testid="org-invite-error">{{ error }}</text>
      <template v-if="invitation">
        <view class="invite__card invite__hero">
          <view class="invite__badge"><LineIcon :d="BUILDING" :size="26" color="#2f80ed" :stroke-width="1.8" /></view>
          <view class="invite__hero-copy">
            <text class="invite__name">{{ invitation.enterpriseName }}</text>
            <view class="invite__tags">
              <text :class="['invite__tag', { 'invite__tag--ok': invitation.qualificationReviewed }]">{{ invitation.qualificationReviewed ? '资料已认证' : '资料待审核' }}</text>
              <text class="invite__tag">企业</text>
            </view>
          </view>
        </view>
        <text class="invite__lead">{{ invitation.invitedByName }}邀请你加入这个企业，加入后可以用企业身份下单、付款，查看企业订单。</text>
        <view class="invite__card invite__rows">
          <view v-for="row in rows" :key="row.k" class="invite__row">
            <text class="invite__k">{{ row.k }}</text>
            <text class="invite__v">{{ row.v }}</text>
          </view>
        </view>
        <view class="invite__card invite__powers">
          <text class="invite__title">加入后的角色 · {{ ROLE_LABELS[invitation.role] }}</text>
          <text class="invite__text">{{ ROLE_GUIDE[invitation.role] }}</text>
          <text class="invite__fine">调整角色和移除成员由管理员操作</text>
        </view>
        <view class="invite__card invite__powers">
          <text class="invite__title">加入须知</text>
          <view v-for="(text, index) in notes" :key="text" class="invite__note">
            <text class="invite__note-n">{{ index + 1 }}</text>
            <text class="invite__text">{{ text }}</text>
          </view>
          <view class="invite__note">
            <LineIcon :d="DOT" :size="16" color="#ff6a00" :stroke-width="4" />
            <text class="invite__fine">不认识邀请人或没有申请过加入，请直接拒绝。拒绝后邀请人可以重新发送邀请。</text>
          </view>
        </view>
        <view v-if="invitation.status === 'DECLINED'" class="invite__state" data-testid="org-invite-declined">
          <text class="invite__state-title">已拒绝加入 {{ invitation.enterpriseName }}</text>
          <text class="invite__fine">如需加入，请让邀请人重新发送邀请</text>
        </view>
        <view v-else-if="invitation.status === 'EXPIRED'" class="invite__state" data-testid="org-invite-expired">
          <text class="invite__state-title">邀请已过期</text>
          <text class="invite__fine">邀请只在 7 天内有效，请让邀请人重新发送</text>
        </view>
        <view v-else-if="invitation.status !== 'PENDING'" class="invite__state">
          <text class="invite__state-title">这条邀请已处理</text>
        </view>
      </template>
    </view>
    <view class="invite__bar">
      <template v-if="invitation && invitation.status === 'PENDING'">
        <button class="invite__ghost" data-testid="org-invite-decline" @click="asking = true">拒绝</button>
        <button class="invite__primary" :disabled="busy" data-testid="org-invite-accept" @click="accept">同意加入</button>
      </template>
      <button v-else class="invite__primary" @click="emit('back')">返回</button>
    </view>
    <ConfirmSheet
      v-if="asking && invitation"
      :title="`拒绝加入 ${invitation.enterpriseName}？`"
      text="拒绝后这条邀请失效，需要时邀请人可以重新邀请你。"
      cancel-text="再想想"
      confirm-text="确认拒绝"
      :busy="busy"
      test-id="org-invite-decline-confirm"
      @cancel="asking = false"
      @confirm="decline"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board OrgInvite (390 wide). */
button { margin: 0; padding: 0; border: 0; background: transparent; font-family: inherit; line-height: normal; text-align: center; }
button::after { border: 0; }
.invite { display: flex; min-height: 100vh; flex-direction: column; color: #202c42; background: #f5f7fc; }
.invite__body { display: flex; padding: 12px 16px; flex: 1; flex-direction: column; gap: 10px; }
.invite__error { color: #d64f67; font-size: 13px; line-height: 18px; }
.invite__card { display: flex; flex-direction: column; border-radius: 14px; background: #fff; }
.invite__hero { padding: 14px; flex-direction: row; align-items: center; gap: 12px; }
.invite__badge { display: flex; width: 52px; height: 52px; flex: none; align-items: center; justify-content: center; border-radius: 14px; background: #e6f0ff; }
.invite__hero-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 6px; }
.invite__name { overflow: hidden; font-size: 18px; font-weight: 700; line-height: 25px; text-overflow: ellipsis; white-space: nowrap; }
.invite__tags { display: flex; gap: 6px; }
.invite__tag { padding: 1px 6px; border-radius: 6px; color: #536178; background: #eef2f8; font-size: 12px; line-height: 18px; }
.invite__tag--ok { color: #11804a; background: #e2f6ed; }
.invite__lead { padding: 0 4px; color: #536178; font-size: 14px; line-height: 21px; }
.invite__rows { padding: 4px 14px; }
.invite__row { display: flex; min-height: 40px; padding: 8px 0; gap: 12px; border-top: 1px solid #f0f1f3; font-size: 14px; line-height: 20px; }
.invite__row:first-child { border-top-color: transparent; }
.invite__k { width: 64px; flex: none; color: #627187; }
.invite__v { min-width: 0; flex: 1; }
.invite__powers { padding: 14px; gap: 8px; }
.invite__title { font-size: 16px; font-weight: 700; line-height: 22px; }
.invite__text { min-width: 0; color: #536178; font-size: 13px; line-height: 19px; }
.invite__fine { color: #738096; font-size: 12px; line-height: 17px; }
.invite__note { display: flex; align-items: flex-start; gap: 8px; }
.invite__note-n { display: flex; width: 18px; height: 18px; flex: none; align-items: center; justify-content: center; border-radius: 50%; color: #ba470b; background: #fff0e3; font-size: 12px; line-height: 18px; text-align: center; }
.invite__state { display: flex; padding: 12px; flex-direction: column; gap: 4px; border-radius: 10px; background: #eef2f8; }
.invite__state-title { font-size: 15px; font-weight: 500; line-height: 21px; }
.invite__bar { display: flex; padding: 10px 16px calc(16px + env(safe-area-inset-bottom)); gap: 10px; background: #fff; }
.invite__ghost, .invite__primary { height: 46px; flex: 1 1 0; border-radius: 999px; font-size: 16px; line-height: 46px; }
.invite__ghost { border: 1px solid #c9d3e1; color: #202c42; background: #fff; box-sizing: border-box; }
.invite__primary { color: #fff; background: #ff6a00; font-weight: 500; }
.invite__primary[disabled] { background: #c9d3e1; }
</style>
