<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { toBeijingParts } from '@/shared/time/beijing-time'

import { identityAccountApi } from '../../data/identity-account-api-client'
import type { Enterprise, EnterpriseMember, EnterpriseMembers, MemberRole, PendingInvitation } from '../../domain/identity-account'
import { ROLE_GUIDE, ROLE_LABELS, avatarChar, digitsOnly, inputValue, invitationLinkPath, isPhoneNumber } from '../../domain/identity-account'
import ConfirmSheet from '../shared/ConfirmSheet.vue'

// 成员与角色 (board OrgMembers, 390 wide). Everyone in the enterprise sees the members and the
// pending invitations and may invite; an admin taps a member to change the role or remove them.
// The boards' three roles are two here (default decision): 管理员 and 成员; an invited person joins
// as 成员. An invitation goes to a phone number (they confirm after signing in with it) or is a link.
const props = defineProps<{ accessToken: string; enterpriseId: string }>()
const emit = defineEmits<{ back: []; unauthorized: [] }>()

const CHEVRON = 'M9 5l7 7-7 7'
const INFO = 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 11v5M12 7.5h.01'
const AVATAR_TONES = [
  { bg: '#ffe3cc', fg: '#ba470b' },
  { bg: '#dbe9fc', fg: '#1d63c4' },
  { bg: '#d6f2e2', fg: '#11804a' },
]

const enterprise = ref<Enterprise | null>(null)
const data = ref<EnterpriseMembers | null>(null)
const error = ref('')
const note = ref('')
const sheet = ref<'' | 'invite' | 'role' | 'remove'>('')
const inviteKind = ref<'PHONE' | 'LINK'>('PHONE')
const phone = ref('')
const current = ref<EnterpriseMember | null>(null)
const draft = ref<MemberRole>('MEMBER')
const busy = ref(false)

const admin = computed(() => data.value?.myRole === 'ADMIN')
const permNote = computed(() => admin.value
  ? '你是管理员，点成员可以调整角色或移除。'
  : '你是成员，可以邀请成员。调整角色和移除成员由管理员操作。')

