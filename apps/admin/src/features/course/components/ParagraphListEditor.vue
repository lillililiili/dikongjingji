<script setup lang="ts">
/**
 * 图文介绍：按顺序的几段文字，能加、删、上移、下移，最多 max 段，每段最多 maxChars 字。v-model 是字符串数组。
 */
const props = withDefaults(defineProps<{
  modelValue: string[]
  max: number
  maxChars: number
  disabled?: boolean
}>(), { disabled: false })

const emit = defineEmits<{
  'update:modelValue': [value: string[]]
}>()

function update(index: number, value: string): void {
  emit('update:modelValue', props.modelValue.map((paragraph, position) => position === index ? value : paragraph))
}

function add(): void {
  if (props.modelValue.length >= props.max) return
  emit('update:modelValue', [...props.modelValue, ''])
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
</script>

<template>
  <div class="paragraphs">
    <!-- 段落没有稳定 ID，按位置渲染；上下移动只是交换文字。 -->
    <div v-for="(paragraph, index) in modelValue" :key="index" class="paragraphs__row">
      <span class="paragraphs__index">{{ index + 1 }}</span>
      <el-input
        :model-value="paragraph"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 6 }"
        :maxlength="maxChars"
        show-word-limit
        :disabled="disabled"
        placeholder="一段文字"
        class="paragraphs__input"
        @update:model-value="(value: string) => update(index, value)"
      />
      <div class="paragraphs__actions">
        <el-button size="small" link :disabled="disabled || index === 0" @click="move(index, -1)">上移</el-button>
        <el-button size="small" link :disabled="disabled || index === modelValue.length - 1" @click="move(index, 1)">下移</el-button>
        <el-button size="small" link type="danger" :disabled="disabled" @click="remove(index)">删除</el-button>
      </div>
    </div>
    <div class="paragraphs__footer">
      <el-button size="small" :disabled="disabled || modelValue.length >= max" @click="add">加一段</el-button>
      <span class="paragraphs__count">{{ modelValue.length }} / {{ max }} 段；空着的段落不保存</span>
    </div>
  </div>
</template>

<style scoped>
.paragraphs {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}

.paragraphs__row {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}

.paragraphs__index {
  flex-shrink: 0;
  width: 20px;
  padding-top: 6px;
  font-size: 12px;
  color: var(--admin-text-secondary);
  text-align: right;
}

.paragraphs__input {
  flex: 1;
  min-width: 0;
}

.paragraphs__actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.paragraphs__actions .el-button + .el-button {
  margin-left: 0;
}

.paragraphs__footer {
  display: flex;
  align-items: center;
  gap: 8px;
}

.paragraphs__count {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
