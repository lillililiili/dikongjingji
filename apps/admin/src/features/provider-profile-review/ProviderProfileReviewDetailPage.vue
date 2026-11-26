<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import ChangeLogTable from '@/core/ops/ChangeLogTable.vue'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'
import { PROFILE_FIELDS, profileValue } from '@/features/provider-profile/provider-profile-api'

import {
  canReview,
  CHANGE_ACTION_LABELS,
  CHANGE_SUBJECT_LABELS,
  createProviderProfileReviewApi,
  REJECT_REASON_MAX,
  rejectReasonError,
  reviewStatus,
  type OpsProviderProfile,
  type ReviewDecision,
} from './provider-profile-review-api'

const route = useRoute()
const router = useRouter()
const api = createProviderProfileReviewApi(http)
const profile = shallowRef<OpsProviderProfile | null>(null)
const loading = ref(false)
const error = ref('')
const busy = ref(false)
const rejecting = ref(false)
const reason = ref('')
const reasonProblem = ref('')

const providerId = computed(() => String(route.params.providerId ?? ''))
const rows = computed(() => PROFILE_FIELDS.map((field) => {
  const published = profileValue(profile.value?.published ?? null, field.key)
  const submitted = profileValue(profile.value?.submitted ?? null, field.key)
  return { key: field.key, label: field.label, published, submitted, changed: published !== submitted }
}))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    profile.value = await api.detail(providerId.value)
  } catch (failure) {
    profile.value = null
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function review(decision: ReviewDecision): Promise<void> {
  const current = profile.value
  if (!current || busy.value) return
  if (decision === 'REJECT') {
    const problem = rejectReasonError(reason.value)
    reasonProblem.value = problem ?? ''
    if (problem) return
  }
  const confirmed = await confirmAction(decision === 'APPROVE'
    ? {
        title: '确认审核通过',
        message: `通过后 App 上“${current.providerName}”的服务商主页马上显示待审核的这一份。`,
        confirmText: '审核通过',
      }
    : {
        title: '确认驳回',
        message: '驳回后 App 主页仍显示上一份通过的资料；服务商在工作台能看到原因，改好后可以重新提交。',
        confirmText: '驳回',
        danger: true,
      })
  if (!confirmed) return
  busy.value = true
  try {
    profile.value = await api.review(current.providerId, decision, reason.value, current.version)
    rejecting.value = false
    reason.value = ''
    notifyRecorded(decision === 'APPROVE' ? '审核通过' : '驳回', '改动记录')
  } catch (failure) {
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
    :title="profile ? `服务商资料：${profile.providerName}` : '服务商资料'"
    :description="profile ? `最近提交 ${formatDateTime(profile.submittedAt)} · 最近审核 ${formatDateTime(profile.reviewedAt)}` : undefined"
    traced
  >
    <template #actions>
      <el-button :loading="loading" @click="load">刷新</el-button>
      <el-button @click="router.push({ name: 'provider-profile-reviews' })">返回列表</el-button>
      <template v-if="profile && canReview(profile)">
        <el-button type="danger" plain :disabled="busy" @click="rejecting = true">驳回</el-button>
        <el-button type="success" :loading="busy" @click="review('APPROVE')">审核通过</el-button>
      </template>
    </template>

    <div v-loading="loading">
      <el-result v-if="error && !profile" icon="error" :title="error">
        <template #extra><el-button type="primary" @click="load">重新加载</el-button></template>
      </el-result>

      <template v-if="profile">
        <div class="status">
          <el-tag :type="reviewStatus(profile.status).tag" size="large">{{ reviewStatus(profile.status).label }}</el-tag>
          <span v-if="profile.status === 'PENDING'">右边是待审核的一份，和左边不一样的行标了颜色。</span>
        </div>
        <el-alert v-if="profile.status === 'REJECTED'" type="error" :closable="false" show-icon class="alert"
                  :title="`已驳回：${profile.rejectReason ?? '（没写原因）'}`" />

        <el-table :data="rows" row-key="key" border :row-class-name="({ row }) => (row.changed ? 'changed' : '')">
          <el-table-column prop="label" label="" width="140" />
          <el-table-column prop="published" label="App 上正在显示的" min-width="260" />
          <el-table-column prop="submitted"
                           :label="profile.status === 'PENDING' ? '待审核的' : '最近提交的'" min-width="260" />
        </el-table>

        <h3 class="title">改动记录</h3>
        <ChangeLogTable :changes="profile.changes" :subject-labels="CHANGE_SUBJECT_LABELS"
                        :action-labels="CHANGE_ACTION_LABELS" />
      </template>
    </div>
  </PageSection>

  <el-dialog v-model="rejecting" title="驳回服务商资料" width="480px" :close-on-click-modal="false">
    <el-input v-model="reason" type="textarea" :rows="3" :maxlength="REJECT_REASON_MAX" show-word-limit
              placeholder="写清楚哪里不符合，比如：经营主体和营业执照不一致" />
    <p v-if="reasonProblem" class="problem">{{ reasonProblem }}</p>
    <template #footer>
      <el-button @click="rejecting = false">取消</el-button>
      <el-button type="danger" :loading="busy" @click="review('REJECT')">驳回</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.status {
  display: flex;
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

.problem {
  margin: 8px 0 0;
  color: var(--el-color-danger);
  font-size: 13px;
}

:deep(.changed) td:last-child {
  background: var(--el-color-warning-light-9);
  font-weight: 600;
}
</style>
