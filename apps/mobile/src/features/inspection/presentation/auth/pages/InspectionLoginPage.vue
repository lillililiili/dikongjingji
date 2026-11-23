<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, onUnmounted, ref } from 'vue'
import { onBackPress, onLoad, onReady } from '@dcloudio/uni-app'

import { ACCOUNT_PREVIEW_ENABLED } from '@/features/account/application/account-preview'
import { currentLoginDevice } from '@/features/account/application/login-device'
import { identityAccountApi } from '@/features/account/data/identity-account-api-client'
import { codeButtonText, digitsOnly, inputValue, isPhoneNumber, isSmsCode } from '@/features/account/domain/identity-account'
import { useCurrentCityStore } from '@/shared/city/state/current-city'

import { SUPPORT_PREVIEW_ENABLED } from '@/features/support/application/support-preview'
import type { AgreementDoc } from '@/features/settings/domain/agreement'
import { buildAgreementUrl } from '@/features/settings/navigation/settings-routes'
import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { undoNeedlessKeyboardPan, watchH5KeyboardViewport } from '@/shared/ui/keyboard-viewport'
import {
  completeBookingAuthenticationRecovery,
  saveBookingAuthSession,
} from '../../../application/service-flow/booking-auth-context'
import { inspectionAuthApi } from '../../../data/service-flow/uni-booking-http-client'
import { isRootTabReturnUrl, normalizeInspectionReturnUrl } from '../../../navigation/inspection-routes'
import { describeLoginResume } from '../login-resume'

interface LoginRouteQuery {
  returnUrl?: string
}

const loginName = ref('')
const password = ref('')
const returnUrl = ref('/pages/home/index')
const isSubmitting = ref(false)
const errorMessage = ref('')
// Board Login: the agreement is not ticked in advance; 登录 stays orange and says what is missing.
const agreementAccepted = ref(false)
const resumeAction = computed(() => describeLoginResume(returnUrl.value))

// Board Login (S5-8): preview builds sign in with the phone number and a code (another number than
// the one on this phone; 本机号码一键登录 needs the carriers' service, which is not connected). A number
// that has no account yet gets one. The account-and-password form stays for accounts made by the
// platform; public builds only have it.
const mode = ref<'phone' | 'password'>(ACCOUNT_PREVIEW_ENABLED ? 'phone' : 'password')
const phone = ref('')
const code = ref('')
const secondsLeft = ref(0)
const sendingCode = ref(false)
let countdown: ReturnType<typeof setInterval> | null = null
const cityStore = useCurrentCityStore()

function onPhoneInput(event: Event): void {
  phone.value = digitsOnly(inputValue(event), 11)
}

function onCodeInput(event: Event): void {
  code.value = digitsOnly(inputValue(event), 6)
}

function startCountdown(seconds: number): void {
  secondsLeft.value = seconds
  if (countdown) clearInterval(countdown)
  countdown = setInterval(() => {
    secondsLeft.value = Math.max(0, secondsLeft.value - 1)
    if (secondsLeft.value === 0 && countdown) {
      clearInterval(countdown)
      countdown = null
    }
  }, 1000)
}

onUnmounted(() => {
  if (countdown) clearInterval(countdown)
})

async function sendCode(): Promise<void> {
  if (sendingCode.value || secondsLeft.value > 0) return
  if (!isPhoneNumber(phone.value)) {
    errorMessage.value = '请输入 11 位手机号'
    return
  }
  sendingCode.value = true
  errorMessage.value = ''
  try {
    const sent = await identityAccountApi.sendLoginCode(phone.value)
    startCountdown(sent.resendAfterSeconds)
    uni.showToast({ title: `验证码已发送至 ${sent.maskedPhone}`, icon: 'none' })
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '验证码发送失败，请重试'
  } finally {
    sendingCode.value = false
  }
}

async function submitPhoneLogin(): Promise<void> {
  if (isSubmitting.value || !agreementAccepted.value) return
  if (!isPhoneNumber(phone.value) || !isSmsCode(code.value)) {
    errorMessage.value = '请输入手机号和 6 位验证码'
    return
  }
  isSubmitting.value = true
  errorMessage.value = ''
  try {
    const result = await identityAccountApi.loginWithCode(phone.value, code.value,
      currentLoginDevice(cityStore.currentCity?.code ?? null))
    saveBookingAuthSession(result)
    completeBookingAuthenticationRecovery()
    if (result.registered) uni.showToast({ title: '已为这个手机号开通账号', icon: 'none' })
    continueToProtectedPage()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败，请重试'
  } finally {
    isSubmitting.value = false
  }
}

