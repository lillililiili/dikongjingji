<script setup lang="ts">
import { ref } from 'vue'
import { onBackPress, onLoad } from '@dcloudio/uni-app'
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import {
  completeBookingAuthenticationRecovery,
  saveBookingAuthSession,
} from '../../../application/service-flow/booking-auth-context'
import { inspectionAuthApi } from '../../../data/service-flow/uni-booking-http-client'
import { normalizeInspectionReturnUrl } from '../../../navigation/inspection-routes'

interface LoginRouteQuery {
  returnUrl?: string
}

const loginName = ref('')
const password = ref('')
const returnUrl = ref('/pages/home/index')
const isSubmitting = ref(false)
const errorMessage = ref('')

onLoad((query: LoginRouteQuery | undefined) => {
  returnUrl.value = normalizeInspectionReturnUrl(query?.returnUrl ?? '')
})

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

  isSubmitting.value = true
  errorMessage.value = ''
  try {
    const response = await inspectionAuthApi.login(
      normalizedLoginName,
      password.value,
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
  if (target === '/pages/home/index') {
    uni.reLaunch({ url: target })
    return
  }
  uni.redirectTo({
    url: target,
    fail: () => uni.reLaunch({ url: target }),
  })
}

function exitLogin(): void {
  completeBookingAuthenticationRecovery()
  uni.reLaunch({ url: '/pages/home/index' })
}
</script>

<template>
  <view class="inspection-login">
    <view class="inspection-login__header">
      <button aria-label="返回首页" @click="exitLogin">
        <UniIcons type="back" size="22" color="#25282d" />
      </button>
      <text>登录</text>
      <view />
    </view>

    <main class="inspection-login__content">
      <view class="inspection-login__brand">
        <view class="inspection-login__brand-icon">
          <UniIcons type="paperplane-filled" size="30" color="#ff6500" />
        </view>
        <text class="inspection-login__title">继续巡检服务</text>
        <text class="inspection-login__subtitle">登录后将返回刚才的服务进度</text>
      </view>

      <view class="inspection-login__form">
        <label>
          <text>用户名</text>
          <input
            v-model="loginName"
            type="text"
            maxlength="80"
            placeholder="请输入用户名"
            confirm-type="next"
          />
        </label>
        <label>
          <text>密码</text>
          <input
            v-model="password"
            type="text"
            password
            maxlength="256"
            placeholder="请输入密码"
            confirm-type="done"
            @confirm="submitLogin"
          />
        </label>
        <text v-if="errorMessage" class="inspection-login__error">{{ errorMessage }}</text>
        <button
          class="inspection-login__submit"
          :disabled="isSubmitting"
          @click="submitLogin"
        >
          {{ isSubmitting ? '正在登录…' : '登录并继续' }}
        </button>
      </view>

      <text class="inspection-login__notice">
        账号权限由平台统一分配，客户只能查看和操作自己的巡检业务。
      </text>
    </main>
  </view>
</template>

<style scoped>
.inspection-login{min-height:100vh;background:#f7f8fa;color:#25282d}
.inspection-login__header{display:grid;height:calc(44px + var(--status-bar-height));padding-top:var(--status-bar-height);grid-template-columns:44px 1fr 44px;align-items:center;background:#fff;font-size:17px;font-weight:700;text-align:center;box-sizing:border-box}
.inspection-login__header button{height:44px;min-height:44px;margin:0;padding:0;border:0;background:transparent;line-height:1}
.inspection-login__header button::after,.inspection-login__submit::after{border:0}
.inspection-login__content{max-width:480px;margin:0 auto;padding:54px 24px calc(24px + env(safe-area-inset-bottom))}
.inspection-login__brand{display:flex;align-items:center;flex-direction:column}
.inspection-login__brand-icon{display:grid;width:62px;height:62px;place-items:center;border-radius:18px;background:#fff0e6}
.inspection-login__title{margin-top:18px;font-size:24px;font-weight:700}
.inspection-login__subtitle{margin-top:8px;color:#7a8189;font-size:14px}
.inspection-login__form{margin-top:36px;padding:22px 18px;border:1px solid #eceef1;border-radius:16px;background:#fff}
.inspection-login__form label{display:block;margin-top:18px}
.inspection-login__form label:first-child{margin-top:0}
.inspection-login__form label>text{display:block;margin-bottom:8px;font-size:13px;font-weight:600}
.inspection-login__form input{height:46px;padding:0 14px;border:1px solid #dfe3e7;border-radius:10px;background:#fafbfc;font-size:15px;box-sizing:border-box}
.inspection-login__error{display:block;margin-top:14px;color:#d73928;font-size:13px}
.inspection-login__submit{height:46px;min-height:46px;margin-top:22px;border:0;border-radius:23px;color:#fff;background:linear-gradient(90deg,#ff8a00,#ff4d00);font-size:16px;font-weight:700}
.inspection-login__submit[disabled]{opacity:.62}
.inspection-login__notice{display:block;margin-top:18px;color:#8a9199;font-size:12px;line-height:1.7;text-align:center}
</style>
