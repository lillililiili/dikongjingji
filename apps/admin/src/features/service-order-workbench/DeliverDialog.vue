<script setup lang="ts">
import { ref, watch } from 'vue'

import { formatBytes } from '@/core/ui/format'

import type { DeliveryRequest, ServiceOrder } from './service-order-api'
import { buildDeliveryRequest, LIMITS } from './service-order-rules'

/**
 * 标记完成并提交交付（第 N 次）：写交付说明，勾选这次要交付的、已经上传的文件（0–10 个，没勾的留着下次用）。
 * 校验过了才发出 submit，接口由详情页调（带 If-Match）。
 */
const props = defineProps<{
  order: ServiceOrder | null
  busy: boolean
}>()

const emit = defineEmits<{
  submit: [request: DeliveryRequest]
  close: []
}>()

const note = ref('')
const fileIds = ref<string[]>([])
const problem = ref('')

watch(() => props.order, (order) => {
  if (!order) return
  note.value = ''
  fileIds.value = order.stagedFiles.slice(0, LIMITS.deliveryFiles).map((file) => file.fileId)
  problem.value = ''
}, { immediate: true })

function submit(): void {
  const built = buildDeliveryRequest(note.value, fileIds.value)
  problem.value = built.ok ? '' : built.message
  if (built.ok) emit('submit', built.request)
}
</script>

<template>
  <el-dialog
    :model-value="order !== null"
    :title="order ? `提交交付（第 ${order.deliveries.length + 1} 次）` : ''"
    width="560px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <template v-if="order">
      <p class="hint">
        提交后订单变成“待客户验收”；客户验收或退回整改（写原因）。{{ order.status === 'REWORK' ? '这是整改后的重新交付。' : '' }}
      </p>
      <el-form label-width="84px" @submit.prevent>
        <el-form-item label="交付说明" required>
          <el-input v-model="note" type="textarea" :rows="4" :maxlength="LIMITS.deliveryNote" show-word-limit
                    placeholder="做了什么、交付了哪些成果、怎么查看" />
        </el-form-item>
        <el-form-item label="交付文件">
          <el-checkbox-group v-if="order.stagedFiles.length > 0" v-model="fileIds" :max="LIMITS.deliveryFiles"
                             class="files">
            <el-checkbox v-for="file in order.stagedFiles" :key="file.fileId" :value="file.fileId">
              {{ file.name }} <span class="muted">{{ formatBytes(file.sizeBytes) }}</span>
            </el-checkbox>
          </el-checkbox-group>
          <span v-else class="muted">还没有上传文件，也可以只写说明交付（比如成果已线下移交）。</span>
          <span class="muted block">最多 {{ LIMITS.deliveryFiles }} 个；没勾选的文件留着下次交付用。</span>
        </el-form-item>
      </el-form>
      <el-alert v-if="problem" type="error" :title="problem" :closable="false" show-icon />
    </template>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">提交交付</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  color: var(--admin-text-secondary);
}

.files {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.muted.block {
  display: block;
  width: 100%;
}
</style>
