<script setup lang="ts">
import { computed, ref, watch } from 'vue'

import { ApiRequestError } from '@/core/http/api-error'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'

import {
  BLOCKER_LABELS,
  blockersFromDetails,
  productStatus,
  type CatalogApi,
  type OpsProductDetail,
  type OpsPublishBlocker,
} from './catalog-api'

/** 页头的状态和上架 / 下架：上架前缺必填时按钮禁用，旁边列出缺什么。 */
const props = defineProps<{
  api: CatalogApi
  detail: OpsProductDetail
}>()

const emit = defineEmits<{
  changed: [detail: OpsProductDetail]
}>()

const busy = ref(false)
// 后端回 OPS_PRODUCT_INCOMPLETE 时以后端说的为准（详情可能刚被别人改过）。
const serverBlockers = ref<OpsPublishBlocker[] | null>(null)

watch(() => props.detail, () => {
  serverBlockers.value = null
})

const status = computed(() => productStatus(props.detail.product.status))
const published = computed(() => props.detail.product.status === 'PUBLISHED')
const blockers = computed(() => serverBlockers.value ?? props.detail.publishBlockers)

async function change(): Promise<void> {
  if (busy.value) return
  const publishing = !published.value
  if (publishing && blockers.value.length > 0) return
  const confirmed = await confirmAction({
    title: publishing ? '确认上架' : '确认下架',
    message: publishing
      ? `上架“${props.detail.product.name}”？App 商城里马上能看到（启用了供给的城市）。`
      : `下架“${props.detail.product.name}”？App 上不再显示，购物车里的也会失效。`,
    confirmText: publishing ? '上架' : '下架',
    danger: !publishing,
  })
  if (!confirmed) return
  busy.value = true
  try {
    emit('changed', await props.api.changeStatus(props.detail.product.productId,
      publishing ? 'PUBLISH' : 'UNPUBLISH', props.detail.product.version))
    notifyRecorded(publishing ? '上架' : '下架', '操作记录')
  } catch (failure) {
    if (failure instanceof ApiRequestError && failure.code === 'OPS_PRODUCT_INCOMPLETE') {
      serverBlockers.value = blockersFromDetails(failure.details)
    }
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="publish">
    <el-tag :type="status.tag" size="large" data-testid="product-status">{{ status.label }}</el-tag>
    <template v-if="!published && blockers.length > 0">
      <span class="publish__blockers" data-testid="publish-blockers">
        上架还缺：{{ blockers.map((blocker) => BLOCKER_LABELS[blocker]).join('、') }}
      </span>
    </template>
    <el-button
      :type="published ? 'danger' : 'primary'"
      :plain="published"
      :loading="busy"
      :disabled="!published && blockers.length > 0"
      data-testid="publish-button"
      @click="change"
    >
      {{ published ? '下架' : '上架' }}
    </el-button>
  </div>
</template>

<style scoped>
.publish {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.publish__blockers {
  font-size: 13px;
  color: var(--el-color-danger);
}
</style>
