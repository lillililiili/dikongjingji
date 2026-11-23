<script setup lang="ts">
import { computed, ref } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'

import { identityAccountApi } from '../../data/identity-account-api-client'
import { digitsOnly, inputValue, isPhoneNumber, isSmsCode, maskPhone } from '../../domain/identity-account'
import SmsCodeField from '../shared/SmsCodeField.vue'

// 更换手机号 (board SetPhone, 390 wide): verify the current number, bind the new one, done. An
// account without a number (made by the platform) starts at 绑定新手机号. The server signs out the
// other devices after a change; this one stays in.
const props = defineProps<{ accessToken: string; maskedPhone: string | null }>()
const emit = defineEmits<{ back: []; devices: []; support: [] }>()

const CHECK = 'M5 12l5 5 9-10'
const CHEVRON = 'M9 5l7 7-7 7'

const binding = computed(() => !props.maskedPhone)
const step = ref<1 | 2 | 3>(props.maskedPhone ? 1 : 2)
const oldCode = ref('')
const newPhone = ref('')
const newCode = ref('')
const ticket = ref<string | null>(null)
const resultPhone = ref('')
const signedOut = ref(0)
const busy = ref(false)
const error = ref('')

const labels = computed(() => binding.value ? ['绑定新号码', '完成'] : ['验证当前号码', '绑定新号码', '完成'])
const steps = computed(() => labels.value.map((label, index) => {
  const n = binding.value ? index + 2 : index + 1
  const done = n < step.value || step.value === 3
  const reached = n <= step.value
  return {
    n: index + 1,
    label,
    done,
    current: n === step.value,
    hasLine: index > 0,
    lineBg: done ? '#159a70' : reached ? '#ff6a00' : '#e5ebf3',
    bg: done ? '#159a70' : reached ? '#ff6a00' : '#eef2f8',
    fg: reached ? '#fff' : '#738096',
    lc: reached ? '#202c42' : '#738096',
  }
}))

const terms = [
  '账号不变，订单、报名、参与人和企业身份都保留',
  '登录和验证码改用新手机号',
  '其他设备需要用新手机号重新登录',
]

const ready = computed(() => step.value === 1
  ? isSmsCode(oldCode.value)
  : isPhoneNumber(newPhone.value) && isSmsCode(newCode.value))

function failed(reason: unknown): never {
  error.value = reason instanceof Error ? reason.message : '操作失败，请重试'
  throw reason
}

async function sendOld(): Promise<number> {
  error.value = ''
  try {
    return (await identityAccountApi.sendAccountCode(props.accessToken, 'VERIFY_CURRENT')).resendAfterSeconds
  } catch (reason) {
    return failed(reason)
  }
}

async function sendNew(): Promise<number> {
  error.value = ''
  if (!isPhoneNumber(newPhone.value)) {
    error.value = '请输入 11 位新手机号'
    throw new Error('phone')
  }
  try {
    return (await identityAccountApi.sendAccountCode(props.accessToken, 'BIND_NEW', newPhone.value)).resendAfterSeconds
  } catch (reason) {
    return failed(reason)
  }
}

