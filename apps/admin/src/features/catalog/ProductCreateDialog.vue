<script setup lang="ts">
import type { FormInstance } from 'element-plus'
import { reactive, ref, watch } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'

import {
  CATALOG_LIMITS,
  CATEGORY_OPTIONS,
  categoryLabel,
  SALE_MODE_OPTIONS,
  saleModeLabel,
  type CatalogApi,
  type MallCategory,
  type MallSaleMode,
  type OpsProductDetail,
} from './catalog-api'
import { createProductRules } from './catalog-rules'

const props = defineProps<{
  api: CatalogApi
  open: boolean
}>()

const emit = defineEmits<{
  close: []
  created: [detail: OpsProductDetail]
}>()

const formRef = ref<FormInstance>()
const form = reactive<{ category: MallCategory, name: string, saleMode: MallSaleMode }>({
  category: 'ROBOT',
  name: '',
  saleMode: 'BUY',
})
const submitting = ref(false)
// 一次打开弹窗用一个幂等键：网络超时后再点新建不会建出两个商品。
let idempotencyKey = newIdempotencyKey()

watch(() => props.open, (open) => {
  if (!open) return
  idempotencyKey = newIdempotencyKey()
  form.category = 'ROBOT'
  form.name = ''
  form.saleMode = 'BUY'
  formRef.value?.clearValidate()
})

async function submit(): Promise<void> {
  if (!formRef.value || submitting.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const confirmed = await confirmAction({
    title: '确认新建商品',
    message: `新建${categoryLabel(form.category)}商品“${form.name.trim()}”，交易方式“${saleModeLabel(form.saleMode)}”`
      + '（建好后不能改）？新建的商品是草稿，App 上看不到。',
    confirmText: '新建',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const detail = await props.api.create({ ...form }, idempotencyKey)
    notifyRecorded(`新建商品“${detail.product.name}”`, '商品详情 · 操作记录')
    emit('created', detail)
  } catch (failure) {
    showError(failure)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="open" title="新建商品" width="520px" :close-on-click-modal="false" @close="emit('close')">
    <el-form ref="formRef" :model="form" :rules="createProductRules" label-width="96px">
      <el-form-item label="类目" prop="category">
        <el-select v-model="form.category" style="width: 100%">
          <el-option v-for="option in CATEGORY_OPTIONS" :key="option.value" :label="option.label"
                     :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="商品名称" prop="name">
        <el-input v-model="form.name" :maxlength="CATALOG_LIMITS.name" show-word-limit placeholder="App 上显示的名称" />
      </el-form-item>
      <el-form-item label="交易方式" prop="saleMode">
        <el-radio-group v-model="form.saleMode">
          <el-radio v-for="option in SALE_MODE_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio>
        </el-radio-group>
        <div class="field-hint">标价购买能进购物车；租赁按天或半天计价；询价不标价、由商家报价。建好后不能改。</div>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">新建</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.field-hint {
  width: 100%;
  font-size: 12px;
  line-height: 1.6;
  color: var(--admin-text-secondary);
}
</style>
