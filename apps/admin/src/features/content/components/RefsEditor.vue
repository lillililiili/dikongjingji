<script setup lang="ts">
/**
 * “内容中提到”：类型 + 稳定 ID + 名称（录入时的快照）。App 按类型和 ID 到各自模块读真实的名称、价格和状态，读不到的
 * 不显示，所以这里只查 ID 格式。视频的第一项是视频挂的入口。refs 是父页面表单里的数组，这里直接改。
 */
import type { RefType } from '../content-api'
import { LIMITS, REF_TYPE_LABELS, isUuid, newKey, refsProblem, type RefForm } from '../content-rules'

const props = defineProps<{
  refs: RefForm[]
  disabled?: boolean
  firstHint?: string
}>()

const TYPES = Object.keys(REF_TYPE_LABELS) as RefType[]

function add(): void {
  props.refs.push({ key: newKey(), type: 'PRODUCT', refId: '', title: '' })
}

function move(index: number, offset: number): void {
  const target = index + offset
  if (target < 0 || target >= props.refs.length) return
  const [ref] = props.refs.splice(index, 1)
  if (ref) props.refs.splice(target, 0, ref)
}
</script>

<template>
  <div class="refs-editor">
    <div v-for="(ref, index) in refs" :key="ref.key" class="refs-editor__row">
      <el-select v-model="ref.type" :disabled="disabled" style="width: 120px">
        <el-option v-for="type in TYPES" :key="type" :label="REF_TYPE_LABELS[type]" :value="type" />
      </el-select>
      <el-input
        v-model="ref.refId"
        :disabled="disabled"
        placeholder="稳定 ID（UUID）"
        style="width: 320px"
        :class="{ 'refs-editor__bad': ref.refId.trim() !== '' && !isUuid(ref.refId) }"
      />
      <el-input v-model="ref.title" :disabled="disabled" :maxlength="LIMITS.refTitle" placeholder="名称" style="width: 220px" />
      <el-button size="small" link :disabled="disabled || index === 0" @click="move(index, -1)">上移</el-button>
      <el-button size="small" link type="danger" :disabled="disabled" @click="refs.splice(index, 1)">删除</el-button>
      <span v-if="index === 0 && firstHint" class="refs-editor__hint">{{ firstHint }}</span>
    </div>
    <div class="refs-editor__row">
      <el-button size="small" :disabled="disabled || refs.length >= LIMITS.refs" @click="add">加一项</el-button>
      <span class="refs-editor__hint">最多 {{ LIMITS.refs }} 项；App 读不到的（下架、本城不卖）自动不显示。</span>
    </div>
    <span v-if="refsProblem(refs)" class="refs-editor__problem">{{ refsProblem(refs) }}</span>
  </div>
</template>

<style scoped>
.refs-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.refs-editor__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.refs-editor__hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.refs-editor__problem {
  font-size: 12px;
  color: var(--el-color-danger);
}

.refs-editor__bad :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}
</style>
