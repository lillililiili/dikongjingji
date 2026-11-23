<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { errorMessage, isApiError } from '@/core/http/api-error'
import { useSessionStore } from '@/core/auth/session'
import { safeRedirect } from '@/core/navigation/guard'
import { required } from '@/core/ui/form-rules'

const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const formRef = ref<FormInstance>()
const form = reactive({ loginName: '', password: '' })
const submitting = ref(false)
const error = ref('')

const rules: FormRules = {
  loginName: [required('登录名')],
  password: [required('口令')],
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
    error.value = isApiError(failure, 'COMMON_UNAUTHORIZED') ? '登录名或口令不对' : errorMessage(failure)
    form.password = ''
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login">
    <el-card class="login__card" shadow="never">
      <h1 class="login__title">城市运营工作台</h1>
      <p class="login__subtitle">平台运营、平台客服和机构账号登录</p>
      <el-alert v-if="error" class="login__error" type="error" :title="error" :closable="false" show-icon />
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" @submit.prevent="submit">
        <el-form-item label="登录名" prop="loginName">
          <el-input v-model="form.loginName" autocomplete="username" maxlength="80" />
        </el-form-item>
        <el-form-item label="口令" prop="password">
          <el-input v-model="form.password" type="password" autocomplete="current-password" maxlength="256"
                    show-password />
        </el-form-item>
        <el-button class="login__submit" type="primary" native-type="submit" :loading="submitting">登录</el-button>
      </el-form>
      <p class="login__hint">登录后一直在用会自动续期，最长 90 天；30 天不用要重新登录。关闭浏览器标签后不保留登录状态。</p>
    </el-card>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: var(--admin-background);
}

.login__card {
  width: 380px;
  border: 1px solid var(--admin-border);
  border-radius: 12px;
}

.login__title {
  margin: 0;
  font-size: 22px;
}

.login__subtitle {
  margin: 6px 0 20px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.login__error {
  margin-bottom: 12px;
}

.login__submit {
  width: 100%;
}

.login__hint {
  margin: 16px 0 0;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
