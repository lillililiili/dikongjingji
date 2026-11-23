<script setup lang="ts">
/**
 * 一组按顺序的条目（服务流程、交付物、价格与取消……）：每行几个文字框，能加、删、上移、下移，最多 max 行。
 * v-model 是对象数组（字段都是字符串）；不在 fields 里的键（比如已有条目的编码）原样保留，新行用 newRow() 生成。
 */
import { charCount } from '@/core/ui/format'

export interface RowField {
  key: string
  label: string
  max?: number
  placeholder?: string
  /** 宽度占比（flex-grow），默认 1。 */
  grow?: number
}

const props = withDefaults(defineProps<{
  modelValue: Record<string, string>[]
  fields: readonly RowField[]
  max: number
  newRow: () => Record<string, string>
  addLabel?: string
  disabled?: boolean
}>(), { addLabel: '加一项', disabled: false })

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, string>[]]
}>()

function update(index: number, key: string, value: string): void {
  emit('update:modelValue', props.modelValue.map((row, position) => position === index ? { ...row, [key]: value } : row))
}

function add(): void {
  if (props.modelValue.length >= props.max) return
  emit('update:modelValue', [...props.modelValue, props.newRow()])
}

function remove(index: number): void {
  emit('update:modelValue', props.modelValue.filter((_, position) => position !== index))
}

function move(index: number, offset: number): void {
  const target = index + offset
  if (target < 0 || target >= props.modelValue.length) return
  const next = [...props.modelValue]
  ;[next[index], next[target]] = [next[target]!, next[index]!]
  emit('update:modelValue', next)
}

function tooLong(field: RowField, value: string | undefined): boolean {
  return field.max !== undefined && charCount((value ?? '').trim()) > field.max
}
</script>

<template>
  <div class="row-list">
    <div v-for="(row, index) in modelValue" :key="index" class="row-list__row">
      <span class="row-list__index">{{ index + 1 }}</span>
      <el-input
        v-for="field in fields"
        :key="field.key"
        :model-value="row[field.key] ?? ''"
        :placeholder="field.placeholder ?? field.label"
        :disabled="disabled"
        :class="{ 'row-list__input--error': tooLong(field, row[field.key]) }"
        :style="{ flexGrow: field.grow ?? 1 }"
        class="row-list__input"
        size="small"
        @update:model-value="(value: string) => update(index, field.key, value)"
      />
      <el-button size="small" link :disabled="disabled || index === 0" @click="move(index, -1)">上移</el-button>
      <el-button size="small" link :disabled="disabled || index === modelValue.length - 1" @click="move(index, 1)">下移</el-button>
      <el-button size="small" link type="danger" :disabled="disabled" @click="remove(index)">删除</el-button>
    </div>
    <div class="row-list__footer">
      <el-button size="small" :disabled="disabled || modelValue.length >= max" @click="add">{{ addLabel }}</el-button>
      <span class="row-list__count">{{ modelValue.length }} / {{ max }}</span>
    </div>
  </div>
</template>

<style scoped>
.row-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.row-list__row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.row-list__index {
  flex-shrink: 0;
  width: 20px;
  font-size: 12px;
  color: var(--admin-text-secondary);
  text-align: right;
}

.row-list__input {
  flex-basis: 0;
  min-width: 0;
}

.row-list__input--error :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}

.row-list__footer {
  display: flex;
  align-items: center;
  gap: 8px;
}

.row-list__count {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
