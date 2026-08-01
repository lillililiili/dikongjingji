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
      <text />
      <view />
    </view>

    <main class="inspection-login__content">
      <view class="inspection-login__skyline">
        <view class="inspection-login__headline">
          <text class="inspection-login__eyebrow">飞享低空服务平台</text>
          <text class="inspection-login__title">欢迎回来</text>
          <text class="inspection-login__subtitle">登录后继续您的巡检服务</text>
        </view>
        <image
          class="inspection-login__robot"
          src="/static/images/inspection/platform/robot-avatar.png"
          mode="aspectFit"
        />
      </view>

      <view class="inspection-login__form">
        <text class="inspection-login__form-title">账号登录</text>
        <label>
          <text>用户名</text>
          <view class="inspection-login__input">
            <UniIcons type="person" size="20" color="#8b929b" />
            <input
              v-model="loginName"
              type="text"
              maxlength="80"
              placeholder="请输入用户名"
              confirm-type="next"
            />
          </view>
        </label>
        <label>
          <text>密码</text>
          <view class="inspection-login__input">
            <UniIcons type="locked" size="20" color="#8b929b" />
            <input
              v-model="password"
              type="text"
              password
              maxlength="256"
              placeholder="请输入密码"
              confirm-type="done"
              @confirm="submitLogin"
            />
          </view>
        </label>
        <text v-if="errorMessage" class="inspection-login__error">{{ errorMessage }}</text>
        <button
          class="inspection-login__submit"
          :disabled="isSubmitting"
          @click="submitLogin"
        >
          {{ isSubmitting ? '正在登录…' : '登录' }}
        </button>
        <view class="inspection-login__secure">
          <UniIcons type="shield-filled" size="16" color="#20b55a" />
          <text>账号信息已加密传输</text>
        </view>
      </view>

      <text class="inspection-login__notice">
        当前仅支持平台已开通账号，登录后只能查看本人巡检业务。
      </text>
    </main>
  </view>
</template>

<style scoped>
.inspection-login{position:relative;min-height:100vh;overflow:hidden;background:linear-gradient(180deg,#cfe8ff 0,#eef7ff 42%,#f8fafc 72%);color:#171b22}
.inspection-login::before{position:absolute;top:76px;right:-90px;width:280px;height:280px;border-radius:50%;background:rgba(255,255,255,.55);filter:blur(4px);content:''}
.inspection-login__header{position:relative;z-index:2;display:grid;height:calc(44px + var(--status-bar-height));padding-top:var(--status-bar-height);grid-template-columns:44px 1fr 44px;align-items:center;background:transparent;box-sizing:border-box}
.inspection-login__header button{height:44px;min-height:44px;margin:0;padding:0;border:0;background:transparent;line-height:1}
.inspection-login__header button::after,.inspection-login__submit::after{border:0}
.inspection-login__content{position:relative;z-index:1;max-width:480px;margin:0 auto;padding:18px 20px calc(28px + env(safe-area-inset-bottom))}
.inspection-login__skyline{position:relative;height:230px;border-radius:28px;background:linear-gradient(145deg,rgba(255,255,255,.72),rgba(255,255,255,.12));box-shadow:0 20px 55px rgba(65,128,180,.12)}
.inspection-login__headline{position:absolute;z-index:2;top:34px;left:24px;display:flex;align-items:flex-start;flex-direction:column}
.inspection-login__eyebrow{color:#2274d9;font-size:13px;font-weight:600;letter-spacing:1px}
.inspection-login__title{margin-top:9px;font-size:34px;font-weight:800;letter-spacing:1px}
.inspection-login__subtitle{margin-top:8px;color:#566b80;font-size:15px}
.inspection-login__robot{position:absolute;right:-2px;bottom:0;width:190px;height:160px}
.inspection-login__form{position:relative;margin-top:-16px;padding:26px 22px 22px;border:1px solid rgba(255,255,255,.9);border-radius:24px;background:rgba(255,255,255,.96);box-shadow:0 16px 42px rgba(39,79,112,.14)}
.inspection-login__form-title{display:block;margin-bottom:22px;font-size:22px;font-weight:800}
.inspection-login__form label{display:block;margin-top:18px}
.inspection-login__form label:first-child{margin-top:0}
.inspection-login__form label>text{display:block;margin-bottom:9px;font-size:14px;font-weight:600}
.inspection-login__input{display:flex;height:52px;padding:0 15px;align-items:center;gap:10px;border:1px solid #dde4eb;border-radius:14px;background:#f8fafc;box-sizing:border-box}
.inspection-login__form input{width:100%;height:50px;font-size:15px}
.inspection-login__error{display:block;margin-top:14px;color:#d73928;font-size:13px}
.inspection-login__submit{height:52px;min-height:52px;margin-top:24px;border:0;border-radius:16px;color:#fff;background:linear-gradient(90deg,#ff9518,#ff5700);box-shadow:0 10px 24px rgba(255,99,0,.22);font-size:17px;font-weight:700}
.inspection-login__submit[disabled]{opacity:.62}
.inspection-login__secure{display:flex;margin-top:15px;align-items:center;justify-content:center;gap:6px;color:#75808a;font-size:12px}
.inspection-login__notice{display:block;margin:18px 16px 0;color:#75808a;font-size:12px;line-height:1.7;text-align:center}
</style>
