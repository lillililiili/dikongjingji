<script setup lang="ts">
/**
 * 巡检类目：新建（编码定了不改）、改名称 / 副标题 / 排序、启用 / 停用、看改动记录。
 * 停用前类目下（含子类目）不能有上架的服务，后端会拒绝并说明。
 */
import type { FormInstance } from 'element-plus'
import { computed, reactive, ref } from 'vue'

import ChangeLogTable from '@/core/ops/ChangeLogTable.vue'
import type { OpsChange } from '@/core/ops/ops-common'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'

import type { InspectionCatalogApi, OpsInspectionCategory } from './inspection-catalog-api'
import { CHANGE_ACTION_LABELS, CHANGE_SUBJECT_LABELS, categoryRules, LIMITS } from './inspection-catalog-rules'

const props = defineProps<{
  api: InspectionCatalogApi
  categories: OpsInspectionCategory[]
}>()

const emit = defineEmits<{
  changed: []
}>()

const formRef = ref<FormInstance>()
const dialog = reactive({ open: false, editing: null as OpsInspectionCategory | null })
const form = reactive({ code: '', name: '', subtitle: '', parentCode: '', sortOrder: 0 })
const busy = ref(false)
const history = reactive({ open: false, title: '', loading: false, changes: [] as OpsChange[] })

const topLevel = computed(() => props.categories.filter((category) => !category.parentCode))

function openCreate(): void {
  dialog.editing = null
  Object.assign(form, { code: '', name: '', subtitle: '', parentCode: '', sortOrder: 0 })
  dialog.open = true
}

function openEdit(category: OpsInspectionCategory): void {
  dialog.editing = category
  Object.assign(form, { code: category.code, name: category.name, subtitle: category.subtitle ?? '',
    parentCode: category.parentCode ?? '', sortOrder: category.sortOrder })
  dialog.open = true
}

