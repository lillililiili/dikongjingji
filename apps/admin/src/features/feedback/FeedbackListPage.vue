<script setup lang="ts">
import type { FormInstance, FormRules } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'

import { http } from '@/core/http'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import { maxChars, required } from '@/core/ui/form-rules'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  createFeedbackApi,
  FEEDBACK_TYPE_LABELS,
  HANDLE_NOTE_MAX,
  hideLabel,
  SOURCE_OPTIONS,
  sourceLabel,
  type FeedbackFilters,
  type OpsFeedback,
  type OpsFeedbackPage,
} from './feedback-api'

const api = createFeedbackApi(http)
const list = usePagedList<OpsFeedback, FeedbackFilters>((filters, page, size) => api.list(filters, page, size),
  { handleStatus: 'PENDING', source: '' })
const pendingCount = computed(() => (list.last.value as OpsFeedbackPage | null)?.pendingCount ?? 0)

const formRef = ref<FormInstance>()
const handling = reactive<{ feedback: OpsFeedback | null, note: string }>({ feedback: null, note: '' })
const busy = ref(false)
/** 这一页里已经点过隐藏的举报（接口幂等，这里只用来把按钮改成“已隐藏”）。 */
const hidden = reactive(new Set<string>())

const rules: FormRules = {
  note: [required('处理说明'), maxChars('处理说明', HANDLE_NOTE_MAX)],
}

function setStatus(value: string | number | boolean | undefined): void {
  list.filters.handleStatus = (value as FeedbackFilters['handleStatus']) ?? ''
  void list.search()
}

function openHandle(feedback: OpsFeedback): void {
  handling.feedback = feedback
  handling.note = hidden.has(feedback.feedbackId) ? `已${hideLabel(feedback.source)}。` : ''
  formRef.value?.clearValidate()
}

async function hide(feedback: OpsFeedback): Promise<void> {
  const label = hideLabel(feedback.source)
  const confirmed = await confirmAction({
    title: `确认${label}`,
    message: `${label}后，App 上所有人都看不到这条内容，提交的人也看不到。平台只协助处理，不裁决纠纷。`,
    confirmText: label,
    danger: true,
  })
  if (!confirmed) return
  busy.value = true
  try {
    await api.hideTarget(feedback)
    hidden.add(feedback.feedbackId)
    notifyRecorded(label, '内容的隐藏记录（隐藏人和时间）')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

async function submitHandle(): Promise<void> {
  const feedback = handling.feedback
  if (!feedback || !formRef.value || busy.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const confirmed = await confirmAction({
    title: '确认标为已处理',
    message: '只能处理一次，处理说明和你的名字会一起记下来。',
    confirmText: '标为已处理',
  })
  if (!confirmed) return
  busy.value = true
  try {
    await api.handle(feedback.feedbackId, handling.note, feedback.version)
    handling.feedback = null
    notifyRecorded('标为已处理', '已处理列表')
    void list.load()
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="举报与反馈"
    description="用户在“帮助与反馈”提交的意见、发现页视频评论和直播弹幕的举报。平台只协助沟通，不裁决，不承诺处理时限。"
    traced
  >
    <div class="toolbar">
      <el-radio-group :model-value="list.filters.handleStatus" @change="setStatus">
        <el-radio-button value="PENDING">待处理（{{ pendingCount }}）</el-radio-button>
        <el-radio-button value="HANDLED">已处理</el-radio-button>
        <el-radio-button value="">全部</el-radio-button>
      </el-radio-group>
      <el-select v-model="list.filters.source" clearable placeholder="全部来源" style="width: 160px"
                 @change="list.search()">
        <el-option v-for="option in SOURCE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
      </el-select>
    </div>

    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="feedbackId"
      empty-text="没有反馈"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="提交时间" width="150">
        <template #default="{ row }">{{ formatDateTime((row as OpsFeedback).createdAt) }}</template>
      </el-table-column>
      <el-table-column label="来源" width="130">
        <template #default="{ row }">
          <el-tag :type="(row as OpsFeedback).source === 'USER' ? 'info' : 'warning'" effect="plain">
            {{ sourceLabel((row as OpsFeedback).source) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ FEEDBACK_TYPE_LABELS[(row as OpsFeedback).type] }}</template>
      </el-table-column>
      <el-table-column label="内容" min-width="280">
        <template #default="{ row }"><span class="text">{{ (row as OpsFeedback).text }}</span></template>
      </el-table-column>
      <el-table-column label="提交人" width="120">
        <template #default="{ row }">{{ (row as OpsFeedback).reporter.displayName ?? '账号已停用' }}</template>
      </el-table-column>
      <el-table-column label="处理" min-width="200">
        <template #default="{ row }">
          <template v-if="(row as OpsFeedback).handling">
            <div>{{ (row as OpsFeedback).handling?.note }}</div>
            <div class="who">
              {{ (row as OpsFeedback).handling?.handlerName }} · {{ formatDateTime((row as OpsFeedback).handling?.handledAt) }}
            </div>
          </template>
          <el-tag v-else type="warning">待处理</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <template v-if="(row as OpsFeedback).target">
            <el-button v-if="hidden.has((row as OpsFeedback).feedbackId)" link disabled>已隐藏</el-button>
            <el-button v-else link type="danger" :disabled="busy" @click="hide(row as OpsFeedback)">
              {{ hideLabel((row as OpsFeedback).source) }}
            </el-button>
          </template>
          <el-button v-if="(row as OpsFeedback).handleStatus === 'PENDING'" link type="primary"
                     @click="openHandle(row as OpsFeedback)">处理</el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>

  <el-dialog
    :model-value="handling.feedback !== null"
    title="标为已处理"
    width="520px"
    :close-on-click-modal="false"
    @close="handling.feedback = null"
  >
    <template v-if="handling.feedback">
      <p class="quote">{{ handling.feedback.text }}</p>
      <el-form ref="formRef" :model="handling" :rules="rules" label-width="80px">
        <el-form-item label="处理说明" prop="note">
          <el-input v-model="handling.note" type="textarea" :rows="3" :maxlength="HANDLE_NOTE_MAX" show-word-limit
                    placeholder="比如：已隐藏该评论；已转交研发排查；已联系商家沟通" />
        </el-form-item>
      </el-form>
    </template>
    <template #footer>
      <el-button @click="handling.feedback = null">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submitHandle">标为已处理</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.text {
  white-space: pre-wrap;
}

.who {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.quote {
  margin: 0 0 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--admin-surface-muted);
  white-space: pre-wrap;
}
</style>
