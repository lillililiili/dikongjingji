<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'

import { ApiRequestError } from '@/shared/http/public-api-request'
import { identityAccountApi } from '../../data/identity-account-api-client'
import { digitsOnly, inputValue, isPhoneNumber, isSmsCode, newPasswordError, type PersonalPasswordStatus } from '../../domain/identity-account'
import SmsCodeField from '../shared/SmsCodeField.vue'

const props = defineProps<{ token: string | null; initialPhone?: string; setupOnly?: boolean }>()
const emit = defineEmits<{ saved: []; signedOut: []; unauthorized: [] }>()
const status = ref<PersonalPasswordStatus | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const code = ref('')
const password = ref('')
const confirmation = ref('')
const visible = ref(false)
const phone = ref(props.initialPhone ?? '')
const currentPassword = ref('')
const recovery = computed(() => props.token === null)
const changing = computed(() => !recovery.value && status.value?.hasPassword === true)
const needsCode = computed(() => recovery.value || (!changing.value && !status.value?.canSetWithoutCode))
const canEdit = computed(() => recovery.value || (status.value?.eligible && !(props.setupOnly && changing.value)))
watch(phone, () => { code.value = '' })

function report(reason: unknown): void {
  if (!recovery.value && reason instanceof ApiRequestError && reason.status === 401) {
    emit('unauthorized')
    return
  }
  error.value = reason instanceof Error ? reason.message : '操作失败，请重试'
}

async function load(): Promise<void> {
  if (loading.value || !props.token) return
  loading.value = true
  error.value = ''
  try {
    status.value = await identityAccountApi.passwordStatus(props.token)
  } catch (reason) {
    report(reason)
  } finally {
    loading.value = false
  }
}

async function sendCode(): Promise<number> {
  error.value = ''
  try {
    if (!props.token && !isPhoneNumber(phone.value)) throw new Error('请输入 11 位手机号')
    const sent = props.token
      ? await identityAccountApi.sendPasswordSetupCode(props.token)
      : await identityAccountApi.sendPasswordResetCode(phone.value)
    const message = recovery.value ? '如该号码可以找回密码，将收到验证码' : `验证码已发送至 ${sent.maskedPhone}`
    uni.showToast({ title: message, icon: 'none' })
    return sent.resendAfterSeconds
  } catch (reason) {
    report(reason)
    throw reason
  }
}

