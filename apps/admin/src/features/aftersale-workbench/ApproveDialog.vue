<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref, watch } from 'vue'

import { confirmAction, showError } from '@/core/ui/feedback'

import {
  LIMITS,
  needsRefresh,
  returnAddressErrors,
  type Aftersale,
  type AftersaleApi,
  type FieldErrors,
} from './aftersale-api'

/** 同意退货：给买家寄回地址，默认带出本机构的默认寄回地址；可以顺手存成默认。 */
const props = defineProps<{
  api: AftersaleApi
  aftersale: Aftersale | null
}>()

const emit = defineEmits<{
  close: []
  done: [next: Aftersale]
  stale: []
}>()

const form = reactive({ contactName: '', phone: '', address: '', note: '', saveAsDefault: false })
const errors = reactive<FieldErrors>({})
const hasDefault = ref(false)
const loading = ref(false)
const submitting = ref(false)

function clearErrors(): void {
  Object.assign(errors, { contactName: undefined, phone: undefined, address: undefined, note: undefined })
}

watch(() => props.aftersale, async (aftersale) => {
  if (!aftersale) return
  clearErrors()
  Object.assign(form, { contactName: '', phone: '', address: '', note: '', saveAsDefault: false })
  loading.value = true
  try {
    const saved = await props.api.returnAddress()
    hasDefault.value = saved !== null
    // 没设过默认地址时空着，并默认勾上“存为默认”，下次就不用再填。
    form.saveAsDefault = saved === null
    if (saved) {
      form.contactName = saved.contactName
      form.phone = saved.phone
      form.address = saved.address
      form.note = saved.note ?? ''
    }
  } catch (failure) {
    showError(failure)
  } finally {
    loading.value = false
  }
}, { immediate: true })

async function submit(): Promise<void> {
  const aftersale = props.aftersale
  if (!aftersale || submitting.value) return
  const found = returnAddressErrors(form)
  clearErrors()
  Object.assign(errors, found)
  if (Object.keys(found).length > 0) return
  const confirmed = await confirmAction({
    title: '同意退货',
    message: `同意售后 ${aftersale.aftersaleNo} 的退货，买家会看到这个寄回地址，按它寄回商品。`
      + `${form.saveAsDefault ? '这个地址同时存成默认寄回地址。' : ''}同意后不能改成拒绝。`,
    confirmText: '同意',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const next = await props.api.approve(aftersale, form)
    ElMessage.success('已同意，等买家寄回商品')
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
    :title="aftersale ? `同意退货：${aftersale.aftersaleNo}` : ''"
    width="540px"
    :close-on-click-modal="false"
    @close="emit('close')"
  >
    <div v-loading="loading">
      <p class="approve__hint">
        {{ hasDefault ? '已带出默认寄回地址，这一单要寄到别处可以直接改。' : '还没有默认寄回地址，请填写。' }}
        寄回运费按销售方的规则承担，平台不算运费、不垫付。
      </p>
      <el-form label-width="84px" @submit.prevent>
        <el-form-item label="收件人" required :error="errors.contactName">
          <el-input v-model="form.contactName" class="approve-contact" :maxlength="LIMITS.contactName"
                    placeholder="比如：售后组 王师傅" />
        </el-form-item>
        <el-form-item label="电话" required :error="errors.phone">
          <el-input v-model="form.phone" class="approve-phone" maxlength="20" placeholder="手机或座机" />
        </el-form-item>
        <el-form-item label="地址" required :error="errors.address">
          <el-input v-model="form.address" class="approve-address" type="textarea" :rows="2" :maxlength="LIMITS.address"
                    show-word-limit placeholder="省市区和详细地址" />
        </el-form-item>
        <el-form-item label="说明" :error="errors.note">
          <el-input v-model="form.note" class="approve-note" type="textarea" :rows="2" :maxlength="LIMITS.note"
                    show-word-limit placeholder="可空，买家能看到，比如：请保留原包装和配件" />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="form.saveAsDefault" class="approve-default">存为默认寄回地址</el-checkbox>
        </el-form-item>
      </el-form>
    </div>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="submitting" :disabled="loading" @click="submit">同意退货</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.approve__hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}
</style>
