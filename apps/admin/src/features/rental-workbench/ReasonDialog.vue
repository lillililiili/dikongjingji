<script setup lang="ts">
import { ref, watch } from 'vue'

import { QUOTE_LIMITS, reasonError } from './rental-api'

/** 写一句原因（拒绝询价、取消订单）：1–80 字。校验过了才发出 submit，接口由页面调。 */
const props = defineProps<{
  open: boolean
  title: string
  hint: string
  placeholder: string
  confirmText: string
  busy: boolean
}>()

const emit = defineEmits<{
  submit: [reason: string]
  close: []
}>()

const reason = ref('')
const problem = ref('')

watch(() => props.open, (open) => {
  if (open) {
    reason.value = ''
    problem.value = ''
  }
})

function submit(): void {
  const error = reasonError(reason.value)
  problem.value = error ?? ''
  if (!error) emit('submit', reason.value.trim())
}
</script>

<template>
  <el-dialog :model-value="open" :title="title" width="480px" :close-on-click-modal="false" @close="emit('close')">
    <p class="hint">{{ hint }}</p>
    <el-input v-model="reason" type="textarea" :rows="3" :maxlength="QUOTE_LIMITS.reason" show-word-limit
              :placeholder="placeholder" />
    <p v-if="problem" class="problem">{{ problem }}</p>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="danger" :loading="busy" @click="submit">{{ confirmText }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 10px;
  color: var(--admin-text-secondary);
}

.problem {
  margin: 8px 0 0;
  color: var(--el-color-danger);
  font-size: 13px;
}
</style>
