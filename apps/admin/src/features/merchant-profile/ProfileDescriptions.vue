<script setup lang="ts">
/**
 * 一份商家资料的各项（经营主体、简介、可售说明、授权有效期、品类、默认开票方、默认售后责任方）。
 * 传了 compare 时，和它不一样的项标“有改动”（运营审核时对照现在对外的资料）。
 */
import { fieldText, PROFILE_FIELD_LABELS, PROFILE_FIELDS, type MallMerchantDraft } from './merchant-profile-api'

const props = defineProps<{
  value: MallMerchantDraft
  compare?: MallMerchantDraft | null
}>()

function changed(field: keyof MallMerchantDraft): boolean {
  if (props.compare === undefined) return false
  return (props.value[field] ?? null) !== (props.compare?.[field] ?? null)
}
</script>

<template>
  <el-descriptions :column="1" border size="small">
    <el-descriptions-item v-for="field in PROFILE_FIELDS" :key="field" :label="PROFILE_FIELD_LABELS[field]">
      <span class="profile-value">{{ fieldText(value[field]) }}</span>
      <el-tag v-if="changed(field)" size="small" type="warning" class="profile-changed">有改动</el-tag>
    </el-descriptions-item>
  </el-descriptions>
</template>

<style scoped>
.profile-value {
  white-space: pre-wrap;
}

.profile-changed {
  margin-left: 8px;
}
</style>
