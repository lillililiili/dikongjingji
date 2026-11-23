<script setup lang="ts">
/**
 * 上线：立即发布，或按北京时间定在以后（最远一年）。确认后交给父页面调上线接口；publishAt 为 null 表示现在。
 */
import { computed, ref, watch } from 'vue'

import { beijingToIso } from '../content-rules'

const props = defineProps<{
  open: boolean
  title: string
  busy?: boolean
}>()

const emit = defineEmits<{
  close: []
  confirm: [publishAt: string | null]
}>()

const mode = ref<'NOW' | 'LATER'>('NOW')
const when = ref('')

watch(() => props.open, (open) => {
  if (open) {
    mode.value = 'NOW'
    when.value = ''
  }
})

const scheduled = computed(() => (mode.value === 'LATER' ? beijingToIso(when.value) : null))
const invalid = computed(() => mode.value === 'LATER' && scheduled.value === null)

function disabledDate(date: Date): boolean {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const last = new Date(today)
  last.setFullYear(last.getFullYear() + 1)
  return date < today || date > last
}
</script>

<template>
  <el-dialog :model-value="open" :title="`上线：${title}`" width="460px" @close="emit('close')">
    <el-radio-group v-model="mode">
      <el-radio value="NOW">立即发布</el-radio>
      <el-radio value="LATER">定时发布</el-radio>
    </el-radio-group>
    <div v-if="mode === 'LATER'" class="publish-dialog__when">
      <el-date-picker
        v-model="when"
        type="datetime"
        format="YYYY-MM-DD HH:mm"
        value-format="YYYY-MM-DD HH:mm"
        placeholder="发布时间（北京时间）"
        :disabled-date="disabledDate"
      />
      <span class="publish-dialog__hint">到了这个时间 App 才看得到；之前在列表里显示“已上线”。</span>
    </div>
    <p class="publish-dialog__hint">上线后 App 发现页按发布时间排序；要改时间请先下线再上线。</p>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :disabled="invalid" :loading="busy" @click="emit('confirm', scheduled)">确认上线</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.publish-dialog__when {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 12px;
}

.publish-dialog__hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