function useCarrierLogin(): void {
  uni.showToast({ title: '本机号码一键登录需要运营商认证服务，暂未开通，请用验证码登录', icon: 'none' })
}

// The account form is drawn only after switching to it (S5-8), so its fields get the plain-typing
// attributes then, the same way the page sets them when it is first drawn.
function switchMode(next: 'phone' | 'password'): void {
  mode.value = next
  errorMessage.value = ''
  if (next !== 'password') return
  void nextTick(() => {
    // #ifdef H5
    document.querySelectorAll('[data-plain-input] input').forEach((field) => {
      field.setAttribute('autocapitalize', 'off')
      field.setAttribute('autocorrect', 'off')
      field.setAttribute('spellcheck', 'false')
    })
    // #endif
    // #ifdef APP-PLUS
    const pages = getCurrentPages() as Array<{ $getAppWebview?: () => { evalJS(script: string): void } }>
    pages[pages.length - 1]?.$getAppWebview?.().evalJS(PLAIN_FIELD_SCRIPT)
    // #endif
  })
}

onLoad((query: LoginRouteQuery | undefined) => {
  returnUrl.value = normalizeInspectionReturnUrl(query?.returnUrl ?? '')
})

// A login name is typed as it is: no capital first letter, no autocorrect, no spell check. uni-app's
// <input> does not pass these attributes on to the real field, so they are set on it once the page is
// drawn: on H5 in this document, on the App in the page's webview (its view layer has the DOM).
const PLAIN_FIELD_SCRIPT = `document.querySelectorAll('[data-plain-input] input').forEach(function(f){`
  + `f.setAttribute('autocapitalize','off');f.setAttribute('autocorrect','off');f.setAttribute('spellcheck','false')})`
let stopKeyboardWatch: () => void = () => {}

// H5: onMounted, not onReady. A page hook registered in this component (the page file only wraps it)
// is never called on H5, so the fields kept their defaults; by the time this component is mounted
// its <input> children are in the document.
onMounted(() => {
  // #ifdef H5
  document.querySelectorAll('[data-plain-input] input').forEach((field) => {
    field.setAttribute('autocapitalize', 'off')
    field.setAttribute('autocorrect', 'off')
    field.setAttribute('spellcheck', 'false')
  })
  // The fields sit high on the page: iOS still pans it when the keyboard opens and the head goes under
  // the status bar. Undo that pan whenever the focused field would be in sight without it.
  stopKeyboardWatch = watchH5KeyboardViewport((viewport) => {
    setTimeout(() => undoNeedlessKeyboardPan(viewport), 50)
  })
  // #endif
})

onReady(() => {
  // #ifdef APP-PLUS
  const pages = getCurrentPages() as Array<{ $getAppWebview?: () => { evalJS(script: string): void } }>
  pages[pages.length - 1]?.$getAppWebview?.().evalJS(PLAIN_FIELD_SCRIPT)
  // #endif
})

onBeforeUnmount(() => stopKeyboardWatch())

onBackPress(() => {
  exitLogin()
  return true
})

async function submitLogin(): Promise<void> {
  if (isSubmitting.value) return
  const normalizedLoginName = loginName.value.trim()
  if (!normalizedLoginName || !password.value) {
    errorMessage.value = '请输入用户名和密码'
    return
  }
  if (!agreementAccepted.value) {
    uni.showToast({ title: '请先阅读并同意用户协议和隐私政策', icon: 'none' })
    return
  }

  isSubmitting.value = true
  errorMessage.value = ''
  try {
    const response = await inspectionAuthApi.login(
      normalizedLoginName,
      password.value,
      ACCOUNT_PREVIEW_ENABLED
        ? { ...currentLoginDevice(cityStore.currentCity?.code ?? null) }
        : undefined,
    )
    saveBookingAuthSession(response)
    completeBookingAuthenticationRecovery()
    continueToProtectedPage()
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败，请重试'
  } finally {
    isSubmitting.value = false
  }
}

function continueToProtectedPage(): void {
  const target = normalizeInspectionReturnUrl(returnUrl.value)
  if (isRootTabReturnUrl(target)) {
    uni.reLaunch({ url: target })
    return
  }
  uni.redirectTo({
    url: target,
    fail: () => uni.reLaunch({ url: target }),
  })
}

// The agreement pages are part of 设置与公共页 (M18, preview builds); public builds name them only.
function openAgreement(doc: AgreementDoc): void {
  if (!SUPPORT_PREVIEW_ENABLED) {
    uni.showToast({ title: '协议正文待提供', icon: 'none' })
    return
  }
  uni.navigateTo({ url: buildAgreementUrl(doc) })
}