async function save(): Promise<void> {
  if (saving.value || !canEdit.value) return
  error.value = ''
  if (recovery.value && !isPhoneNumber(phone.value)) error.value = '请输入 11 位手机号'
  if (changing.value && !currentPassword.value) error.value = '请输入当前密码'
  if (!error.value) error.value = newPasswordError(password.value)
  if (!error.value && password.value !== confirmation.value) error.value = '两次输入的密码不一致'
  if (!error.value && needsCode.value && !isSmsCode(code.value)) error.value = '请输入 6 位验证码'
  if (error.value) return
  saving.value = true
  try {
    if (props.token === null) {
      await identityAccountApi.resetPassword(phone.value, code.value, password.value)
    } else if (changing.value) {
      await identityAccountApi.changePassword(props.token, currentPassword.value, password.value)
    } else {
      await identityAccountApi.setPassword(props.token, password.value, needsCode.value ? code.value : undefined)
    }
    password.value = ''
    confirmation.value = ''
    currentPassword.value = ''
    code.value = ''
    if (changing.value || recovery.value) {
      emit('signedOut')
    } else {
      uni.showToast({ title: '登录密码已设置', icon: 'none' })
      emit('saved')
    }
  } catch (reason) {
    // The short-lived verification obtained at SMS login may expire while the user is typing.
    if (reason instanceof ApiRequestError &&
        ['PASSWORD_CODE_REQUIRED', 'PASSWORD_ALREADY_SET', 'PASSWORD_NOT_ELIGIBLE'].includes(reason.code ?? '')) {
      await load()
    }
    report(reason)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <view class="password-form" data-testid="password-form">
    <text v-if="loading" class="password-form__hint">正在读取密码设置…</text>
    <template v-else-if="canEdit">
      <text v-if="!recovery" class="password-form__heading">{{ changing ? '修改登录密码' : '设置登录密码' }}</text>
      <text class="password-form__hint">{{ recovery || changing ? '保存新密码后，所有设备都需要重新登录。' : '设置后可用绑定的手机号和密码登录。' }}</text>
      <view class="password-form__card">
        <template v-if="recovery">
          <text class="password-form__label">绑定的手机号</text>
          <view class="password-form__field">
            <input :value="phone" type="number" maxlength="11" aria-label="绑定的手机号" placeholder="请输入手机号" data-testid="password-reset-phone" @input="phone = digitsOnly(inputValue($event), 11)" @blur="phone = digitsOnly(inputValue($event), 11)" />
          </view>
        </template>
        <template v-if="needsCode">
          <text class="password-form__label">{{ recovery ? '找回验证码' : '验证当前绑定的手机号' }}</text>
          <SmsCodeField :key="phone" v-model="code" :label="recovery ? '找回密码验证码' : '设置密码验证码'" test-id="password" :send="sendCode" />
        </template>
        <template v-if="changing">
          <text class="password-form__label">当前密码</text>
          <view class="password-form__field">
            <input v-model="currentPassword" password maxlength="128" aria-label="当前密码" placeholder="输入当前密码" data-testid="password-current" @blur="currentPassword = inputValue($event)" />
          </view>
        </template>
        <text class="password-form__label">新密码</text>
        <view class="password-form__field">
          <input v-model="password" :password="!visible" maxlength="128" aria-label="新密码" placeholder="输入新密码" data-testid="password-new" @blur="password = inputValue($event)" />
          <button class="password-form__toggle" :aria-label="visible ? '隐藏密码' : '显示密码'" @click="visible = !visible">{{ visible ? '隐藏' : '显示' }}</button>
        </view>
        <text class="password-form__hint">8–64 位，包含字母和数字，不能含空格</text>
        <text class="password-form__label">确认新密码</text>
        <view class="password-form__field">
          <input v-model="confirmation" :password="!visible" maxlength="128" aria-label="确认新密码" placeholder="再次输入新密码" data-testid="password-confirm" @blur="confirmation = inputValue($event)" @confirm="save" />
        </view>
      </view>
    </template>
    <text v-else-if="status" class="password-form__hint">{{ status.eligible ? '此账号已设置登录密码' : '当前账号不支持个人密码设置' }}</text>
    <text v-if="error" class="password-form__error" role="alert" data-testid="password-error">{{ error }}</text>
    <button v-if="!recovery && !loading && !status" class="password-form__secondary" @click="load">重新加载</button>
    <button v-if="canEdit" class="password-form__primary" data-testid="password-save" :disabled="saving || loading" @click="save">{{ saving ? '保存中…' : '保存密码' }}</button>
  </view>
</template>

<style scoped>
.password-form { display: flex; flex-direction: column; gap: 10px; color: #202c42; }
.password-form__heading { font-size: 21px; font-weight: 700; line-height: 29px; }
.password-form__hint { color: #627187; font-size: 14px; line-height: 21px; }
.password-form__card { display: flex; flex-direction: column; gap: 8px; padding: 16px; border-radius: 14px; background: #fff; box-shadow: var(--app-card-shadow); }
.password-form__label { margin-top: 8px; font-size: 15px; font-weight: 500; line-height: 22px; }
.password-form__label:first-child { margin-top: 0; }
.password-form__field { display: flex; align-items: center; gap: 8px; padding: 0 12px; border-radius: 10px; background: #f5f7fc; }
.password-form__field input { flex: 1; min-width: 0; height: 48px; font-size: 15px; }
.password-form button { margin: 0; border: 0; }
.password-form button::after { border: 0; }
.password-form__toggle, .password-form__secondary { min-width: 44px; padding: 0 4px; color: #536178; background: transparent; font-size: 14px; line-height: 44px; }
.password-form__error { color: #aa344a; font-size: 14px; line-height: 20px; }
.password-form__primary { min-height: 48px; border-radius: 24px; color: white; background: #ff6a00; font-weight: 500; line-height: 48px; }
.password-form__primary[disabled] { color: white; background: #91a0b4; }
</style>
