<script setup lang="ts">
/**
 * 改动记录（留痕）：谁、什么时候、对什么做了什么。数据由各块的 changes 接口给（新的在前），这里只负责显示；
 * subjectLabels / actionLabels 把编码翻成中文。
 */
import { formatDateTime } from '@/core/ui/format'

import { actionLabel, type OpsChange } from './ops-common'

withDefaults(defineProps<{
  changes: OpsChange[]
  error?: string
  loading?: boolean
  subjectLabels?: Readonly<Record<string, string>>
  actionLabels?: Readonly<Record<string, string>>
}>(), { loading: false, subjectLabels: () => ({}), actionLabels: () => ({}) })
</script>

<template>
  <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon />
  <el-table v-else v-loading="loading" :data="changes" row-key="changeId" size="small" border empty-text="还没有改动记录">
    <el-table-column label="时间" width="150">
      <template #default="{ row }">{{ formatDateTime((row as OpsChange).changedAt) }}</template>
    </el-table-column>
    <el-table-column label="对象" width="100">
      <template #default="{ row }">{{ subjectLabels[(row as OpsChange).subjectType] ?? (row as OpsChange).subjectType }}</template>
    </el-table-column>
    <el-table-column label="动作" width="100">
      <template #default="{ row }">{{ actionLabel((row as OpsChange).action, actionLabels) }}</template>
    </el-table-column>
    <el-table-column prop="summary" label="说明" min-width="220" />
    <el-table-column prop="operatorName" label="操作人" width="140" />
  </el-table>
</template>
