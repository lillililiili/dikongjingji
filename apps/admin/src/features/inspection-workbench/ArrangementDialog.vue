<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import { confirmAction, showError } from '@/core/ui/feedback'
import { arrangementProblem, certifiedPilotCount } from './inspection-rules'
import {
  ASSIGNMENT_TYPE_LABELS,
  type AssignmentInput,
  type AssignmentType,
  type InspectionWorkbenchApi,
  type ServiceOrder,
} from './inspection-workbench-api'

/**
 * 安排服务团队和设备（putInspectionArrangement）：整体替换，至少一名人员和一台设备，最多 30 项。
 * resourceId 是本机构内部给这个人、这台设备的编号——平台没有人员和设备档案，页面第一次加的时候生成一个，
 * 之后改安排沿用原来的编号。手机号选填，后端加密保存，客户只看到打码的。
 * 人员可以勾“持证飞手”（契约 2.6.0）：App 履约页显示“持证飞手 N 名”，只认这个勾，不看角色写了什么。
 */
const props = defineProps<{
  api: InspectionWorkbenchApi
  order: ServiceOrder
}>()

const emit = defineEmits<{
  saved: [order: ServiceOrder]
  close: []
}>()

interface Row {
  type: AssignmentType
  resourceId: string
  displayName: string
  roleLabel: string
  /** 只填新手机号；留空表示不提供（已有的打码手机号后端不回原文，改安排时要重新填）。 */
  contactMobile: string
  maskedMobile: string | null
  certifiedPilot: boolean
}

function newId(): string {
  return crypto.randomUUID()
}

const rows = reactive<Row[]>(props.order.assignments.length > 0
  ? props.order.assignments.map((item) => ({
    type: item.type,
    resourceId: item.resourceId,
    displayName: item.displayName,
    roleLabel: item.roleLabel ?? '',
    contactMobile: '',
    maskedMobile: item.contactMobileMasked,
    certifiedPilot: item.type === 'TEAM_MEMBER' && item.certifiedPilot === true,
  }))
  : [
      { type: 'TEAM_MEMBER', resourceId: newId(), displayName: '', roleLabel: '项目负责人', contactMobile: '', maskedMobile: null, certifiedPilot: false },
      { type: 'DEVICE', resourceId: newId(), displayName: '', roleLabel: '', contactMobile: '', maskedMobile: null, certifiedPilot: false },
    ])

const busy = ref(false)
let key = newIdempotencyKey()

function add(type: AssignmentType): void {
  rows.push({
    type, resourceId: newId(), displayName: '', roleLabel: '', contactMobile: '', maskedMobile: null, certifiedPilot: false,
  })
}

async function submit(): Promise<void> {
  if (busy.value) return
  const issue = arrangementProblem(rows)
  if (issue) {
    ElMessage.warning(issue)
    return
  }
  const lostMobiles = rows.filter((row) => row.maskedMobile && !row.contactMobile.trim()).length
  const confirmed = await confirmAction({
    title: '保存团队和设备安排',
    message: `客户在 App 的履约页能看到这些名称和角色（手机号打码），以及“持证飞手 ${certifiedPilotCount(rows)} 名”。${lostMobiles > 0
      ? `有 ${lostMobiles} 项原来留了手机号、这次没重填，保存后不再显示手机号。`
      : ''}`,
    confirmText: '保存',
  })
  if (!confirmed) return
  const assignments: AssignmentInput[] = rows.map((row) => ({
    type: row.type,
    resourceId: row.resourceId,
    displayName: row.displayName.trim(),
    roleLabel: row.roleLabel.trim() || null,
    contactMobile: row.contactMobile.trim() || null,
    certifiedPilot: row.type === 'TEAM_MEMBER' && row.certifiedPilot,
  }))
  busy.value = true
  try {
    const order = await props.api.arrange(props.order, assignments, key)
    key = newIdempotencyKey()
    ElMessage.success('已保存团队和设备安排')
    emit('saved', order)
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="true" title="安排人员和设备" width="760px" :close-on-click-modal="false" @close="emit('close')">
    <p class="hint">至少一名人员（或班组）和一台设备。编号是本机构内部用的，页面自动生成，不用填。
      持有民航局无人机驾驶员执照的人员请勾“持证飞手”，客户在履约页看到人数。</p>
    <div class="rows">
      <div v-for="(row, index) in rows" :key="row.resourceId" class="row">
        <el-tag effect="plain" class="row__type">{{ ASSIGNMENT_TYPE_LABELS[row.type] }}</el-tag>
        <el-input v-model="row.displayName" :placeholder="row.type === 'DEVICE' ? '设备名称，如 M350 RTK' : '姓名或班组'"
                  maxlength="120" style="width: 200px" />
        <el-input v-model="row.roleLabel" placeholder="角色（选填）" maxlength="80" style="width: 130px" />
        <el-input v-if="row.type === 'TEAM_MEMBER'" v-model="row.contactMobile"
                  :placeholder="row.maskedMobile ? `原 ${row.maskedMobile}，要保留请重填` : '手机号（选填）'"
                  maxlength="11" style="width: 190px" />
        <el-checkbox v-if="row.type === 'TEAM_MEMBER'" v-model="row.certifiedPilot"
                     :data-testid="`arrangement-certified-${index}`">持证飞手</el-checkbox>
        <el-button v-if="rows.length > 1" link type="danger" @click="rows.splice(index, 1)">删除</el-button>
      </div>
    </div>
    <div class="adds">
      <el-button size="small" :disabled="rows.length >= 30" @click="add('TEAM_MEMBER')">加人员</el-button>
      <el-button size="small" :disabled="rows.length >= 30" @click="add('DEVICE')">加设备</el-button>
    </div>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.row__type {
  width: 84px;
  justify-content: center;
}

.adds {
  margin-top: 12px;
}
</style>
