<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { formatBeijingTime, toBeijingParts } from '@/shared/time/beijing-time'

import { identityAccountApi } from '../../data/identity-account-api-client'
import type { AccountSession } from '../../domain/identity-account'
import { isTabletName, sessionMeta } from '../../domain/identity-account'
import ConfirmSheet from '../shared/ConfirmSheet.vue'

// 登录设备管理 (board SetDevices, 390 wide): the devices still signed in, this one first. This device
// is signed out with 设置 → 退出登录, not here.
const props = defineProps<{ accessToken: string }>()
const emit = defineEmits<{ back: []; phone: []; support: []; unauthorized: [] }>()

const PHONE_ICON = 'M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2'
const TABLET_ICON = 'M6 3h12a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2'
const CHECK = 'M5 12l5 5 9-10'
const CHEVRON = 'M9 5l7 7-7 7'

const sessions = ref<AccountSession[] | null>(null)
const loadError = ref('')
const doneText = ref('')
const asking = ref<AccountSession | 'all' | null>(null)
const busy = ref(false)

const others = computed(() => (sessions.value ?? []).filter((session) => !session.current))

function when(iso: string): string {
  const parts = toBeijingParts(new Date(iso))
  return `${String(parts.month).padStart(2, '0')}/${String(parts.day).padStart(2, '0')} ${formatBeijingTime(iso)}`
}

async function load(): Promise<void> {
  loadError.value = ''
  try {
    sessions.value = await identityAccountApi.listSessions(props.accessToken)
  } catch (error) {
    if ((error as { status?: number }).status === 401) emit('unauthorized')
    loadError.value = error instanceof Error ? error.message : '读取失败，请重试'
  }
}

const sheetTitle = computed(() => (asking.value === 'all' ? '退出其他全部设备？' : '退出这台设备？'))
const sheetText = computed(() => {
  const who = asking.value === 'all' ? '除本机外的设备' : `${(asking.value as AccountSession | null)?.deviceName ?? '该设备'} `
  return `${who}需要重新登录才能使用，订单和消息不受影响。`
})