function exitLogin(): void {
  completeBookingAuthenticationRecovery()
  uni.reLaunch({ url: '/pages/home/index' })
}
</script>

<template>
  <view :class="['inspection-login', { 'inspection-login--phone': mode === 'phone' }]">
    <AppPageHeader class="inspection-login__header" title="登录" test-id="login" @back="exitLogin" />

    <main class="inspection-login__content">
      <view v-if="resumeAction" class="inspection-login__resume" data-testid="login-resume">
        <text class="inspection-login__resume-title">登录后继续：{{ resumeAction }}</text>
        <text class="inspection-login__resume-text">登录成功后自动回到原操作</text>
      </view>

      <view v-if="mode === 'phone'" class="inspection-login__form" data-testid="login-phone-form">
        <text class="inspection-login__form-title">验证码登录</text>
        <view class="inspection-login__input inspection-login__input--soft">
          <text class="inspection-login__prefix">+86</text>
          <view class="inspection-login__divider" />
          <input
            :value="phone"
            type="number"
            maxlength="11"
            placeholder="请输入手机号"
            placeholder-class="inspection-login__placeholder"
            aria-label="手机号"
            data-testid="login-phone"
            @input="onPhoneInput"
          />
        </view>
        <view class="inspection-login__input inspection-login__input--soft inspection-login__input--code">
          <input
            :value="code"
            type="number"
            maxlength="6"
            placeholder="请输入验证码"
            placeholder-class="inspection-login__placeholder"
            aria-label="验证码"
            data-testid="login-code"
            @input="onCodeInput"
            @confirm="submitPhoneLogin"
          />
          <button
            :class="['inspection-login__code', { 'inspection-login__code--wait': secondsLeft > 0 }]"
            :disabled="secondsLeft > 0 || sendingCode"
            data-testid="login-send-code"
            @click="sendCode"
          >
            {{ codeButtonText(secondsLeft) }}
          </button>
        </view>
        <text v-if="errorMessage" class="inspection-login__error">{{ errorMessage }}</text>
        <button
          :class="['inspection-login__submit', { 'inspection-login__submit--off': !agreementAccepted }]"
          :disabled="isSubmitting || !agreementAccepted"
          data-testid="login-phone-submit"
          @click="submitPhoneLogin"
        >
          {{ isSubmitting ? '正在登录…' : '登录' }}
        </button>
        <button class="inspection-login__switch" data-testid="login-carrier" @click="useCarrierLogin">使用本机号码一键登录</button>
      </view>

      <view v-else class="inspection-login__form">
        <text class="inspection-login__form-title">账号登录</text>
        <view class="inspection-login__input" data-plain-input>
          <LineIcon name="person" :size="18" color="#738096" />
          <input
            v-model="loginName"
            type="text"
            maxlength="80"
            placeholder="请输入用户名"
            placeholder-class="inspection-login__placeholder"
            aria-label="用户名"
            confirm-type="next"
          />
        </view>
        <view class="inspection-login__input">
          <LineIcon name="lock" :size="18" color="#738096" />
          <input
            v-model="password"
            type="text"
            password
            maxlength="256"
            placeholder="请输入密码"
            placeholder-class="inspection-login__placeholder"
            aria-label="密码"
            confirm-type="done"
            @confirm="submitLogin"
          />
        </view>
        <text v-if="errorMessage" class="inspection-login__error">{{ errorMessage }}</text>
        <button
          class="inspection-login__submit"
          :disabled="isSubmitting"
          @click="submitLogin"
        >
          {{ isSubmitting ? '正在登录…' : '登录' }}
        </button>
      </view>

      <view
        class="inspection-login__agreement"
        data-testid="login-agreement"
        role="checkbox"
        :aria-checked="agreementAccepted ? 'true' : 'false'"
        @click="agreementAccepted = !agreementAccepted"
      >
        <view :class="['inspection-login__tick', { 'inspection-login__tick--on': agreementAccepted }]">
          <LineIcon v-if="agreementAccepted" name="check" :size="11" color="#ffffff" :stroke-width="3.2" />
        </view>
        <text class="inspection-login__agreement-text">我已阅读并同意<text class="inspection-login__link" @click.stop="openAgreement('USER_AGREEMENT')">《用户协议》</text><text class="inspection-login__link" @click.stop="openAgreement('PRIVACY_POLICY')">《隐私政策》</text><text class="inspection-login__link" @click.stop="openAgreement('CHILD_PRIVACY_RULES')">《儿童个人信息保护规则》</text></text>
      </view>

      <button
        v-if="ACCOUNT_PREVIEW_ENABLED"
        class="inspection-login__other"
        data-testid="login-switch-mode"
        @click="switchMode(mode === 'phone' ? 'password' : 'phone')"
      >
        {{ mode === 'phone' ? '账号密码登录' : '验证码登录' }}
      </button>
      <button class="inspection-login__browse" data-testid="login-browse" @click="exitLogin">先随便看看</button>
    </main>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Login (390 wide). One-tap login with the
   phone number is not built; the existing account login keeps the board's layout. */
