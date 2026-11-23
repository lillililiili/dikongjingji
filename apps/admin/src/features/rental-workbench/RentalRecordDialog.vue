<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, ref, watch } from 'vue'

import { confirmAction, showError } from '@/core/ui/feedback'

import {
  buildRecordRequest,
  QUOTE_LIMITS,
  type RentalOrder,
  type RentalWorkbenchApi,
} from './rental-api'

/** 录入交接 / 归还记录：录完等客户在 App 里确认；客户确认之前可以再录（覆盖上一次）。 */
const props = defineProps<{
  api: Pick<RentalWorkbenchApi, 'recordHandover' | 'recordReturn'>
  order: RentalOrder | null
  kind: 'handover' | 'return'
}>()

const emit = defineEmits<{
  changed: [order: RentalOrder]
  close: []
}>()

const note = ref('')
const occurredAt = ref<Date | null>(null)
const problem = ref('')
const busy = ref(false)

const label = computed(() => (props.kind === 'handover' ? '交接' : '归还'))
const existing = computed(() => (props.kind === 'handover' ? props.order?.handoverRecord : props.order?.returnRecord))

watch(() => props.order, (order) => {
  if (!order) return
  note.value = existing.value?.note ?? ''
  occurredAt.value = existing.value ? new Date(existing.value.occurredAt) : null
  problem.value = ''
}, { immediate: true })

async function submit(): Promise<void> {
  const order = props.order
  if (!order || busy.value) return
  const built = buildRecordRequest(note.value, occurredAt.value)
  if (!built.ok) {
    problem.value = built.message
    return
  }
  problem.value = ''
  const confirmed = await confirmAction({
    title: `确认录入${label.value}记录`,
    message: `提交后客户在 App 里看到这条${label.value}记录并确认；客户确认之前你还可以改。`,
    confirmText: '提交',
  })
  if (!confirmed) return
  busy.value = true
  try {
    const next = props.kind === 'handover'
      ? await props.api.recordHandover(order.rentalOrderId, built.request)
      : await props.api.recordReturn(order.rentalOrderId, built.request)
    ElMessage.success(`已录入${label.value}记录，等客户在 App 里确认`)
    emit('changed', next)
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="order !== null" :title="`录入${label}记录`" width="520px" :close-on-click-modal="false"
             @close="emit('close')">
    <el-form label-width="80px">
      <el-form-item :label="`${label}时间`">
        <el-date-picker v-model="occurredAt" type="datetime" placeholder="不选就是现在" />
      </el-form-item>
      <el-form-item label="说明" required>
        <el-input v-model="note" type="textarea" :rows="4" :maxlength="QUOTE_LIMITS.recordNote" show-word-limit
                  placeholder="设备编号、配件、外观等，比如：机身编号 RD-0012，电池 2 块，外观无划痕" />
      </el-form-item>
    </el-form>
    <p v-if="problem" class="problem">{{ problem }}</p>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">提交</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.problem {
  margin: 0;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
