<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { ref, watch } from 'vue'

import { confirmAction, showError } from '@/core/ui/feedback'

import { DECLINE_REASON_MAX, declineReasonError, type MallInquiryApi, type MallMerchantInquiry } from './mall-inquiry-api'

/** 拒绝询价：写明原因；报过的有效报价随之撤回，拒绝后不能再报。 */
const props = defineProps<{
  api: MallInquiryApi
  inquiry: MallMerchantInquiry | null
}>()

const emit = defineEmits<{
  close: []
  done: [inquiry: MallMerchantInquiry]
}>()

const reason = ref('')
const error = ref('')
const submitting = ref(false)

watch(() => props.inquiry, () => {
  reason.value = ''
  error.value = ''
}, { immediate: true })

async function submit(): Promise<void> {
  const inquiry = props.inquiry
  if (!inquiry || submitting.value) return
  error.value = declineReasonError(reason.value) ?? ''
  if (error.value) return
  const confirmed = await confirmAction({
    title: '确认拒绝询价',
    message: inquiry.myQuote
      ? '拒绝后你报过的价随之撤回，客户看不到了，这张询价单也不能再报价。'
      : '拒绝后这张询价单不能再报价。',
    confirmText: '确认拒绝',
    danger: true,
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const next = await props.api.decline(inquiry.inquiryId, reason.value)
    ElMessage.success('已拒绝这张询价单')
    emit('done', next)
  } catch (failure) {
    showError(failure)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="inquiry !== null"
    :title="inquiry ? `拒绝询价：${inquiry.product.name}` : ''"
    width="480px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <el-form v-if="inquiry" label-width="84px" @submit.prevent>
      <el-form-item label="拒绝原因" required :error="error">
        <el-input v-model="reason" class="decline-reason" type="textarea" :rows="3" :maxlength="DECLINE_REASON_MAX"
                  show-word-limit placeholder="比如：这个配置暂时缺货" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="danger" :loading="submitting" @click="submit">确认拒绝</el-button>
    </template>
  </el-dialog>
</template>
