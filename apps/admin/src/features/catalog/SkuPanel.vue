<script setup lang="ts">
import type { FormInstance } from 'element-plus'
import { computed, reactive, ref } from 'vue'

import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'

import { CATALOG_LIMITS as L, type CatalogApi, type OpsProductDetail, type OpsSku } from './catalog-api'
import { skuRules } from './catalog-rules'

const props = defineProps<{
  api: CatalogApi
  detail: OpsProductDetail
}>()

const emit = defineEmits<{
  changed: [detail: OpsProductDetail]
}>()

const formRef = ref<FormInstance>()
const dialog = reactive<{ open: boolean, editing: OpsSku | null, name: string, sortOrder: number | null }>({
  open: false,
  editing: null,
  name: '',
  sortOrder: 0,
})
const busy = ref(false)

const productId = computed(() => props.detail.product.productId)
const full = computed(() => props.detail.skus.length >= L.skus)

function offersOf(skuId: string): number {
  return props.detail.offers.filter((offer) => offer.skuId === skuId && offer.status === 'ACTIVE').length
}

function openCreate(): void {
  dialog.editing = null
  dialog.name = ''
  dialog.sortOrder = (props.detail.skus.length + 1) * 10
  dialog.open = true
  formRef.value?.clearValidate()
}

function openEdit(sku: OpsSku): void {
  dialog.editing = sku
  dialog.name = sku.name
  dialog.sortOrder = sku.sortOrder
  dialog.open = true
  formRef.value?.clearValidate()
}

async function run(action: () => Promise<OpsProductDetail>, done: string): Promise<boolean> {
  busy.value = true
  try {
    emit('changed', await action())
    notifyRecorded(done, '操作记录')
    return true
  } catch (failure) {
    showError(failure)
    return false
  } finally {
    busy.value = false
  }
}

async function submit(): Promise<void> {
  if (!formRef.value || busy.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const editing = dialog.editing
  const draft = { name: dialog.name, sortOrder: dialog.sortOrder }
  const ok = editing
    ? await run(() => props.api.updateSku(productId.value, editing.skuId, draft, editing.status, editing.version),
      `修改规格“${dialog.name.trim()}”`)
    : await run(() => props.api.createSku(productId.value, draft), `新建规格“${dialog.name.trim()}”`)
  if (ok) dialog.open = false
}

async function toggle(sku: OpsSku): Promise<void> {
  const disabling = sku.status === 'ACTIVE'
  const confirmed = await confirmAction({
    title: disabling ? '确认停用规格' : '确认启用规格',
    message: disabling
      ? `停用“${sku.name}”？App 上不再显示这个规格，它的供给也不能再下单（${offersOf(sku.skuId)} 条启用中的供给）。`
      : `启用“${sku.name}”？它的供给要分别启用后才能在 App 上买。`,
    confirmText: disabling ? '停用' : '启用',
    danger: disabling,
  })
  if (!confirmed) return
  await run(() => props.api.updateSku(productId.value, sku.skuId, { name: sku.name, sortOrder: sku.sortOrder },
    disabling ? 'DISABLED' : 'ACTIVE', sku.version), `${disabling ? '停用' : '启用'}规格“${sku.name}”`)
}
</script>

<template>
  <div class="toolbar">
    <span class="hint">一个商品可以有多个规格（最多 {{ L.skus }} 个），例如“标准套装”“教室套装”。价格和库存在“在售供给”里按商家填。</span>
    <el-button type="primary" :disabled="full" @click="openCreate">新建规格</el-button>
  </div>
  <el-table :data="detail.skus" row-key="skuId" border empty-text="还没有规格">
    <el-table-column prop="name" label="规格" min-width="240" />
    <el-table-column prop="sortOrder" label="排序" width="80" />
    <el-table-column label="状态" width="90">
      <template #default="{ row }">
        <el-tag :type="(row as OpsSku).status === 'ACTIVE' ? 'success' : 'info'">
          {{ (row as OpsSku).status === 'ACTIVE' ? '启用' : '停用' }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="启用供给" width="100">
      <template #default="{ row }">{{ offersOf((row as OpsSku).skuId) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="150">
      <template #default="{ row }">
        <el-button link type="primary" :disabled="busy" @click="openEdit(row as OpsSku)">编辑</el-button>
        <el-button link :type="(row as OpsSku).status === 'ACTIVE' ? 'danger' : 'primary'" :disabled="busy"
                   @click="toggle(row as OpsSku)">
          {{ (row as OpsSku).status === 'ACTIVE' ? '停用' : '启用' }}
        </el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog v-model="dialog.open" :title="dialog.editing ? '修改规格' : '新建规格'" width="480px"
             :close-on-click-modal="false">
    <el-form ref="formRef" :model="dialog" :rules="skuRules" label-width="80px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="dialog.name" :maxlength="L.skuName" show-word-limit placeholder="如：教室套装（6 台 + 教师端）" />
      </el-form-item>
      <el-form-item label="排序">
        <el-input-number v-model="dialog.sortOrder" :min="0" :max="L.sortOrder" :step="10" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog.open = false">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
