<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import type { CascaderOption, CascaderProps, FormInstance } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref, shallowRef, watch } from 'vue'

import { http } from '@/core/http'
import { newIdempotencyKey } from '@/core/http/api-client'
import { ApiRequestError, errorMessage, isApiError } from '@/core/http/api-error'

import {
  createSignupApi,
  emptySignupForm,
  PHONE,
  SIGNUP_LIMITS,
  signupRequestBody,
  signupRules,
  toCityCascaderOptions,
  type SignupApi,
  type SignupResult,
} from './signup-api'

const props = defineProps<{
  api?: SignupApi
}>()

const cityCascaderProps: CascaderProps = {
  emitPath: false,
  expandTrigger: 'click',
}

const api = props.api ?? createSignupApi(http)
const formRef = ref<FormInstance>()
const form = reactive(emptySignupForm())
const rules = signupRules(form)
const provinceOptions = shallowRef<CascaderOption[]>([])
const citiesLoading = ref(false)
const sending = ref(false)
const countdown = ref(0)
const submitting = ref(false)
const error = ref('')
const result = shallowRef<SignupResult | null>(null)
let idempotencyKey: string | undefined
let timer: ReturnType<typeof setInterval> | null = null

const phoneValid = computed(() => PHONE.test(form.phone.trim()))
const cityModel = computed<string | undefined>({
  get: () => form.cityCode || undefined,
  set: (value) => {
    form.cityCode = typeof value === 'string' ? value : ''
  },
})

watch(() => Object.values(signupRequestBody(form)), (current, previous) => {
  if (current.some((value, i) => value !== previous[i])) idempotencyKey = undefined
})

