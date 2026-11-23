<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref, watch } from 'vue'

import { confirmAction, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'

import {
  LIMITS,
  returnAddressErrors,
  type AftersaleApi,
  type FieldErrors,
  type ReturnAddress,
} from './aftersale-api'

/** 默认寄回地址：同意退货时默认带出；商家 48 小时没处理、自动同意时也用它。 */
const props = defineProps<{
  api: AftersaleApi
  open: boolean
}>()

const emit = defineEmits<{
  close: []
  saved: [address: ReturnAddress]
}>()

const form = reactive({ contactName: '', phone: '', address: '', note: '' })
const errors = reactive<FieldErrors>({})
const current = ref<ReturnAddress | null>(null)
const loading = ref(false)
const loadFailed = ref(false)
const saving = ref(false)

function fill(address: ReturnAddress | null): void {
  form.contactName = address?.contactName ?? ''
  form.phone = address?.phone ?? ''
  form.address = address?.address ?? ''
  form.note = address?.note ?? ''
}

async function load(): Promise<void> {
  loading.value = true
  loadFailed.value = false
  try {
    current.value = await props.api.returnAddress()
    fill(current.value)
  } catch (failure) {
    loadFailed.value = true
    showError(failure)
  } finally {
    loading.value = false
  }
}

watch(() => props.open, (open) => {
  if (!open) return
  Object.assign(errors, { contactName: undefined, phone: undefined, address: undefined, note: undefined })
  void load()
}, { immediate: true })

async function save(): Promise<void> {
  if (saving.value) return
  const found = returnAddressErrors(form)
  Object.assign(errors, { contactName: undefined, phone: undefined, address: undefined, note: undefined }, found)
  if (Object.keys(found).length > 0) return
  const confirmed = await confirmAction({
    title: '保存默认寄回地址',
    message: '以后同意退货时默认带出这个地址；商家 48 小时没处理、系统自动同意时，买家看到的也是这个地址。',
    confirmText: '保存',
  })
  if (!confirmed) return
  saving.value = true
  try {
    const saved = await props.api.saveReturnAddress(form)
    current.value = saved
    ElMessage.success('默认寄回地址已保存')
    emit('saved', saved)
  } catch (failure) {
    showError(failure)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="open" title="默认寄回地址" width="520px" :close-on-click-modal="false" @close="emit('close')">
    <div v-loading="loading">
      <el-alert
        v-if="!loading && !loadFailed && !current"
        type="warning"
        :closable="false"
        show-icon
        title="还没有设置默认寄回地址"
        description="没设时，商家超时未处理被自动同意的退货没有寄回地址，买家要先联系商家。"
        class="return-address__alert"
      />
      <p v-if="current" class="return-address__hint">上次保存：{{ formatDateTime(current.updatedAt) }}</p>
      <el-form label-width="84px" @submit.prevent>
        <el-form-item label="收件人" required :error="errors.contactName">
          <el-input v-model="form.contactName" class="return-contact" :maxlength="LIMITS.contactName"
                    placeholder="比如：售后组 王师傅" />
        </el-form-item>
        <el-form-item label="电话" required :error="errors.phone">
          <el-input v-model="form.phone" class="return-phone" maxlength="20" placeholder="手机或座机" />
        </el-form-item>
        <el-form-item label="地址" required :error="errors.address">
          <el-input v-model="form.address" class="return-address" type="textarea" :rows="2" :maxlength="LIMITS.address"
                    show-word-limit placeholder="省市区和详细地址" />
        </el-form-item>
        <el-form-item label="说明" :error="errors.note">
          <el-input v-model="form.note" class="return-note" type="textarea" :rows="2" :maxlength="LIMITS.note"
                    show-word-limit placeholder="可空，比如：请勿寄到付，包裹里放一张写有售后编号的纸条" />
        </el-form-item>
      </el-form>
    </div>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="loading" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.return-address__alert {
  margin-bottom: 12px;
}

.return-address__hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
