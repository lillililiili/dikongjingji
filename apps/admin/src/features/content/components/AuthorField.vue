<script setup lang="ts">
/** 作者 / 发布方：服务商、商家选机构，平台写署名。allowPlatform 为 false 时（案例）不能选平台。 */
import type { AuthorKind } from '../content-api'
import { AUTHOR_KIND_LABELS, type AuthorForm } from '../content-rules'

import OrganizationPicker from './OrganizationPicker.vue'

const props = withDefaults(defineProps<{
  modelValue: AuthorForm
  allowPlatform?: boolean
  disabled?: boolean
}>(), { allowPlatform: true, disabled: false })

const emit = defineEmits<{
  'update:modelValue': [value: AuthorForm]
}>()

const KINDS: readonly AuthorKind[] = ['PROVIDER', 'MERCHANT', 'PLATFORM']

function update(patch: Partial<AuthorForm>): void {
  emit('update:modelValue', { ...props.modelValue, ...patch })
}
</script>

<template>
  <div class="author-field">
    <el-radio-group :model-value="modelValue.kind" :disabled="disabled" @update:model-value="(kind) => update({ kind: kind as AuthorKind })">
      <el-radio-button
        v-for="kind in KINDS"
        :key="kind"
        :value="kind"
        :disabled="kind === 'PLATFORM' && !allowPlatform"
      >
        {{ AUTHOR_KIND_LABELS[kind] }}
      </el-radio-button>
    </el-radio-group>
    <el-input
      v-if="modelValue.kind === 'PLATFORM'"
      :model-value="modelValue.name"
      :disabled="disabled"
      maxlength="160"
      placeholder="平台署名，比如“东营城市运营中心”"
      style="width: 320px"
      @update:model-value="(name: string) => update({ name })"
    />
    <OrganizationPicker
      v-else
      :model-value="modelValue.organizationId"
      :label="modelValue.organizationName"
      :disabled="disabled"
      @update:model-value="(organizationId: string) => update({ organizationId })"
      @picked="(picked) => update({ organizationId: picked?.organizationId ?? '', organizationName: picked?.name ?? '' })"
    />
    <span v-if="modelValue.kind === 'MERCHANT'" class="author-field__hint">商家发布的内容 App 上一律标“广告”。</span>
  </div>
</template>

<style scoped>
.author-field {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
}

.author-field__hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