function when(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${String(parts.month).padStart(2, '0')}/${String(parts.day).padStart(2, '0')}`
}

function pendingLine(invitation: PendingInvitation): string {
  return `${ROLE_LABELS[invitation.role]} · ${when(invitation.sentAt)} 发送，${when(invitation.expiresAt)} 前有效`
}

async function load(): Promise<void> {
  try {
    const [loadedEnterprise, loadedMembers] = await Promise.all([
      identityAccountApi.readEnterprise(props.accessToken, props.enterpriseId),
      identityAccountApi.listMembers(props.accessToken, props.enterpriseId),
    ])
    enterprise.value = loadedEnterprise
    data.value = loadedMembers
    error.value = ''
  } catch (reason) {
    if ((reason as { status?: number }).status === 401) emit('unauthorized')
    error.value = reason instanceof Error ? reason.message : '读取失败，请重试'
  }
}

function openMember(member: EnterpriseMember): void {
  if (!admin.value || member.self) return
  current.value = member
  draft.value = member.role
  note.value = ''
  sheet.value = 'role'
}

function openInvite(): void {
  phone.value = ''
  inviteKind.value = 'PHONE'
  note.value = ''
  sheet.value = 'invite'
}

async function run(action: () => Promise<string>): Promise<void> {
  if (busy.value) return
  busy.value = true
  try {
    note.value = await action()
    sheet.value = ''
    await load()
  } catch (reason) {
    uni.showToast({ title: reason instanceof Error ? reason.message : '操作失败，请重试', icon: 'none' })
  } finally {
    busy.value = false
  }
}

function sendInvite(): void {
  void run(async () => {
    if (inviteKind.value === 'PHONE') {
      const created = await identityAccountApi.invite(props.accessToken, props.enterpriseId, 'PHONE', phone.value)
      return `已向 ${created.invitation.maskedPhone} 发送邀请，对方用这个手机号登录后在“我的 → 企业组织”确认`
    }
    const created = await identityAccountApi.invite(props.accessToken, props.enterpriseId, 'LINK')
    const link = invitationLinkPath(created.invitation.invitationId, created.inviteCode ?? '')
    uni.setClipboardData({ data: link, showToast: false })
    return '邀请链接已复制（7 天内有效、用一次失效），发给对方在 App 里打开'
  })
}

function saveRole(): void {
  const member = current.value
  if (!member) return
  if (draft.value === member.role) {
    sheet.value = ''
    return
  }
  void run(async () => {
    await identityAccountApi.changeRole(props.accessToken, props.enterpriseId, member.memberId, draft.value)
    return `${member.displayName} 已调整为${ROLE_LABELS[draft.value]}`
  })
}

function remove(): void {
  const member = current.value
  if (!member) return
  void run(async () => {
    await identityAccountApi.removeMember(props.accessToken, props.enterpriseId, member.memberId)
    return `已移除 ${member.displayName}`
  })
}

function revoke(invitation: PendingInvitation): void {
  void run(async () => {
    await identityAccountApi.revokeInvitation(props.accessToken, props.enterpriseId, invitation.invitationId)
    return `已撤销 ${invitation.maskedPhone ?? '邀请链接'} 的邀请`
  })
}

function resend(invitation: PendingInvitation): void {
  void run(async () => {
    await identityAccountApi.renewInvitation(props.accessToken, props.enterpriseId, invitation.invitationId)
    return `已向 ${invitation.maskedPhone} 再次发送邀请`
  })
}

const canSend = computed(() => inviteKind.value === 'LINK' || isPhoneNumber(phone.value))

onMounted(load)
</script>

<template>
  <view class="members" data-testid="org-members-page">
    <AppPageHeader title="成员与角色" test-id="org-members" @back="emit('back')">
      <template #actions>
        <text class="members__head-name">{{ enterprise?.name }}</text>
      </template>
    </AppPageHeader>
    <view class="members__body">
      <text v-if="error" class="members__error">{{ error }}</text>
      <template v-if="data">
        <view class="members__perm" data-testid="org-members-perm">
          <LineIcon :d="INFO" :size="16" color="#1d63c4" :stroke-width="2" />
          <text>{{ permNote }}</text>
        </view>
        <view v-if="note" class="members__note" role="status" data-testid="org-members-note">{{ note }}</view>

        <view class="members__card">
          <view class="members__section-head">
            <text class="members__section-title">成员</text>
            <text class="members__count">{{ data.members.length }} 人</text>
          </view>
          <button
            v-for="(member, index) in data.members"
            :key="member.memberId"
            class="members__row"
            :data-testid="member.self ? 'org-member-self' : 'org-member'"
            @click="openMember(member)"
          >
            <text class="members__avatar" :style="{ background: AVATAR_TONES[index % 3].bg, color: AVATAR_TONES[index % 3].fg }">{{ avatarChar(member.displayName) }}</text>
            <view class="members__copy">
              <text class="members__name">{{ member.displayName }}{{ member.self ? '（我）' : '' }}</text>
              <text class="members__phone">{{ member.maskedPhone ?? '未绑定手机号' }}</text>
            </view>
            <text :class="['members__role', { 'members__role--admin': member.role === 'ADMIN' }]">{{ ROLE_LABELS[member.role] }}</text>
            <LineIcon v-if="admin && !member.self" :d="CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
          </button>
        </view>

        <template v-if="data.invitations.length">
          <view class="members__card">
            <view class="members__section-head">
              <text class="members__section-title">待加入</text>
              <text class="members__count">{{ data.invitations.length }} 人</text>
            </view>
            <view v-for="invitation in data.invitations" :key="invitation.invitationId" class="members__row members__row--pending" data-testid="org-pending-invitation">
              <view class="members__copy">
                <text class="members__name">{{ invitation.maskedPhone ?? '邀请链接' }}</text>
                <text class="members__phone">{{ pendingLine(invitation) }}</text>
              </view>
              <button class="members__small" :aria-label="`撤销邀请：${invitation.maskedPhone ?? '邀请链接'}`" data-testid="org-invitation-revoke" @click="revoke(invitation)">撤销</button>
              <button v-if="invitation.kind === 'PHONE'" class="members__small members__small--orange" :aria-label="`再发一次邀请：${invitation.maskedPhone}`" @click="resend(invitation)">再发一次</button>
            </view>
          </view>
        </template>

        <view class="members__card members__guide">
          <text class="members__section-title">角色说明</text>
          <view v-for="role in (['ADMIN', 'MEMBER'] as const)" :key="role" class="members__guide-row">
            <text :class="['members__role', { 'members__role--admin': role === 'ADMIN' }]">{{ ROLE_LABELS[role] }}</text>
            <text class="members__guide-text">{{ ROLE_GUIDE[role] }}</text>
          </view>
          <text class="members__fine">企业至少保留一名管理员；被移除或退出后不能再以企业身份下单，已有的订单仍归企业所有。</text>
        </view>
      </template>
    </view>
    <view class="members__bar">
      <button class="members__primary" data-testid="org-members-invite" @click="openInvite">邀请成员</button>
    </view>

    <template v-if="sheet === 'invite'">
      <view class="members__mask" @click="sheet = ''" />
      <view class="members__sheet" role="dialog" aria-label="邀请成员" data-testid="org-invite-sheet">
        <text class="members__sheet-title">加入 {{ enterprise?.name }}</text>
        <view class="members__kinds">
          <button :class="['members__kind', { 'members__kind--on': inviteKind === 'PHONE' }]" data-testid="org-invite-kind-phone" @click="inviteKind = 'PHONE'">手机号邀请</button>
          <button :class="['members__kind', { 'members__kind--on': inviteKind === 'LINK' }]" data-testid="org-invite-kind-link" @click="inviteKind = 'LINK'">邀请链接</button>
        </view>
        <view v-if="inviteKind === 'PHONE'" class="members__field">
          <text class="members__prefix">+86</text>
          <view class="members__divider" />
          <input class="members__input" :value="phone" type="number" maxlength="11" placeholder="对方的手机号" placeholder-class="members__placeholder"
                 aria-label="手机号" data-testid="org-invite-phone" @input="phone = digitsOnly(inputValue($event), 11)" />
        </view>
        <view class="members__field-label">加入后的角色</view>
        <view class="members__kinds"><text class="members__kind members__kind--on">成员</text></view>
        <text class="members__fine">{{ inviteKind === 'PHONE'
          ? '对方用这个手机号登录后，在“我的 → 企业组织”确认加入；邀请 7 天内有效。管理员不能通过邀请设置。'
          : '生成一条只能用一次的邀请链接并复制，7 天内有效；拿到链接的人登录后确认加入。' }}</text>
        <view class="members__actions">
          <button class="members__ghost" @click="sheet = ''">取消</button>
          <button :class="['members__primary', { 'members__primary--off': !canSend }]" :disabled="!canSend || busy" data-testid="org-invite-send" @click="sendInvite">
            {{ inviteKind === 'PHONE' ? '发送邀请' : '复制邀请链接' }}
          </button>
        </view>
      </view>
    </template>

    <template v-if="sheet === 'role' && current">
      <view class="members__mask" @click="sheet = ''" />
      <view class="members__sheet" role="dialog" aria-label="调整角色" data-testid="org-role-sheet">
        <view class="members__sheet-head">
          <text class="members__sheet-title">调整角色</text>
          <text class="members__phone">{{ current.displayName }} · {{ current.maskedPhone ?? '未绑定手机号' }}</text>
        </view>
        <button
          v-for="role in (['ADMIN', 'MEMBER'] as const)"
          :key="role"
          :class="['members__option', { 'members__option--on': draft === role }]"
          :data-testid="`org-role-${role.toLowerCase()}`"
          @click="draft = role"
        >
          <view :class="['members__ring', { 'members__ring--on': draft === role }]"><view v-if="draft === role" class="members__ring-dot" /></view>
          <view class="members__copy">
            <text class="members__name">{{ ROLE_LABELS[role] }}</text>
            <text class="members__phone">{{ ROLE_GUIDE[role] }}</text>
          </view>
        </button>
        <button class="members__remove" data-testid="org-member-remove" @click="sheet = 'remove'">移除这位成员</button>
        <view class="members__actions">
          <button class="members__ghost" @click="sheet = ''">取消</button>
          <button class="members__primary" :disabled="busy" data-testid="org-role-save" @click="saveRole">保存</button>
        </view>
      </view>
    </template>

    <ConfirmSheet
      v-if="sheet === 'remove' && current"
      :title="`确认移除 ${current.displayName}？`"
      text="移除后对方不能再以企业身份下单和查看企业订单，已有的订单仍归企业所有。"
      cancel-text="暂不移除"
      confirm-text="确认移除"
      :busy="busy"
      test-id="org-remove-confirm"
      @cancel="sheet = 'role'"
      @confirm="remove"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board OrgMembers (390 wide). */
button { margin: 0; padding: 0; border: 0; background: transparent; font-family: inherit; line-height: normal; text-align: left; }
button::after { border: 0; }
.members { display: flex; min-height: 100vh; flex-direction: column; color: #202c42; background: #f5f7fc; }
.members__body { display: flex; padding: 12px 16px; flex: 1; flex-direction: column; gap: 10px; }
.members__error { color: #d64f67; font-size: 13px; line-height: 18px; }
.members__head-name { padding-right: 8px; color: #627187; font-size: 13px; white-space: nowrap; }
.members__perm { display: flex; padding: 10px 12px; align-items: flex-start; gap: 6px; border-radius: 10px; color: #1d63c4; background: #e6f0ff; font-size: 13px; line-height: 19px; }
.members__note { padding: 10px 12px; border-radius: 10px; color: #11804a; background: #eefaf3; font-size: 13px; line-height: 19px; }
.members__section-head { display: flex; padding: 12px 0 8px; align-items: baseline; gap: 8px; border-bottom: 1px solid #f0f1f3; }
.members__section-title { font-size: 16px; font-weight: 700; line-height: 22px; }
.members__count { color: #738096; font-size: 13px; }
.members__card { display: flex; padding: 0 14px; flex-direction: column; border-radius: 14px; background: #fff; }
.members__row { display: flex; min-height: 64px; align-items: center; gap: 10px; border-top: 1px solid #f0f1f3; color: #202c42; }
.members__section-head + .members__row { border-top-color: transparent; }
.members__row--pending { gap: 8px; }
.members__avatar { width: 36px; height: 36px; flex: none; border-radius: 50%; font-size: 15px; font-weight: 700; line-height: 36px; text-align: center; }
.members__copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; }
.members__name { overflow: hidden; font-size: 15px; line-height: 21px; text-overflow: ellipsis; white-space: nowrap; }
.members__phone { overflow: hidden; color: #738096; font-size: 12px; line-height: 17px; text-overflow: ellipsis; white-space: nowrap; }
.members__role { flex: none; padding: 1px 6px; border-radius: 6px; color: #536178; background: #eef2f8; font-size: 12px; line-height: 18px; white-space: nowrap; }
.members__role--admin { color: #ba470b; background: #fff0e3; }
.members__small { flex: none; height: 32px; padding: 0 12px; border: 1px solid #c9d3e1; border-radius: 999px; background: #fff; font-size: 13px; line-height: 30px; white-space: nowrap; box-sizing: border-box; }
.members__small--orange { border-color: #ff6a00; color: #ba470b; }
.members__guide { padding: 14px; gap: 10px; }
.members__guide-row { display: flex; align-items: flex-start; gap: 8px; }
.members__guide-text { min-width: 0; flex: 1; color: #536178; font-size: 13px; line-height: 19px; }
.members__fine { color: #738096; font-size: 12px; line-height: 17px; }
.members__bar { padding: 10px 16px calc(16px + env(safe-area-inset-bottom)); background: #fff; }
.members__primary { width: 100%; height: 46px; border-radius: 999px; color: #fff; background: #ff6a00; font-size: 16px; font-weight: 500; line-height: 46px; text-align: center; }
.members__primary--off, .members__primary[disabled] { color: #fff; background: #c9d3e1; }
.members__ghost { height: 46px; flex: 1 1 0; border: 1px solid #c9d3e1; border-radius: 999px; background: #fff; font-size: 15px; line-height: 44px; text-align: center; box-sizing: border-box; }
.members__mask { position: fixed; inset: 0; z-index: 900; background: rgba(0, 0, 0, 0.45); }
.members__sheet { position: fixed; right: 0; bottom: 0; left: 0; z-index: 901; display: flex; padding: 20px 16px calc(24px + env(safe-area-inset-bottom)); flex-direction: column; gap: 12px; border-radius: 20px 20px 0 0; background: #fff; }
.members__sheet-head { display: flex; flex-direction: column; gap: 4px; }
.members__sheet-title { font-size: 18px; font-weight: 700; line-height: 25px; }
.members__kinds { display: flex; gap: 8px; }
.members__kind { height: 36px; padding: 0 14px; border: 1px solid #dcdfe4; border-radius: 999px; background: #fff; font-size: 14px; line-height: 34px; box-sizing: border-box; }
.members__kind--on { border-color: #ff6a00; color: #ba470b; background: #fff0e3; font-weight: 500; }
.members__field { display: flex; height: 50px; padding: 0 14px; align-items: center; gap: 10px; border-radius: 12px; background: #f5f7fc; }
.members__field-label { color: #627187; font-size: 13px; line-height: 18px; }
.members__prefix { flex: none; font-size: 15px; }
.members__divider { width: 1px; height: 16px; flex: none; background: #dcdfe3; }
.members__input { min-width: 0; height: 50px; flex: 1; font-size: 15px; }
.members__placeholder { color: #91a0b4; }
.members__actions { display: flex; gap: 10px; }
.members__actions .members__primary { width: auto; flex: 1 1 0; }
.members__option { display: flex; padding: 12px; align-items: center; gap: 10px; border: 1px solid #e5ebf3; border-radius: 12px; background: #fff; }
.members__option--on { border-color: #ff6a00; background: #fffaf5; }
.members__ring { display: flex; width: 18px; height: 18px; flex: none; align-items: center; justify-content: center; border: 1.5px solid #c9d3e1; border-radius: 50%; box-sizing: border-box; }
.members__ring--on { border-color: #ff6a00; }
.members__ring-dot { width: 8px; height: 8px; border-radius: 50%; background: #ff6a00; }
.members__remove { align-self: flex-start; min-height: 40px; color: #d64f67; font-size: 14px; line-height: 40px; }
</style>
