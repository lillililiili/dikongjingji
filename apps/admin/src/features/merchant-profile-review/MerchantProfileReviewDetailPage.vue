<script setup lang="ts">
import type { FormInstance } from 'element-plus'
import { computed, onMounted, reactive, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'
import { SUBMISSION_STATUS_LABELS, SUBMISSION_STATUS_TAGS } from '@/features/merchant-profile/merchant-profile-api'
import ProfileDescriptions from '@/features/merchant-profile/ProfileDescriptions.vue'

import {
  createMerchantProfileReviewApi,
  rejectReviewRules,
  REVIEW_LIMITS,
  reviewIsStale,
  type MallMerchantSubmission,
  type MallMerchantSubmissionDetail,
  type MerchantProfileReviewApi,
} from './merchant-profile-review-api'

const props = defineProps<{
  api?: MerchantProfileReviewApi
}>()

const api = props.api ?? createMerchantProfileReviewApi(http)
const route = useRoute()
const router = useRouter()
const detail = shallowRef<MallMerchantSubmissionDetail | null>(null)
const loading = ref(false)
const error = ref('')
const busy = ref(false)
const rejectRef = ref<FormInstance>()
const reject = reactive({ open: false, reason: '' })

const submissionId = computed(() => String(route.params.submissionId ?? ''))
const submission = computed(() => detail.value?.submission ?? null)
const pending = computed(() => submission.value?.status === 'SUBMITTED')

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    detail.value = await api.detail(submissionId.value)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function run(action: () => Promise<MallMerchantSubmissionDetail>, done: string): Promise<void> {
  busy.value = true
  try {
    detail.value = await action()
    notifyRecorded(done, '这家的提交记录')
  } catch (failure) {
    showError(failure)
    // 已经被处理过或版本不对：重新读一次，看现在是什么状态。
    if (reviewIsStale(failure)) void load()
  } finally {
    busy.value = false
  }
}

async function approve(): Promise<void> {
  const current = submission.value
  if (!current || busy.value) return
  const confirmed = await confirmAction({
    title: '审核通过',
    message: `通过后“${current.organizationName}”的这一版资料整份显示在 App 的商家主页上，替换现在对外的资料；`
      + '默认开票方、售后责任方也会给没单独设置的商品兜底（已经下的订单不受影响）。',
    confirmText: '审核通过',
  })
  if (!confirmed) return
  await run(() => api.approve(current), '审核通过商家资料')
}

async function submitReject(): Promise<void> {
  const current = submission.value
  if (!current || !rejectRef.value || busy.value) return
  if (!(await rejectRef.value.validate().catch(() => false))) return
  const confirmed = await confirmAction({
    title: '驳回商家资料',
    message: '驳回后商家在工作台能看到原因，改好后可以重新提交；App 上仍显示现在对外的资料。',
    confirmText: '驳回',
    danger: true,
  })
  if (!confirmed) return
  reject.open = false
  await run(() => api.reject(current, reject.reason), '驳回商家资料')
  reject.reason = ''
}

function openHistory(item: MallMerchantSubmission): void {
  if (item.submissionId === submissionId.value) return
  void router.push({ name: 'merchant-profile-review-detail', params: { submissionId: item.submissionId } })
}

// 从“最近的提交”点进另一版时路由组件不重建，按新的 submissionId 重新读。
watch(submissionId, (next, previous) => {
  if (next && next !== previous) void load()
})

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="submission ? `商家资料：${submission.organizationName}` : '商家资料审核'"
    :description="submission ? `${SUBMISSION_STATUS_LABELS[submission.status]} · ${submission.submittedByName} 提交于 ${formatDateTime(submission.submittedAt)}` : undefined"
    traced
  >
    <template #actions>
      <template v-if="pending">
        <el-button type="success" :loading="busy" @click="approve">审核通过</el-button>
        <el-button type="danger" plain :disabled="busy" @click="reject.open = true">驳回</el-button>
      </template>
      <el-button @click="load()">刷新</el-button>
      <el-button @click="router.push({ name: 'merchant-profile-reviews' })">返回列表</el-button>
    </template>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button size="small" @click="load()">重新加载</el-button>
    </el-alert>
    <div v-else-if="loading && !detail" v-loading="true" class="placeholder" />
    <template v-else-if="detail && submission">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="状态">
          <el-tag :type="SUBMISSION_STATUS_TAGS[submission.status]">{{ SUBMISSION_STATUS_LABELS[submission.status] }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="机构资质">
          <el-tag v-if="detail.qualificationReviewed" type="success">已审核</el-tag>
          <el-tag v-else type="warning">未审核</el-tag>
          <span class="hint inline">在“机构与资质”里审核，和商家资料是两件事</span>
        </el-descriptions-item>
        <el-descriptions-item label="审核">
          <template v-if="submission.reviewedAt">
            {{ submission.reviewedByName ?? '—' }} · {{ formatDateTime(submission.reviewedAt) }}
          </template>
          <template v-else>—</template>
        </el-descriptions-item>
        <el-descriptions-item v-if="submission.rejectReason" label="驳回原因" :span="3">
          {{ submission.rejectReason }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="columns">
        <div class="column">
          <h2 class="block-title">这一版</h2>
          <ProfileDescriptions :value="submission.draft" :compare="detail.published" />
        </div>
        <div class="column">
          <h2 class="block-title">现在对外的资料</h2>
          <template v-if="detail.published">
            <p class="hint">审核通过于 {{ formatDateTime(detail.published.approvedAt) }}</p>
            <ProfileDescriptions :value="detail.published" />
          </template>
          <el-empty v-else description="还没有审核通过的资料（这一版通过后才有商家主页资料）" :image-size="80" />
        </div>
      </div>

      <h2 class="block-title">这家最近的提交</h2>
      <el-table :data="detail.history" row-key="submissionId" border size="small" empty-text="没有别的提交">
        <el-table-column label="提交时间" width="160">
          <template #default="{ row }">{{ formatDateTime((row as MallMerchantSubmission).submittedAt) }}</template>
        </el-table-column>
        <el-table-column label="提交人" width="120">
          <template #default="{ row }">{{ (row as MallMerchantSubmission).submittedByName }}</template>
        </el-table-column>
        <el-table-column label="经营主体" min-width="200">
          <template #default="{ row }">{{ (row as MallMerchantSubmission).draft.businessEntity }}</template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag size="small" :type="SUBMISSION_STATUS_TAGS[(row as MallMerchantSubmission).status]">
              {{ SUBMISSION_STATUS_LABELS[(row as MallMerchantSubmission).status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="审核" min-width="200">
          <template #default="{ row }">
            <template v-if="(row as MallMerchantSubmission).reviewedAt">
              {{ (row as MallMerchantSubmission).reviewedByName ?? '—' }} ·
              {{ formatDateTime((row as MallMerchantSubmission).reviewedAt) }}
              <span v-if="(row as MallMerchantSubmission).rejectReason" class="hint">
                （{{ (row as MallMerchantSubmission).rejectReason }}）
              </span>
            </template>
            <template v-else>—</template>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <span v-if="(row as MallMerchantSubmission).submissionId === submission.submissionId" class="hint">当前</span>
            <el-button v-else link type="primary" @click="openHistory(row as MallMerchantSubmission)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="hint">最多显示最近 20 次。</p>
    </template>
  </PageSection>

  <el-dialog v-model="reject.open" title="驳回商家资料" width="480px" :close-on-click-modal="false">
    <el-form ref="rejectRef" :model="reject" :rules="rejectReviewRules" label-width="80px" @submit.prevent>
      <el-form-item label="驳回原因" prop="reason" required>
        <el-input v-model="reject.reason" type="textarea" :rows="3" :maxlength="REVIEW_LIMITS.rejectReason"
                  show-word-limit placeholder="商家能看到，写清楚哪里要改，比如：经营主体与营业执照不一致" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="reject.open = false">取消</el-button>
      <el-button type="danger" :loading="busy" @click="submitReject">驳回</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.placeholder {
  min-height: 200px;
}

.columns {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 20px;
}

.block-title {
  margin: 20px 0 10px;
  font-size: 15px;
}

.hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.hint.inline {
  margin: 0 0 0 8px;
}
</style>
