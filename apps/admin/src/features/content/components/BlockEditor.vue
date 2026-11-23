<script setup lang="ts">
/**
 * 正文块编辑器：按顺序列出每一块，能换类型、上移、下移、删除，最后可以加一块。段落、提示、文末说明写一段文字；
 * 小标题写一行；要点表、步骤、条目写若干条（标题 + 说明）；正文配图上传或粘贴图片地址、写替代文字和图注
 * （App 照画板 ContentArticle 画成 358×190 的图，下面居中图注）。blocks 是父页面表单里的数组，这里直接改。
 */
import MediaField from '@/core/ops/MediaField.vue'

import type { BlockType } from '../content-api'
import {
  BLOCK_TYPE_LABELS,
  BLOCK_TYPES,
  LIMITS,
  blockProblem,
  emptyBlock,
  hasItems,
  hasText,
  newKey,
  type BlockForm,
} from '../content-rules'

const props = defineProps<{
  blocks: BlockForm[]
  disabled?: boolean
}>()

function add(type: BlockType): void {
  props.blocks.push(emptyBlock(type))
}

function remove(index: number): void {
  props.blocks.splice(index, 1)
}

function move(index: number, offset: number): void {
  const target = index + offset
  if (target < 0 || target >= props.blocks.length) return
  const [block] = props.blocks.splice(index, 1)
  if (block) props.blocks.splice(target, 0, block)
}

/** 换类型时保留能保留的：文字块之间保留文字，条目块之间保留条目。 */
function changeType(block: BlockForm, type: BlockType): void {
  const next = emptyBlock(type)
  if (hasText(type) && hasText(block.type)) next.text = block.text
  if (hasItems(type) && hasItems(block.type)) next.items = block.items
  Object.assign(block, { ...next, key: block.key })
}

function itemPlaceholder(type: BlockType): [string, string] {
  if (type === 'FACTS') return ['名称，比如“场景”', '值，比如“物流园区夜间安防”']
  if (type === 'STEPS') return ['这一步做什么', '说明（选填）']
  return ['条目', '说明（选填）']
}
</script>

<template>
  <div class="block-editor">
    <div v-for="(block, index) in blocks" :key="block.key" class="block-editor__block" :data-testid="`block-${index}`">
      <div class="block-editor__head">
        <span class="block-editor__order">{{ index + 1 }}</span>
        <el-select
          :model-value="block.type"
          :disabled="disabled"
          style="width: 130px"
          @update:model-value="(type: BlockType) => changeType(block, type)"
        >
          <el-option v-for="type in BLOCK_TYPES" :key="type" :label="BLOCK_TYPE_LABELS[type]" :value="type" />
        </el-select>
        <span class="block-editor__spacer" />
        <el-button size="small" link :disabled="disabled || index === 0" @click="move(index, -1)">上移</el-button>
        <el-button size="small" link :disabled="disabled || index === blocks.length - 1" @click="move(index, 1)">下移</el-button>
        <el-button size="small" link type="danger" :disabled="disabled" @click="remove(index)">删除</el-button>
      </div>

      <el-input
        v-if="block.type === 'HEADING'"
        v-model="block.text"
        :disabled="disabled"
        :maxlength="LIMITS.heading"
        show-word-limit
        placeholder="小标题，比如“怎么巡”"
      />
      <el-input
        v-else-if="hasText(block.type)"
        v-model="block.text"
        type="textarea"
        :autosize="{ minRows: 2, maxRows: 8 }"
        :disabled="disabled"
        :maxlength="LIMITS.blockText"
        show-word-limit
        :placeholder="block.type === 'TIP' ? '提示，比如“新手建议先在模拟器里练悬停”' : '一段正文，可以换行'"
      />
      <div v-else-if="hasItems(block.type)" class="block-editor__items">
        <div v-for="(item, itemIndex) in block.items" :key="item.key" class="block-editor__item">
          <span class="block-editor__item-order">{{ block.type === 'STEPS' ? itemIndex + 1 : '·' }}</span>
          <el-input v-model="item.title" :disabled="disabled" :maxlength="LIMITS.itemTitle"
                    :placeholder="itemPlaceholder(block.type)[0]" style="width: 220px" />
          <el-input v-model="item.text" :disabled="disabled" :maxlength="LIMITS.itemText"
                    :placeholder="itemPlaceholder(block.type)[1]" />
          <el-button size="small" link type="danger" :disabled="disabled || block.items.length === 1"
                     @click="block.items.splice(itemIndex, 1)">删除</el-button>
        </div>
        <el-button
          size="small"
          :disabled="disabled || block.items.length >= LIMITS.blockItems"
          @click="block.items.push({ key: newKey(), title: '', text: '' })"
        >
          加一条
        </el-button>
      </div>
      <div v-else class="block-editor__image">
        <MediaField v-model="block.imageUrl" kind="IMAGE" :disabled="disabled" />
        <div class="block-editor__image-texts">
          <el-input v-model="block.imageAlt" :disabled="disabled" :maxlength="LIMITS.imageAlt"
                    placeholder="替代文字（必填），比如“无人机在园区上空巡查”" />
          <el-input v-model="block.imageCaption" :disabled="disabled" :maxlength="LIMITS.imageCaption"
                    placeholder="图注（选填），显示在图片下面" />
        </div>
        <span class="block-editor__hint">没有图片也能先保存草稿；上线前必须有图片。</span>
      </div>

      <span v-if="blockProblem(block, index + 1)" class="block-editor__problem">{{ blockProblem(block, index + 1) }}</span>
    </div>

    <div class="block-editor__add">
      <span class="block-editor__hint">加一块：</span>
      <el-button
        v-for="type in BLOCK_TYPES"
        :key="type"
        size="small"
        :disabled="disabled || blocks.length >= LIMITS.blocks"
        :data-testid="`add-block-${type}`"
        @click="add(type)"
      >
        {{ BLOCK_TYPE_LABELS[type] }}
      </el-button>
      <span class="block-editor__hint">{{ blocks.length }} / {{ LIMITS.blocks }} 块</span>
    </div>
  </div>
</template>

<style scoped>
.block-editor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 100%;
}

.block-editor__block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--admin-border);
  border-radius: 10px;
  background: var(--admin-surface-muted);
}

.block-editor__head,
.block-editor__item,
.block-editor__add {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.block-editor__order,
.block-editor__item-order {
  width: 22px;
  font-weight: 600;
  text-align: center;
  color: var(--admin-brand-text);
}

.block-editor__spacer {
  flex: 1;
}

.block-editor__items,
.block-editor__image,
.block-editor__image-texts {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.block-editor__hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.block-editor__problem {
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
