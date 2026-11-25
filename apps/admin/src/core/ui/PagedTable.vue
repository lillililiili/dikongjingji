<script setup lang="ts" generic="T extends object">
import DataOriginColumn from '@/core/ops/DataOriginColumn.vue'
/**
 * 分页表格：el-table + el-pagination，带加载中、出错重试和空状态。列由默认插槽给出（el-table-column）。
 * 数据和页码来自 usePagedList，这个组件只负责显示和转发翻页。
 */
defineProps<{
  items: T[]
  total: number
  page: number
  size: number
  loading: boolean
  error: string
  rowKey: string
  originResource?: string
  emptyText?: string
}>()

const emit = defineEmits<{
  'change-page': [page: number]
  'change-size': [size: number]
  retry: []
}>()
</script>

<template>
  <div class="paged-table">
    <el-alert
      v-if="error"
      class="paged-table__error"
      type="error"
      :title="error"
      :closable="false"
      show-icon
    >
      <el-button size="small" @click="emit('retry')">重新加载</el-button>
    </el-alert>
    <el-table
      v-loading="loading"
      :data="items"
      :row-key="rowKey"
      :empty-text="error ? '加载失败' : (emptyText ?? '暂无数据')"
      border
      stripe
    >
      <slot />
      <DataOriginColumn v-if="originResource" :resource="originResource" :items="items" :row-key="rowKey" />
    </el-table>
    <div v-if="!error" class="paged-table__footer">
      <el-pagination
        :current-page="page"
        :page-size="size"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next"
        background
        @current-change="(value: number) => emit('change-page', value)"
        @size-change="(value: number) => emit('change-size', value)"
      />
    </div>
  </div>
</template>

<style scoped>
.paged-table__error {
  margin-bottom: 12px;
}

.paged-table__footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
