<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { confirmAction, showError } from '@/core/ui/feedback'

import {
  LIMITS,
  needsRefresh,
  rejectErrors,
  type Aftersale,
  type AftersaleApi,
  type RejectErrors,
} from './aftersale-api'

/**
 * 两种“不同意”：
 * - review：审核时不同意（原因必填，说明可空），不会退款；
 * - receipt：收到退货后验货不通过（原因、说明都必填，说明写寄回的商品怎么处理），不会退款。
 */
const props = defineProps<{
  api: AftersaleApi
  aftersale: Aftersale | null
  mode: 'review' | 'receipt'
}>()

const emit = defineEmits<{
  close: []
  done: [next: Aftersale]
  stale: []
}>()

const form = reactive({ reason: '', note: '' })
const errors = reactive<RejectErrors>({})
const submitting = ref(false)

const receipt = computed(() => props.mode === 'receipt')

watch(() => props.aftersale, (aftersale) => {
  if (!aftersale) return
  form.reason = ''
  form.note = ''
  errors.reason = undefined
  errors.note = undefined
}, { immediate: true })

async function submit(): Promise<void> {
  const aftersale = props.aftersale
  if (!aftersale || submitting.value) return
  const found = rejectErrors(form.reason, form.note, receipt.value)
  errors.reason = found.reason
  errors.note = found.note
  if (found.reason || found.note) return
  const confirmed = await confirmAction({
    title: receipt.value ? '验货不通过' : '不同意售后',
    message: receipt.value
      ? `售后 ${aftersale.aftersaleNo} 验货不通过，不会退款；买家能看到原因和寄回商品的处理说明。提交后不能撤回。`
      : `不同意售后 ${aftersale.aftersaleNo}，不会退款；买家能看到原因，可以联系平台客服协助沟通。提交后不能撤回。`,
    confirmText: receipt.value ? '验货不通过' : '不同意',
    danger: true,
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const next = receipt.value
      ? await props.api.rejectReceipt(aftersale, form.reason, form.note)
      : await props.api.reject(aftersale, form.reason, form.note)
    ElMessage.success(receipt.value ? '已记下验货不通过' : '已拒绝这次售后')
    emit('done', next)
  } catch (failure) {
    showError(failure)
    if (needsRefresh(failure)) emit('stale')
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    :model-value="aftersale !== null"
    :title="aftersale ? `${receipt ? '验货不通过' : '不同意'}：${aftersale.aftersaleNo}` : ''"
    width="500px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <el-form v-if="aftersale" label-width="84px" @submit.prevent>
      <el-form-item :label="receipt ? '不通过原因' : '拒绝原因'" required :error="errors.reason">
        <el-input v-model="form.reason" class="reject-reason" type="textarea" :rows="2" :maxlength="LIMITS.rejectReason"
                  show-word-limit
                  :placeholder="receipt ? '买家能看到，比如：商品已拆封使用，不符合七天无理由' : '买家能看到，比如：商品已超过七天无理由期限'" />
      </el-form-item>
      <el-form-item label="商家说明" :required="receipt" :error="errors.note">
        <el-input v-model="form.note" class="reject-note" type="textarea" :rows="3" :maxlength="LIMITS.note"
                  show-word-limit
                  :placeholder="receipt ? '寄回的商品怎么处理，比如：3 天内原样寄回买家，运费由商家承担' : '可空，补充说明'" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="danger" :loading="submitting" @click="submit">{{ receipt ? '验货不通过' : '不同意' }}</el-button>
    </template>
  </el-dialog>
</template>