async function next(): Promise<void> {
  if (!ready.value || busy.value) return
  busy.value = true
  error.value = ''
  try {
    if (step.value === 1) {
      ticket.value = (await identityAccountApi.verifyCurrentPhone(props.accessToken, oldCode.value)).changeTicket
      step.value = 2
    } else {
      const result = await identityAccountApi.changePhone(props.accessToken, {
        newPhone: newPhone.value,
        code: newCode.value,
        changeTicket: ticket.value,
      })
      resultPhone.value = result.maskedPhone
      signedOut.value = result.signedOutSessions
      step.value = 3
    }
  } catch (reason) {
    error.value = reason instanceof Error ? reason.message : '操作失败，请重试'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <view class="set-phone" data-testid="set-phone-page">
    <AppPageHeader :title="binding ? '绑定手机号' : '更换手机号'" test-id="set-phone" @back="emit('back')" />
    <view class="set-phone__body">
      <view class="set-phone__steps" role="list" aria-label="更换步骤" :style="{ gridTemplateColumns: `repeat(${steps.length}, minmax(0, 1fr))` }">
        <view v-for="item in steps" :key="item.label" class="set-phone__step" role="listitem" :aria-current="item.current ? 'step' : 'false'">
          <view v-if="item.hasLine" class="set-phone__line" :style="{ background: item.lineBg }" />
          <view class="set-phone__dot" :style="{ background: item.bg, color: item.fg }">
            <LineIcon v-if="item.done" :d="CHECK" :size="13" color="#ffffff" :stroke-width="3" />
            <text v-else>{{ item.n }}</text>
          </view>
          <text class="set-phone__step-label" :style="{ color: item.lc, fontWeight: item.current ? 500 : 400 }">{{ item.label }}</text>
        </view>
      </view>

      <view v-if="step === 1" class="set-phone__card set-phone__card--form">
        <view class="set-phone__heading">
          <text class="set-phone__title">验证当前手机号</text>
          <text class="set-phone__sub">验证码发送到 {{ maskedPhone }}</text>
        </view>
        <SmsCodeField v-model="oldCode" label="验证码" test-id="set-phone-old" :send="sendOld" />
        <button class="set-phone__help" data-testid="set-phone-support" @click="emit('support')">
          <text>这个号码收不到验证码？联系平台客服</text>
          <LineIcon :d="CHEVRON" :size="12" color="#536178" :stroke-width="2" />
        </button>
      </view>

      <view v-if="step === 2" class="set-phone__card set-phone__card--form">
        <view class="set-phone__heading">
          <text class="set-phone__title">绑定新手机号</text>
          <text class="set-phone__sub">一个手机号只能绑定一个账号</text>
        </view>
        <view class="set-phone__field">
          <text class="set-phone__prefix">+86</text>
          <view class="set-phone__divider" />
          <input
            class="set-phone__input"
            :value="newPhone"
            type="number"
            maxlength="11"
            placeholder="请输入新手机号"
            placeholder-class="set-phone__placeholder"
            aria-label="新手机号"
            data-testid="set-phone-new"
            @input="newPhone = digitsOnly(inputValue($event), 11)"
          />
        </view>
        <SmsCodeField v-model="newCode" label="新手机号验证码" test-id="set-phone-new" :send="sendNew" />
      </view>

      <text v-if="error" class="set-phone__error" data-testid="set-phone-error">{{ error }}</text>

      <view v-if="step !== 3" class="set-phone__card set-phone__terms">
        <text class="set-phone__terms-title">{{ binding ? '绑定后' : '更换后' }}</text>
        <view v-for="term in terms" :key="term" class="set-phone__term">
          <LineIcon :d="CHECK" :size="16" color="#159a70" :stroke-width="2.4" />
          <text class="set-phone__term-text">{{ term }}</text>
        </view>
      </view>

      <template v-else>
        <view class="set-phone__card set-phone__done" data-testid="set-phone-done">
          <view class="set-phone__done-icon"><LineIcon :d="CHECK" :size="28" color="#ffffff" :stroke-width="3" /></view>
          <text class="set-phone__done-title">{{ binding ? '手机号已绑定' : '手机号已更换' }}</text>
          <text class="set-phone__done-text">新手机号 {{ resultPhone || maskPhone(newPhone) }}</text>
          <text class="set-phone__done-note">下次登录请使用新手机号</text>
        </view>
        <button v-if="!binding" class="set-phone__card set-phone__devices" data-testid="set-phone-devices" @click="emit('devices')">
          <view class="set-phone__devices-copy">
            <text class="set-phone__devices-title">登录设备管理</text>
            <text class="set-phone__devices-sub">{{ signedOut > 0 ? `其他 ${signedOut} 台设备已退出，需用新手机号重新登录` : '其他设备已退出，需用新手机号重新登录' }}</text>
          </view>
          <LineIcon :d="CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
      </template>
    </view>

    <view class="set-phone__bar">
      <button
        v-if="step !== 3"
        :class="['set-phone__next', { 'set-phone__next--off': !ready }]"
        :disabled="!ready || busy"
        data-testid="set-phone-next"
        @click="next"
      >{{ step === 1 ? '下一步' : binding ? '确认绑定' : '确认更换' }}</button>
      <button v-else class="set-phone__next" data-testid="set-phone-finish" @click="emit('back')">返回设置</button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board SetPhone (390 wide). */
button { margin: 0; padding: 0; border: 0; background: transparent; font-family: inherit; line-height: normal; text-align: left; }
button::after { border: 0; }
.set-phone { display: flex; min-height: 100vh; flex-direction: column; color: #202c42; background: #f5f7fc; }
.set-phone__body { display: flex; padding: 12px 16px; flex: 1; flex-direction: column; gap: 10px; }
.set-phone__steps { display: grid; padding: 14px 0 12px; border-radius: 14px; background: #fff; }
.set-phone__step { position: relative; display: flex; flex-direction: column; align-items: center; gap: 6px; }
.set-phone__line { position: absolute; top: 11px; left: -50%; width: 100%; height: 2px; }
.set-phone__dot { position: relative; z-index: 1; display: flex; width: 24px; height: 24px; align-items: center; justify-content: center; border-radius: 50%; font-size: 13px; font-weight: 700; }
.set-phone__step-label { font-size: 13px; line-height: 18px; white-space: nowrap; }
.set-phone__card { display: flex; flex-direction: column; border-radius: 14px; background: #fff; }
.set-phone__card--form { padding: 16px 14px 14px; gap: 12px; }
.set-phone__heading { display: flex; flex-direction: column; gap: 2px; }
.set-phone__title { font-size: 17px; font-weight: 700; line-height: 24px; }
.set-phone__sub { color: #627187; font-size: 13px; line-height: 19px; }
.set-phone__help { display: flex; min-height: 44px; align-items: center; align-self: flex-start; gap: 2px; color: #536178; font-size: 13px; white-space: nowrap; }
.set-phone__field { display: flex; height: 50px; padding: 0 14px; align-items: center; gap: 10px; border-radius: 12px; background: #f5f7fc; }
.set-phone__prefix { flex: none; font-size: 15px; }
.set-phone__divider { width: 1px; height: 16px; flex: none; background: #dcdfe3; }
.set-phone__input { min-width: 0; height: 50px; flex: 1; font-size: 15px; }
.set-phone__placeholder { color: #91a0b4; }
.set-phone__error { color: #d64f67; font-size: 13px; line-height: 18px; }
.set-phone__terms { padding: 14px; gap: 8px; }
.set-phone__terms-title { margin-bottom: 2px; font-size: 16px; font-weight: 700; line-height: 22px; }
.set-phone__term { display: flex; gap: 8px; font-size: 13px; line-height: 19px; }
.set-phone__term-text { min-width: 0; color: #536178; }
.set-phone__done { padding: 32px 14px 28px; align-items: center; gap: 6px; }
.set-phone__done-icon { display: flex; width: 56px; height: 56px; margin-bottom: 8px; align-items: center; justify-content: center; border-radius: 50%; background: #159a70; }
.set-phone__done-title { font-size: 20px; font-weight: 700; line-height: 28px; }
.set-phone__done-text { color: #536178; font-size: 14px; line-height: 20px; white-space: nowrap; }
.set-phone__done-note { color: #738096; font-size: 13px; line-height: 19px; white-space: nowrap; }
.set-phone__devices { display: flex; min-height: 56px; padding: 10px 14px; flex-direction: row; align-items: center; gap: 12px; }
.set-phone__devices-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; }
.set-phone__devices-title { font-size: 15px; line-height: 21px; }
.set-phone__devices-sub { color: #738096; font-size: 12px; line-height: 17px; }
.set-phone__bar { padding: 10px 16px calc(16px + env(safe-area-inset-bottom)); background: #fff; }
.set-phone__next { width: 100%; height: 48px; border-radius: 999px; color: #fff; background: #ff6a00; font-size: 16px; font-weight: 500; line-height: 48px; text-align: center; }
.set-phone__next--off, .set-phone__next[disabled] { color: #fff; background: #c9d3e1; }
</style>