async function loadCities(): Promise<void> {
  citiesLoading.value = true
  try {
    provinceOptions.value = toCityCascaderOptions(await api.provinces())
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
  submitting.value = true
  try {
    if (!(await formRef.value.validate().catch(() => false))) return
    error.value = ''
    idempotencyKey ??= newIdempotencyKey()
    result.value = await api.signUp(form, idempotencyKey)
  } catch (failure) {
    error.value = isApiError(failure, 'OPS_LOGIN_NAME_TAKEN') ? '用户名已被占用，换一个试试' : errorMessage(failure)
    if (failure instanceof ApiRequestError && [400, 409, 422, 429].includes(failure.status)) {
      idempotencyKey = undefined
    }
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
  <div class="signup-page">
    <header class="signup-nav">
      <div class="signup-nav__inner">
        <div class="signup-nav__brand">
          <span class="signup-nav__logo" aria-hidden="true">
            <svg viewBox="0 0 28 28" fill="none">
              <rect width="28" height="28" rx="5" fill="var(--el-color-primary)" />
              <path d="M7 15.2L20.5 8.5L16.2 20.5L13 15.8L7 15.2Z" fill="#ffffff" />
            </svg>
          </span>
          <strong>城市运营工作台 · 商家入驻申请</strong>
        </div>
        <router-link to="/login" class="signup-nav__back">
          <el-icon><ArrowLeft /></el-icon>
          <span>返回登录</span>
        </router-link>
      </div>
    </header>

    <div class="signup-main">
      <div class="signup-steps-bar">
        <el-steps :active="result ? 1 : 0" finish-status="success" simple>
          <el-step title="1. 提交主体与账号" />
          <el-step title="2. 登录上传证照附件" />
          <el-step title="3. 平台审核通过后经营" />
        </el-steps>
      </div>

      <div class="signup-grid">
        <div class="signup__card">
          <div class="signup__head">
            <h1 class="signup__title">商家入驻申请</h1>
            <p class="signup__subtitle">
              填写机构、营业执照和联系人信息，提交后用自己设的用户名登录工作台，再上传营业执照等附件；平台审核通过后商品才能上架。
            </p>
          </div>

          <el-alert v-if="error" class="signup__error" type="error" :title="error" :closable="false" show-icon />

          <el-result v-if="result" icon="success" title="申请已提交" data-testid="signup-result">
            <template #sub-title>
              <div class="signup-success-box">
                <p>机构“<strong>{{ result.organizationName }}</strong>”已建好，资质待平台审核。</p>
                <p>用户名：<strong data-testid="signup-login-name">{{ result.loginName }}</strong>，密码是你刚设的。</p>
                <p>登录后先在“入驻资料”里上传营业执照（和行业资质）附件，审核通过前至少一份。</p>
              </div>
            </template>
            <template #extra>
              <router-link to="/login">
                <el-button type="primary">去登录</el-button>
              </router-link>
            </template>
          </el-result>

          <el-form v-else ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
            <h2 class="signup__section">一、机构基本信息</h2>
            <div class="form-row">
              <el-form-item label="机构名称（对外显示的商家名）" prop="organizationName">
                <el-input v-model="form.organizationName" :maxlength="SIGNUP_LIMITS.organizationName" />
              </el-form-item>
              <el-form-item label="所在城市" prop="cityCode" data-testid="signup-city">
                <el-cascader
                  v-model="cityModel"
                  class="signup-city-cascader"
                  :options="provinceOptions"
                  :props="cityCascaderProps"
                  :disabled="citiesLoading"
                  filterable
                  clearable
                  :placeholder="citiesLoading ? '加载省市中…' : '请选择省份 / 城市'"
                  style="width: 100%"
                />
              </el-form-item>
            </div>

            <h2 class="signup__section">二、营业执照信息</h2>
            <div class="form-row">
              <el-form-item label="主体名称" prop="subjectName">
                <el-input
                  v-model="form.subjectName"
                  :maxlength="SIGNUP_LIMITS.subjectName"
                  placeholder="营业执照上的名称"
                />
              </el-form-item>
              <el-form-item label="统一社会信用代码" prop="licenseNumber">
                <el-input v-model="form.licenseNumber" maxlength="18" placeholder="18 位信用代码" />
              </el-form-item>
            </div>
            <el-form-item label="经营范围（可空）" prop="businessScope">
              <el-input
                v-model="form.businessScope"
                type="textarea"
                :rows="2"
                :maxlength="SIGNUP_LIMITS.businessScope"
                show-word-limit
              />
            </el-form-item>

            <h2 class="signup__section">三、联系人与工作台账号</h2>
            <div class="form-row">
              <el-form-item label="联系人姓名" prop="contactName">
                <el-input v-model="form.contactName" :maxlength="SIGNUP_LIMITS.contactName" />
              </el-form-item>
              <el-form-item label="联系人手机号" prop="phone">
                <el-input v-model="form.phone" maxlength="11" inputmode="numeric" autocomplete="tel" />
              </el-form-item>
            </div>

            <div class="form-row">
              <el-form-item label="短信验证码" prop="smsCode">
                <div class="signup__code">
                  <el-input v-model="form.smsCode" maxlength="6" inputmode="numeric" autocomplete="one-time-code" />
                  <el-button
                    :disabled="!phoneValid || countdown > 0"
                    :loading="sending"
                    data-testid="send-code"
                    @click="sendCode"
                  >
                    {{ countdown > 0 ? `${countdown} 秒后可重发` : '发送验证码' }}
                  </el-button>
                </div>
              </el-form-item>

              <el-form-item label="用户名" prop="loginName">
                <el-input
                  v-model="form.loginName"
                  maxlength="32"
                  autocomplete="username"
                  placeholder="4–32 位，小写字母开头，可用数字、_ 和 -"
                />
              </el-form-item>
            </div>

            <div class="form-row">
              <el-form-item label="密码" prop="password">
                <el-input
                  v-model="form.password"
                  type="password"
                  :maxlength="SIGNUP_LIMITS.passwordMax"
                  show-password
                  autocomplete="new-password"
                  placeholder="8–64 位，至少一个字母和一个数字"
                />
              </el-form-item>
              <el-form-item label="再输一次密码" prop="passwordAgain">
                <el-input
                  v-model="form.passwordAgain"
                  type="password"
                  :maxlength="SIGNUP_LIMITS.passwordMax"
                  show-password
                  autocomplete="new-password"
                />
              </el-form-item>
            </div>

            <div class="signup__footer">
              <el-button class="signup__submit" type="primary" size="large" native-type="submit" :loading="submitting">
                提交申请
              </el-button>
              <p class="signup__hint">
                已有账号？<router-link to="/login">去登录</router-link>。提交即表示你有权代表该机构申请入驻。
              </p>
            </div>
          </el-form>
        </div>

        <aside class="signup-notice">
          <h3>入驻须知</h3>
          <ol>
            <li>提交申请后将立即生成机构工作台账号，请使用刚设置的用户名登录。</li>
            <li>登录后请前往「入驻资料」上传营业执照扫描件（支持 PDF、JPG、PNG，不超过 10 MB）。</li>
            <li>审核期间可先行建立商品草稿与规格；资质审核通过后方可正式上架。</li>
            <li>标价商品启用售卖前，需由平台运营登记贵机构的微信支付二级商户号，货款直接进入本机构商户账户。</li>
          </ol>
        </aside>
      </div>
    </div>
  </div>
</template>

<style scoped>
.signup-page {
  min-height: 100vh;
  background: var(--admin-background);
}

.signup-nav {
  height: 52px;
  border-bottom: 1px solid var(--admin-border);
  background: #ffffff;
}

.signup-nav__inner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  max-width: 1040px;
  height: 100%;
  margin: 0 auto;
  padding: 0 20px;
}

.signup-nav__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 15px;
  color: #1f2329;
}

.signup-nav__logo {
  display: inline-flex;
  width: 24px;
  height: 24px;
}

.signup-nav__logo svg {
  width: 100%;
  height: 100%;
}

.signup-nav__back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--el-color-primary);
  font-size: 13.5px;
  text-decoration: none;
}

