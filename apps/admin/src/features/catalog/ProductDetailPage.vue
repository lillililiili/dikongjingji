<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import {
  ACTION_LABELS,
  categoryLabel,
  createCatalogApi,
  saleModeLabel,
  type OpsProductDetail,
} from './catalog-api'
import OfferPanel from './OfferPanel.vue'
import ProductInfoPanel from './ProductInfoPanel.vue'
import ProductPublishBar from './ProductPublishBar.vue'
import SkuPanel from './SkuPanel.vue'

const route = useRoute()
const router = useRouter()
const api = createCatalogApi(http)
const detail = shallowRef<OpsProductDetail | null>(null)
const loading = ref(false)
const error = ref('')
const tab = ref('info')

const productId = computed(() => String(route.params.productId ?? ''))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    detail.value = await api.detail(productId.value)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function changed(next: OpsProductDetail): void {
  detail.value = next
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="detail?.product.name ?? '商品详情'"
    :description="detail ? `${categoryLabel(detail.product.category)} · ${saleModeLabel(detail.product.saleMode)} · 更新于 ${formatDateTime(detail.product.updatedAt)}` : undefined"
    traced
  >
    <template #actions>
      <ProductPublishBar v-if="detail" :api="api" :detail="detail" @changed="changed" />
      <el-button @click="router.push({ name: 'catalog-products' })">返回列表</el-button>
    </template>

    <div v-loading="loading">
      <el-result v-if="error && !detail" icon="error" :title="error">
        <template #extra>
          <el-button type="primary" @click="load">重新加载</el-button>
        </template>
      </el-result>

      <el-tabs v-if="detail" v-model="tab">
        <el-tab-pane label="基本资料" name="info">
          <ProductInfoPanel :api="api" :detail="detail" @changed="changed" />
        </el-tab-pane>
        <el-tab-pane :label="`规格（${detail.skus.length}）`" name="skus">
          <SkuPanel :api="api" :detail="detail" @changed="changed" />
        </el-tab-pane>
        <el-tab-pane :label="`在售供给（${detail.offers.length}）`" name="offers">
          <OfferPanel :api="api" :detail="detail" @changed="changed" />
        </el-tab-pane>
        <el-tab-pane label="操作记录" name="operations">
          <el-empty v-if="detail.operations.length === 0" description="还没有操作记录" />
          <el-timeline v-else>
            <el-timeline-item
              v-for="operation in detail.operations"
              :key="operation.operationId"
              :timestamp="`${formatDateTime(operation.occurredAt)} · ${operation.operatorName}`"
              placement="top"
            >
              <strong>{{ ACTION_LABELS[operation.action] }}</strong>
              <p class="operation__detail">{{ operation.detail }}</p>
            </el-timeline-item>
          </el-timeline>
          <p class="hint">显示最近 50 条。</p>
        </el-tab-pane>
      </el-tabs>
    </div>
  </PageSection>
</template>

<style scoped>
.operation__detail {
  margin: 4px 0 0;
  color: var(--admin-text-secondary);
}

.hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