async function save(): Promise<void> {
  if (!formRef.value || busy.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const editing = dialog.editing
  const confirmed = await confirmAction({
    title: editing ? '确认修改类目' : '确认新建类目',
    message: editing
      ? `改名后，这个类目下服务卡片和详情上的类目名称会跟着改，App 上马上生效。`
      : `新建类目 ${form.code.trim().toUpperCase()}（${form.name.trim()}），编码以后不能改。`,
    confirmText: '保存',
  })
  if (!confirmed) return
  busy.value = true
  try {
    const subtitle = form.subtitle.trim() === '' ? null : form.subtitle.trim()
    if (editing) {
      await props.api.updateCategory(editing.code, form.name.trim(), subtitle, form.sortOrder, editing.version)
      notifyRecorded('修改类目', '类目 → 改动记录')
    } else {
      await props.api.createCategory({ code: form.code.trim().toUpperCase(), name: form.name.trim(), subtitle,
        parentCode: form.parentCode || null, sortOrder: form.sortOrder })
      notifyRecorded('新建类目', '类目 → 改动记录')
    }
    dialog.open = false
    emit('changed')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

async function toggle(category: OpsInspectionCategory): Promise<void> {
  const enabling = category.status !== 'ACTIVE'
  const confirmed = await confirmAction({
    title: enabling ? '确认启用类目' : '确认停用类目',
    message: enabling
      ? `启用后 App 巡检入口和目录里会出现“${category.name}”。`
      : `停用后 App 不再列出“${category.name}”，新服务也不能选它；类目下还有上架服务时不能停用。`,
    confirmText: enabling ? '启用' : '停用',
    danger: !enabling,
  })
  if (!confirmed) return
  try {
    await props.api.changeCategoryStatus(category.code, enabling ? 'ACTIVE' : 'INACTIVE', category.version)
    notifyRecorded(enabling ? '启用类目' : '停用类目', '类目 → 改动记录')
    emit('changed')
  } catch (failure) {
    showError(failure)
  }
}

async function openHistory(category: OpsInspectionCategory): Promise<void> {
  Object.assign(history, { open: true, title: `${category.name}（${category.code}）`, loading: true, changes: [] })
  try {
    history.changes = await props.api.categoryChanges(category.code)
  } catch (failure) {
    showError(failure)
  } finally {
    history.loading = false
  }
}
</script>

<template>
  <div class="toolbar">
    <span class="hint">App 按类目编码画场景图标、做筛选；一级类目在前，子类目只有一层。</span>
    <el-button type="primary" plain @click="openCreate">新建类目</el-button>
  </div>
  <el-table :data="categories" row-key="code" border stripe empty-text="还没有类目">
    <el-table-column label="类目" min-width="180">
      <template #default="{ row }">
        <span :class="{ child: (row as OpsInspectionCategory).parentCode }">{{ (row as OpsInspectionCategory).name }}</span>
        <div class="sub">{{ (row as OpsInspectionCategory).code }}</div>
      </template>
    </el-table-column>
    <el-table-column label="副标题" min-width="160">
      <template #default="{ row }">{{ (row as OpsInspectionCategory).subtitle ?? '—' }}</template>
    </el-table-column>
    <el-table-column prop="sortOrder" label="排序" width="70" />
    <el-table-column label="上架服务" width="90">
      <template #default="{ row }">{{ (row as OpsInspectionCategory).activeServiceCount }}</template>
    </el-table-column>
    <el-table-column label="状态" width="80">
      <template #default="{ row }">
        <el-tag :type="(row as OpsInspectionCategory).status === 'ACTIVE' ? 'success' : 'info'">
          {{ (row as OpsInspectionCategory).status === 'ACTIVE' ? '启用' : '停用' }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="最后修改" width="150">
      <template #default="{ row }">{{ formatDateTime((row as OpsInspectionCategory).updatedAt) }}</template>
    </el-table-column>
    <el-table-column label="操作" width="190">
      <template #default="{ row }">
        <el-button link type="primary" @click="openEdit(row as OpsInspectionCategory)">修改</el-button>
        <el-button link :type="(row as OpsInspectionCategory).status === 'ACTIVE' ? 'danger' : 'success'"
                   @click="toggle(row as OpsInspectionCategory)">
          {{ (row as OpsInspectionCategory).status === 'ACTIVE' ? '停用' : '启用' }}
        </el-button>
        <el-button link @click="openHistory(row as OpsInspectionCategory)">改动记录</el-button>
      </template>
    </el-table-column>
  </el-table>

  <el-dialog v-model="dialog.open" :title="dialog.editing ? '修改类目' : '新建类目'" width="480px">
    <el-form ref="formRef" :model="form" :rules="categoryRules" label-width="80px">
      <el-form-item label="编码" prop="code">
        <el-input v-model="form.code" :disabled="!!dialog.editing" placeholder="如 BRIDGE" />
      </el-form-item>
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" :maxlength="LIMITS.categoryName" show-word-limit />
      </el-form-item>
      <el-form-item label="副标题" prop="subtitle">
        <el-input v-model="form.subtitle" :maxlength="LIMITS.categorySubtitle" show-word-limit />
      </el-form-item>
      <el-form-item v-if="!dialog.editing" label="上级">
        <el-select v-model="form.parentCode" clearable placeholder="不选是一级类目" style="width: 100%">
          <el-option v-for="category in topLevel" :key="category.code" :value="category.code" :label="category.name" />
        </el-select>
      </el-form-item>
      <el-form-item label="排序">
        <el-input-number v-model="form.sortOrder" :min="0" :max="9999" />
        <span class="hint">　小的在前</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialog.open = false">取消</el-button>
      <el-button type="primary" :loading="busy" @click="save">保存</el-button>
    </template>
  </el-dialog>

  <el-drawer v-model="history.open" :title="`改动记录 · ${history.title}`" size="640px">
    <ChangeLogTable :changes="history.changes" :loading="history.loading" :subject-labels="CHANGE_SUBJECT_LABELS"
                    :action-labels="CHANGE_ACTION_LABELS" />
  </el-drawer>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.hint,
.sub {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.child {
  padding-left: 16px;
}
</style>
