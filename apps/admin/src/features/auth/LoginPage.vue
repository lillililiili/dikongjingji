<script setup lang="ts">
import { Lock, User } from '@element-plus/icons-vue'
import type { FormInstance, FormRules } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import loginBg from '@/assets/images/login-bg.jpg'
import { useSessionStore } from '@/core/auth/session'
import { errorMessage, isApiError } from '@/core/http/api-error'
import { safeRedirect } from '@/core/navigation/guard'
import { required } from '@/core/ui/form-rules'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const form = reactive({ loginName: '', password: '' })
const submitting = ref(false)
const error = ref('')
const restoring = ref(false)

async function retrySession(): Promise<void> {
  if (restoring.value) return
  restoring.value = true
  try {
    if (await session.ensureAccount()) await router.replace(safeRedirect(route.query.redirect))
  } finally {
    restoring.value = false
  }
}

const rules: FormRules = {
  loginName: [required('用户名')],
  password: [required('密码')],
}

async function submit(): Promise<void> {
  if (!formRef.value || submitting.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  submitting.value = true
  error.value = ''
  try {
    await session.login(form.loginName.trim(), form.password)
    await router.replace(safeRedirect(route.query.redirect))
  } catch (failure) {
    if (!session.restoreError) error.value = isApiError(failure, 'COMMON_UNAUTHORIZED') ? '用户名或密码错误' : errorMessage(failure)
    form.password = ''
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login-layout">
    <aside class="login-visual" :style="{ backgroundImage: `url(${loginBg})` }">
      <div class="login-visual__mask" />
      <div class="login-visual__brand">
        <span class="login-visual__logo" aria-hidden="true">
          <svg viewBox="0 0 28 28" fill="none">
            <rect width="28" height="28" rx="5" fill="#1677ff" />
            <path d="M7 15.2L20.5 8.5L16.2 20.5L13 15.8L7 15.2Z" fill="#ffffff" />
          </svg>
        </span>
        <span>飞享 · 低空经济与机器人综合服务平台</span>
      </div>
      <div class="login-visual__caption">
        <h2>城市运营管理与商家工作台</h2>
        <p>面向平台运营、客服与入驻机构的统一业务后台</p>
      </div>
    </aside>

    <main class="login-workspace">
      <div class="login-box">
        <h1 class="login__title">城市运营工作台</h1>
        <p class="login__subtitle">平台运营、平台客服和机构账号登录</p>

        <el-alert v-if="error && !session.restoreError && !restoring" class="login__error" type="error" :title="error" :closable="false" show-icon />

        <div v-if="session.restoreError || restoring" role="status">
          <el-alert title="暂时无法读取账号资料" :description="session.restoreError" type="warning" :closable="false" />
          <el-button type="primary" :loading="restoring" @click="retrySession">重新加载</el-button>
          <el-button :disabled="restoring" @click="session.logout()">换账号登录</el-button>
        </div>
        <el-form
          v-else
          ref="formRef"
          class="login-form"
          :model="form"
          :rules="rules"
          label-position="top"
          size="large"
          @submit.prevent="submit"
        >
          <el-form-item label="用户名" prop="loginName">
            <el-input
              v-model="form.loginName"
              autocomplete="username"
              maxlength="80"
              placeholder="请输入用户名"
              :prefix-icon="User"
            />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              autocomplete="current-password"
              maxlength="256"
              placeholder="请输入密码"
              :prefix-icon="Lock"
              show-password
            />
          </el-form-item>
          <el-button class="login__submit" type="primary" native-type="submit" :loading="submitting">登录</el-button>
        </el-form>

        <div class="login__links">
          <span>商家还没有账号？<router-link to="/signup" data-testid="signup-link">申请入驻</router-link>，审核通过后即可上架商品。</span>
        </div>
      </div>

      <footer class="login-copyright">
        飞享综合服务平台 · 运营管理后台
      </footer>
    </main>
  </div>
</template>

<style scoped>
.login-layout {
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  min-height: 100vh;
  background: #ffffff;
}

.login-visual {
  position: relative;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 36px 48px 44px;
  background-color: #001529;
  background-position: center;
  background-size: cover;
  color: #ffffff;
}

.login-visual__mask {
  position: absolute;
  inset: 0;
  background: linear-gradient(180deg, rgba(0, 21, 41, 0.55) 0%, rgba(0, 21, 41, 0.35) 50%, rgba(0, 21, 41, 0.78) 100%);
  pointer-events: none;
}

.login-visual__brand,
.login-visual__caption {
  position: relative;
  z-index: 1;
}

.login-visual__brand {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.login-visual__logo {
  display: inline-flex;
  width: 28px;
  height: 28px;
}

.login-visual__logo svg {
  width: 100%;
  height: 100%;
}

.login-visual__caption {
  max-width: 520px;
}

.login-visual__caption h2 {
  margin: 0 0 10px;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.login-visual__caption p {
  margin: 0;
  font-size: 14px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.82);
}

.login-workspace {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  padding: 40px 32px;
  background: #ffffff;
}

.login-box {
  width: 100%;
  max-width: 360px;
}

.login__title {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  color: #1f2329;
}

.login__subtitle {
  margin: 8px 0 28px;
  font-size: 13.5px;
  color: #8c8c8c;
}

.login__error {
  margin-bottom: 18px;
}

.login-form :deep(.el-form-item__label) {
  padding-bottom: 6px;
  font-size: 13.5px;
  font-weight: 500;
  color: #262626;
}

.login__submit {
  width: 100%;
  height: 40px;
  margin-top: 8px;
  font-size: 15px;
  font-weight: 500;
}

.login__links {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid #f0f0f0;
  font-size: 13px;
  color: #595959;
}

.login__links a {
  color: var(--el-color-primary);
  font-weight: 500;
  text-decoration: none;
}

.login__links a:hover {
  text-decoration: underline;
}

.login-copyright {
  position: absolute;
  bottom: 20px;
  font-size: 12px;
  color: #8c8c8c;
}

@media (max-width: 900px) {
  .login-layout {
    grid-template-columns: 1fr;
  }

  .login-visual {
    display: none;
  }
}
</style>