async function confirm(): Promise<void> {
  const target = asking.value
  if (!target || busy.value) return
  busy.value = true
  try {
    if (target === 'all') {
      await identityAccountApi.revokeOtherSessions(props.accessToken)
      doneText.value = '已退出其他全部设备'
    } else {
      await identityAccountApi.revokeSession(props.accessToken, target.sessionId)
      doneText.value = `已退出 ${target.deviceName}`
    }
    asking.value = null
    await load()
  } catch (error) {
    uni.showToast({ title: error instanceof Error ? error.message : '操作失败，请重试', icon: 'none' })
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <view class="devices" data-testid="devices-page">
    <AppPageHeader title="登录设备管理" plain test-id="devices" @back="emit('back')" />
    <view class="devices__body">
      <text v-if="sessions" class="devices__intro">这些设备最近登录过你的账号，共 {{ sessions.length }} 台</text>
      <text v-if="loadError" class="devices__error">{{ loadError }}</text>
      <view v-if="doneText" class="devices__done" role="status" data-testid="devices-done">
        <view class="devices__done-icon"><LineIcon :d="CHECK" :size="12" color="#ffffff" :stroke-width="3.2" /></view>
        <text class="devices__done-text">{{ doneText }}</text>
      </view>
      <view v-if="sessions && sessions.length" class="devices__card">
        <view
          v-for="session in sessions"
          :key="session.sessionId"
          class="devices__row"
          :data-testid="session.current ? 'device-current' : 'device-other'"
        >
          <view class="devices__icon" :style="{ background: session.current ? '#fff0e3' : '#eef2f8' }">
            <LineIcon :d="isTabletName(session.deviceName) ? TABLET_ICON : PHONE_ICON" :size="22"
                      :color="session.current ? '#ff6a00' : '#536178'" :stroke-width="1.8" />
          </view>
          <view class="devices__copy">
            <view class="devices__name-line">
              <text class="devices__name">{{ session.deviceName }}</text>
              <text v-if="session.current" class="devices__me">本机</text>
            </view>
            <text class="devices__meta">{{ sessionMeta(session, when) }}</text>
          </view>
          <button
            v-if="!session.current"
            class="devices__out"
            :aria-label="`退出 ${session.deviceName}`"
            data-testid="device-sign-out"
            @click="asking = session"
          >退出</button>
        </view>
      </view>
      <button v-if="others.length" class="devices__all" data-testid="devices-sign-out-all" @click="asking = 'all'">退出其他全部设备</button>
      <text v-else-if="sessions" class="devices__none">没有其他登录设备</text>

      <view class="devices__help">
        <view class="devices__help-head">
          <text class="devices__help-title">发现不认识的设备？</text>
          <text class="devices__help-sub">先退出这台设备，再更换手机号</text>
        </view>
        <button class="devices__link" data-testid="devices-phone" @click="emit('phone')">
          <text class="devices__link-text">更换手机号</text>
          <LineIcon :d="CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
        <button class="devices__link" data-testid="devices-support" @click="emit('support')">
          <text class="devices__link-text">联系平台客服</text>
          <LineIcon :d="CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
      </view>
    </view>
    <ConfirmSheet
      v-if="asking"
      :title="sheetTitle"
      :text="sheetText"
      cancel-text="取消"
      confirm-text="确认退出"
      :busy="busy"
      test-id="devices-confirm"
      @cancel="asking = null"
      @confirm="confirm"
    />
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board SetDevices (390 wide). */
button { margin: 0; padding: 0; border: 0; background: transparent; font-family: inherit; line-height: normal; text-align: left; }
button::after { border: 0; }
.devices { display: flex; min-height: 100vh; flex-direction: column; color: #202c42; background: #f5f7fc; }
.devices__body { display: flex; padding: 12px 16px calc(24px + env(safe-area-inset-bottom)); flex-direction: column; gap: 10px; }
.devices__intro { padding: 0 4px; color: #627187; font-size: 13px; line-height: 19px; }
.devices__error { color: #d64f67; font-size: 13px; line-height: 18px; }
.devices__done { display: flex; padding: 10px 12px; align-items: center; gap: 8px; border-radius: 10px; background: #eefaf3; }
.devices__done-icon { display: flex; width: 20px; height: 20px; flex: none; align-items: center; justify-content: center; border-radius: 50%; background: #159a70; }
.devices__done-text { min-width: 0; color: #11804a; font-size: 14px; font-weight: 500; line-height: 20px; }
.devices__card { display: flex; flex-direction: column; border-radius: 14px; background: #fff; }
.devices__row { display: flex; min-height: 72px; padding: 10px 14px; align-items: center; gap: 12px; box-sizing: border-box; border-bottom: 1px solid #f0f1f3; }
.devices__row:last-child { border-bottom-color: transparent; }
.devices__icon { display: flex; width: 44px; height: 44px; flex: none; align-items: center; justify-content: center; border-radius: 12px; }
.devices__copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 3px; }
.devices__name-line { display: flex; min-width: 0; align-items: center; gap: 6px; }
.devices__name { overflow: hidden; font-size: 15px; font-weight: 500; line-height: 21px; text-overflow: ellipsis; white-space: nowrap; }
.devices__me { flex: none; padding: 1px 6px; border-radius: 6px; color: #ba470b; background: #fff0e3; font-size: 12px; line-height: 16px; white-space: nowrap; }
.devices__meta { color: #738096; font-size: 12px; line-height: 17px; white-space: nowrap; }
.devices__out { flex: none; height: 36px; padding: 0 16px; border: 1px solid #c9d3e1; border-radius: 999px; color: #202c42; background: #fff; font-size: 13px; line-height: 34px; white-space: nowrap; box-sizing: border-box; }
.devices__all { width: 100%; height: 50px; border-radius: 14px; color: #202c42; background: #fff; font-size: 15px; font-weight: 500; line-height: 50px; text-align: center; }
.devices__none { padding: 6px 0; color: #738096; font-size: 13px; line-height: 19px; text-align: center; }
.devices__help { display: flex; padding: 14px 14px 4px; flex-direction: column; border-radius: 14px; background: #fff; }
.devices__help-head { display: flex; padding-bottom: 10px; flex-direction: column; gap: 2px; }
.devices__help-title { font-size: 16px; font-weight: 700; line-height: 22px; }
.devices__help-sub { color: #627187; font-size: 13px; line-height: 19px; }
.devices__link { display: flex; min-height: 46px; align-items: center; gap: 8px; border-top: 1px solid #f0f1f3; color: #202c42; }
.devices__link-text { min-width: 0; flex: 1; font-size: 14px; line-height: 20px; white-space: nowrap; }
</style>