.signup-nav__back:hover {
  text-decoration: underline;
}

.signup-main {
  max-width: 1040px;
  margin: 0 auto;
  padding: 20px 20px 40px;
}

.signup-steps-bar {
  margin-bottom: 16px;
  border: 1px solid var(--admin-border);
  border-radius: 4px;
  background: #ffffff;
  overflow: hidden;
}

.signup-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 16px;
  align-items: start;
}

.signup__card {
  padding: 24px 28px;
  border: 1px solid var(--admin-border);
  border-radius: 4px;
  background: #ffffff;
}

.signup__head {
  padding-bottom: 16px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--admin-border-subtle);
}

.signup__title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  color: #1f2329;
}

.signup__subtitle {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.6;
  color: #8c8c8c;
}

.signup__section {
  margin: 8px 0 14px;
  padding-left: 8px;
  border-left: 3px solid var(--el-color-primary);
  font-size: 14px;
  font-weight: 600;
  line-height: 1.2;
  color: #1f2329;
}

.form-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 0 18px;
}

.signup__error {
  margin-bottom: 16px;
}

.signup__code {
  display: flex;
  gap: 8px;
  width: 100%;
}

.signup__footer {
  margin-top: 8px;
  padding-top: 16px;
  border-top: 1px solid var(--admin-border-subtle);
}

.signup__submit {
  width: 180px;
}

.signup__hint {
  margin: 12px 0 0;
  font-size: 12.5px;
  color: #8c8c8c;
}

.signup__hint a {
  color: var(--el-color-primary);
  text-decoration: none;
}

.signup__hint a:hover {
  text-decoration: underline;
}

.signup-success-box {
  max-width: 440px;
  margin: 0 auto;
  padding: 14px 18px;
  border: 1px solid var(--admin-border);
  border-radius: 4px;
  background: #fafafa;
  text-align: left;
  line-height: 1.65;
}

.signup-success-box p {
  margin: 6px 0;
}

.signup-notice {
  padding: 18px 20px;
  border: 1px solid var(--admin-border);
  border-radius: 4px;
  background: #ffffff;
}

.signup-notice h3 {
  margin: 0 0 12px;
  font-size: 14.5px;
  font-weight: 600;
  color: #1f2329;
}

.signup-notice ol {
  margin: 0;
  padding-left: 18px;
  font-size: 12.5px;
  line-height: 1.75;
  color: #595959;
}

.signup-notice li + li {
  margin-top: 8px;
}

@media (max-width: 900px) {
  .signup-grid,
  .form-row {
    grid-template-columns: 1fr;
  }

  .signup__submit {
    width: 100%;
  }
}
</style>