.inspection-login { display: flex; min-height: 100vh; flex-direction: column; color: #202c42; background: #f5f7fc; }
.inspection-login__content { display: flex; width: 100%; max-width: 600px; min-height: 0; margin: 0 auto; padding: 8px 16px calc(24px + env(safe-area-inset-bottom)); flex: 1; flex-direction: column; box-sizing: border-box; }
.inspection-login__resume { display: flex; padding: 10px 12px; flex-direction: column; gap: 2px; border: 1px solid #ffd8b8; border-radius: 12px; background: #fff0e3; }
.inspection-login__resume-title { font-size: 13px; font-weight: 500; line-height: 18px; }
.inspection-login__resume-text { color: #627187; font-size: 12px; line-height: 17px; }
.inspection-login__form { display: flex; flex-direction: column; }
.inspection-login__form-title { margin: 28px 0 16px; font-size: 22px; font-weight: 700; line-height: 30px; }
.inspection-login__input { display: flex; height: 50px; padding: 0 14px; align-items: center; gap: 10px; border-radius: 12px; background: #fff; box-sizing: border-box; }
.inspection-login__input + .inspection-login__input { margin-top: 12px; }
.inspection-login__input input { min-width: 0; height: 50px; flex: 1; color: #202c42; font-size: 15px; }
.inspection-login__placeholder { color: #738096; }
.inspection-login__error { display: block; margin-top: 12px; color: #d64f67; font-size: 13px; line-height: 18px; }
.inspection-login__submit { height: 48px; margin: 24px 0 0; border: 0; border-radius: 999px; color: #fff; background: #ff6a00; box-shadow: var(--app-primary-shadow); font-size: 16px; font-weight: 500; line-height: 48px; }
.inspection-login__submit::after, .inspection-login__browse::after { border: 0; }
.inspection-login__submit[disabled] { opacity: 0.7; }
.inspection-login__agreement { display: flex; margin-top: 16px; padding: 6px 0; align-items: flex-start; gap: 8px; color: #627187; font-size: 12px; line-height: 18px; }
.inspection-login__tick { display: flex; width: 18px; height: 18px; flex: none; align-items: center; justify-content: center; border: 1.5px solid #91a0b4; border-radius: 50%; background: #fff; box-sizing: border-box; }
.inspection-login__tick--on { border: 0; background: #ff6a00; }
.inspection-login__agreement-text { min-width: 0; flex: 1; }
.inspection-login__link { color: #ba470b; }
/* Board Login, 验证码登录 frame: the theme draws this title at 17px and the fields in the page colour. */
.inspection-login--phone .inspection-login__form-title { font-size: 17px; line-height: 24px; }
.inspection-login__input--soft { background: #f5f7fc; }
.inspection-login__input--code { padding-right: 4px; gap: 8px; }
.inspection-login__prefix { flex: none; color: #202c42; font-size: 15px; }
.inspection-login__divider { width: 1px; height: 16px; flex: none; background: #dcdfe3; }
.inspection-login__code { flex: none; min-height: 44px; margin: 0; padding: 0 10px; border: 0; color: #ba470b; background: transparent; font-size: 14px; line-height: 44px; white-space: nowrap; }
.inspection-login__code::after, .inspection-login__switch::after, .inspection-login__other::after { border: 0; }
.inspection-login__code--wait, .inspection-login__code[disabled] { color: #91a0b4; background: transparent; }
.inspection-login__submit--off, .inspection-login__submit--off[disabled] { color: #fff; background: #c9d3e1; box-shadow: none; opacity: 1; }
.inspection-login__switch { align-self: center; min-height: 44px; margin: 8px auto 0; padding: 0 16px; border: 0; color: #202c42; background: transparent; font-size: 15px; line-height: 44px; }
.inspection-login__other { min-height: 40px; margin: 8px auto 0; padding: 0 16px; border: 0; color: #627187; background: transparent; font-size: 13px; line-height: 40px; }
.inspection-login__browse { min-height: 44px; margin: auto auto 0; padding: 0 16px; border: 0; color: #627187; background: transparent; font-size: 14px; line-height: 44px; }
</style>
