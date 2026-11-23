<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { ApiRequestError } from '@/core/http/api-error'
import { showError } from '@/core/ui/feedback'

import {
  buildResultRequest,
  initialResultForm,
  RESULT_LIMITS,
  type ActivityResultsApi,
  type ResultForm,
  type ResultRosterEntry,
} from './activity-results-api'

/** 录入 / 修改一位的结果。修改带读到的版本号；被别人改过时提示刷新名单。 */
const props = defineProps<{
  api: Pick<ActivityResultsApi, 'record'>
  entry: ResultRosterEntry | null
}>()

const emit = defineEmits<{
  recorded: [entry: ResultRosterEntry]
  close: []
  /** 版本冲突：名单要重新读。 */
  stale: []
}>()

const form = reactive<ResultForm>({ completion: 'COMPLETED', scoreText: '', certificateNote: '' })
const problem = ref('')
const busy = ref(false)
const revising = computed(() => props.entry?.result !== null && props.entry?.result !== undefined)

watch(() => props.entry, (entry) => {
  if (!entry) return
  Object.assign(form, initialResultForm(entry))
  problem.value = ''
}, { immediate: true })

async function submit(): Promise<void> {
  const entry = props.entry
  if (!entry || busy.value) return
  const built = buildResultRequest(form, entry)
  if (!built.ok) {
    problem.value = built.message
    return
  }
  problem.value = ''
  busy.value = true
  try {
    const next = await props.api.record(entry.ticketId, built.request)
    ElMessage.success(revising.value ? '已修改，客户在凭证和订单页看到新的结果' : '已录入，客户会收到“结果已发布”的通知')
    emit('recorded', next)
  } catch (failure) {
    if (failure instanceof ApiRequestError && failure.code === 'ACT_RESULT_VERSION_CONFLICT') {
      problem.value = '结果刚被别人改过，请刷新名单后再改'
      emit('stale')
      return
    }
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="entry !== null"
    :title="revising ? '修改结果' : '录入结果'"
    width="520px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <template v-if="entry">
      <p class="hint">
        {{ entry.holderName ?? '参与人' }}{{ entry.age === null ? '' : ` · ${entry.age} 岁` }}
        · 凭证尾号 {{ entry.codeTail ?? '—' }}{{ entry.teamName ? ` · ${entry.teamName}` : '' }}
      </p>
      <el-form label-width="84px">
        <el-form-item label="完成情况" required>
          <el-radio-group v-model="form.completion">
            <el-radio value="COMPLETED">完成</el-radio>
            <el-radio value="INCOMPLETE">未完成</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="成绩">
          <el-input v-model="form.scoreText" :maxlength="RESULT_LIMITS.score" show-word-limit
                    placeholder="比如：完成任务 5/5 · 用时 18′42″" />
        </el-form-item>
        <el-form-item label="证书说明">
          <el-input v-model="form.certificateNote" :maxlength="RESULT_LIMITS.certificate" show-word-limit
                    placeholder="比如：完赛证书由主办方现场发放" />
        </el-form-item>
      </el-form>
      <el-alert v-if="problem" type="error" :title="problem" :closable="false" show-icon />
    </template>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">{{ revising ? '保存修改' : '录入' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  color: var(--admin-text-secondary);
}
</style>
