<script setup lang="ts">
import type { FormInstance } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef } from 'vue'

import { http } from '@/core/http'
import { newIdempotencyKey } from '@/core/http/api-client'
import { errorMessage } from '@/core/http/api-error'
import type { DirectoryCity } from '@/features/organization/organization-api'

import {
  createSignupApi,
  emptySignupForm,
  PHONE,
  SIGNUP_LIMITS,
  signupRules,
  type SignupApi,
  type SignupResult,
} from './signup-api'

const props = defineProps<{
  api?: SignupApi
}>()

const api = props.api ?? createSignupApi(http)
const formRef = ref<FormInstance>()
const form = reactive(emptySignupForm())
const rules = signupRules(form)
const cities = shallowRef<DirectoryCity[]>([])
const citiesLoading = ref(false)
const sending = ref(false)
const countdown = ref(0)
const submitting = ref(false)
const error = ref('')
const result = shallowRef<SignupResult | null>(null)
let idempotencyKey = newIdempotencyKey()
let timer: ReturnType<typeof setInterval> | null = null

const phoneValid = computed(() => PHONE.test(form.phone.trim()))

async function loadCities(): Promise<void> {
  citiesLoading.value = true
  try {
    cities.value = await api.cities()
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    citiesLoading.value = false
  }
}

function startCountdown(): void {
  countdown.value = SIGNUP_LIMITS.resendSeconds
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function sendCode(): Promise<void> {
  if (!phoneValid.value || sending.value || countdown.value > 0) return
  sending.value = true
  error.value = ''
  try {
    await api.sendCode(form.phone)
    startCountdown()
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    sending.value = false
  }
}

async function submit(): Promise<void> {
  if (!formRef.value || submitting.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  submitting.value = true
  error.value = ''
  try {
    result.value = await api.signUp(form, idempotencyKey)
  } catch (failure) {
    error.value = errorMessage(failure)
    // 登录名、手机号被占或验证码不对时改了再交，是一次新的申请。
    idempotencyKey = newIdempotencyKey()
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void loadCities()
})

onBeforeUnmount(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="signup">
    <el-card class="signup__card" shadow="never">
      <h1 class="signup__title">商家入驻申请</h1>
      <p class="signup__subtitle">
        填写机构、营业执照和联系人信息，提交后用自己设的登录名登录工作台，再上传营业执照等附件；平台审核通过后商品才能上架。
      </p>
      <el-alert v-if="error" class="signup__error" type="error" :title="error" :closable="false" show-icon />

      <el-result v-if="result" icon="success" title="申请已提交" data-testid="signup-result">
        <template #sub-title>
          <p>机构“{{ result.organizationName }}”已建好，资质待平台审核。</p>
          <p>登录名：<strong data-testid="signup-login-name">{{ result.loginName }}</strong>，口令是你刚设的。</p>
          <p>登录后先在“入驻资料”里上传营业执照（和行业资质）附件，审核通过前至少一份。</p>
        </template>
        <template #extra>
          <router-link to="/login">
            <el-button type="primary">去登录</el-button>
          </router-link>
        </template>
      </el-result>

      <el-form v-else ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <h2 class="signup__section">机构</h2>
        <el-form-item label="机构名称（对外显示的商家名）" prop="organizationName">
          <el-input v-model="form.organizationName" :maxlength="SIGNUP_LIMITS.organizationName" />
        </el-form-item>
        <el-form-item label="所在城市" prop="cityCode">
          <el-select v-model="form.cityCode" filterable :loading="citiesLoading" placeholder="输入城市名搜索"
                     style="width: 100%">
            <el-option v-for="city in cities" :key="city.code" :value="city.code"
                       :label="`${city.name}（${city.provinceName}）`" />
          </el-select>
        </el-form-item>

        <h2 class="signup__section">营业执照</h2>
        <el-form-item label="主体名称" prop="subjectName">
          <el-input v-model="form.subjectName" :maxlength="SIGNUP_LIMITS.subjectName" placeholder="营业执照上的名称" />
        </el-form-item>
        <el-form-item label="统一社会信用代码" prop="licenseNumber">
          <el-input v-model="form.licenseNumber" maxlength="18" placeholder="18 位" />
        </el-form-item>
        <el-form-item label="经营范围（可空）" prop="businessScope">
          <el-input v-model="form.businessScope" type="textarea" :rows="2" :maxlength="SIGNUP_LIMITS.businessScope"
                    show-word-limit />
        </el-form-item>

        <h2 class="signup__section">联系人与账号</h2>
        <el-form-item label="联系人姓名" prop="contactName">
          <el-input v-model="form.contactName" :maxlength="SIGNUP_LIMITS.contactName" />
        </el-form-item>
        <el-form-item label="联系人手机号" prop="phone">
          <el-input v-model="form.phone" maxlength="11" inputmode="numeric" autocomplete="tel" />
        </el-form-item>
        <el-form-item label="短信验证码" prop="smsCode">
          <div class="signup__code">
            <el-input v-model="form.smsCode" maxlength="6" inputmode="numeric" autocomplete="one-time-code" />
            <el-button :disabled="!phoneValid || countdown > 0" :loading="sending" data-testid="send-code"
                       @click="sendCode">
              {{ countdown > 0 ? `${countdown} 秒后可重发` : '发送验证码' }}
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="登录名" prop="loginName">
          <el-input v-model="form.loginName" maxlength="32" autocomplete="username"
                    placeholder="4–32 位，小写字母开头，可用数字、_ 和 -" />
        </el-form-item>
        <el-form-item label="口令" prop="password">
          <el-input v-model="form.password" type="password" :maxlength="SIGNUP_LIMITS.passwordMax" show-password
                    autocomplete="new-password" placeholder="8–64 位，至少一个字母和一个数字" />
        </el-form-item>
        <el-form-item label="再输一次口令" prop="passwordAgain">
          <el-input v-model="form.passwordAgain" type="password" :maxlength="SIGNUP_LIMITS.passwordMax" show-password
                    autocomplete="new-password" />
        </el-form-item>

        <el-button class="signup__submit" type="primary" native-type="submit" :loading="submitting">提交申请</el-button>
        <p class="signup__hint">
          已有账号？<router-link to="/login">去登录</router-link>。提交即表示你有权代表该机构申请入驻。
        </p>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.signup {
  display: flex;
  justify-content: center;
  min-height: 100vh;
  padding: 32px 16px;
  background: var(--admin-background);
}

.signup__card {
  width: 100%;
  max-width: 560px;
  border: 1px solid var(--admin-border);
  border-radius: 12px;
}

.signup__title {
  margin: 0;
  font-size: 22px;
}

.signup__subtitle {
  margin: 6px 0 20px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.signup__section {
  margin: 8px 0 12px;
  font-size: 15px;
}

.signup__error {
  margin-bottom: 12px;
}

.signup__code {
  display: flex;
  gap: 8px;
  width: 100%;
}

.signup__submit {
  width: 100%;
}

.signup__hint {
  margin: 16px 0 0;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
