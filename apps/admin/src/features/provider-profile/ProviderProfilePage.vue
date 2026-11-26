<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref, shallowRef } from 'vue'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import RowListEditor from '@/core/ops/RowListEditor.vue'
import { confirmAction, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'
import { beijingToday } from '@/features/inspection-workbench/inspection-rules'

import {
  buildProfileRequest,
  createProviderProfileApi,
  initialProfileForm,
  isNoProviderProfile,
  PROFILE_FIELDS,
  PROFILE_LIMITS,
  profileValue,
  REVIEW_STATUS,
  type ProfileForm,
  type ProviderProfileReview,
} from './provider-profile-api'

const api = createProviderProfileApi(http)
const review = shallowRef<ProviderProfileReview | null>(null)
const loading = ref(false)
const error = ref('')
const noProfile = ref(false)
const busy = ref(false)
const problem = ref('')
const form = reactive<ProfileForm>(initialProfileForm(null))
const fieldRows = [...PROFILE_FIELDS]
const deliverableFields = [{ key: 'label', label: '可交付', max: PROFILE_LIMITS.deliverable, placeholder: '如 巡检报告' }] as const

function show(next: ProviderProfileReview): void {
  review.value = next
  Object.assign(form, initialProfileForm(next))
  problem.value = ''
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  noProfile.value = false
  try {
    show(await api.get())
  } catch (failure) {
    review.value = null
    noProfile.value = isNoProviderProfile(failure)
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function disabledBeforeToday(date: Date): boolean {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return date.getTime() < today.getTime()
}

async function submit(): Promise<void> {
  const current = review.value
  if (!current || busy.value) return
  const built = buildProfileRequest(form, current.version, beijingToday())
  if (!built.ok) {
    problem.value = built.message
    return
  }
  problem.value = ''
  const confirmed = await confirmAction({
    title: '确认提交审核',
    message: current.status === 'PENDING'
      ? '会替换正在审核的那一份。审核通过前，App 服务商主页仍显示上一份通过的资料。'
      : '提交后由平台运营审核。审核通过前，App 服务商主页仍显示上一份通过的资料。',
    confirmText: '提交审核',
  })
  if (!confirmed) return
  busy.value = true
  try {
    show(await api.submit(built.request))
    ElMessage.success('已提交，等平台审核')
  } catch (failure) {
    if (isNoProviderProfile(failure)) noProfile.value = true
    showError(failure)
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    title="服务商资料"
    description="App 服务商主页上的经营主体、服务方式、授权有效期、可交付清单和本地团队标签。提交后由平台审核，主页只显示审核通过的一份。"
  >
    <template #actions>
      <el-button :loading="loading" @click="load">刷新</el-button>
    </template>

    <div v-loading="loading">
      <el-result v-if="noProfile" icon="info" title="这家机构没有服务商资料"
                 sub-title="只有在平台登记为服务商的机构才有服务商主页。需要开通请联系平台运营。" />
      <el-result v-else-if="error && !review" icon="error" :title="error">
        <template #extra><el-button type="primary" @click="load">重新加载</el-button></template>
      </el-result>

      <template v-if="review && !noProfile">
        <div class="status">
          <el-tag :type="REVIEW_STATUS[review.status].tag" size="large">{{ REVIEW_STATUS[review.status].label }}</el-tag>
          <span>{{ review.providerName }}</span>
          <span v-if="review.submittedAt">最近提交 {{ formatDateTime(review.submittedAt) }}</span>
          <span v-if="review.reviewedAt">最近审核 {{ formatDateTime(review.reviewedAt) }}</span>
        </div>
        <el-alert v-if="review.status === 'REJECTED'" type="error" :closable="false" show-icon class="alert"
                  :title="`被驳回：${review.rejectReason ?? '（没写原因）'}`" description="改好后重新提交，会再进入待审核。" />
        <el-alert v-if="review.status === 'PENDING'" type="warning" :closable="false" show-icon class="alert"
                  title="最近提交的一份正在审核；审核通过前 App 主页仍显示“正在显示的”那一份。" />

        <el-table :data="fieldRows" row-key="key" border size="small" class="compare">
          <el-table-column prop="label" label="" width="140" />
          <el-table-column label="正在显示的（已通过）" min-width="240">
            <template #default="{ row }">{{ profileValue(review.published, row.key) }}</template>
          </el-table-column>
          <el-table-column label="最近提交的" min-width="240">
            <template #default="{ row }">{{ profileValue(review.submitted, row.key) }}</template>
          </el-table-column>
        </el-table>

        <h3 class="title">{{ review.status === 'NONE' ? '填写资料' : '修改并重新提交' }}</h3>
        <el-form label-width="110px" class="form" @submit.prevent>
          <el-form-item label="经营主体" required>
            <el-input v-model="form.legalEntity" :maxlength="PROFILE_LIMITS.legalEntity" show-word-limit
                      placeholder="营业执照上的名称" />
          </el-form-item>
          <el-form-item label="服务方式" required>
            <el-input v-model="form.serviceModes" :maxlength="PROFILE_LIMITS.serviceModes" show-word-limit
                      placeholder="如 无人机巡检 · 机器狗夜巡" />
          </el-form-item>
          <el-form-item label="授权有效期至">
            <el-date-picker v-model="form.authorizationUntil" type="date" value-format="YYYY-MM-DD" placeholder="不填"
                            :disabled-date="disabledBeforeToday" />
            <span class="hint">不早于今天；不填时主页不显示这一行</span>
          </el-form-item>
          <el-form-item label="可交付">
            <RowListEditor
              :model-value="form.deliverables"
              :fields="deliverableFields"
              :max="PROFILE_LIMITS.deliverables"
              :new-row="() => ({ label: '' })"
              add-label="加一项"
              @update:model-value="(value: Record<string, string>[]) => (form.deliverables = value as Array<{ label: string }>)"
            />
            <span class="hint">最多 {{ PROFILE_LIMITS.deliverables }} 项，每项最多 {{ PROFILE_LIMITS.deliverable }} 个字，不能重复</span>
          </el-form-item>
          <el-form-item label="本地团队标签">
            <el-input v-model="form.localTeamLabel" :maxlength="PROFILE_LIMITS.localTeamLabel" show-word-limit
                      placeholder="如 东营本地团队（选填）" style="width: 280px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="busy" @click="submit">提交审核</el-button>
          </el-form-item>
        </el-form>
        <el-alert v-if="problem" type="error" :title="problem" :closable="false" show-icon />
      </template>
    </div>
  </PageSection>
</template>

<style scoped>
.status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  color: var(--admin-text-secondary);
}

.alert {
  margin-bottom: 12px;
}

.title {
  margin: 20px 0 10px;
  font-size: 15px;
}

.form {
  max-width: 720px;
}

.hint {
  display: block;
  width: 100%;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
