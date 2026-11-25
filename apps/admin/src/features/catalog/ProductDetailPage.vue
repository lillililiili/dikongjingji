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
  MERCHANT_CATALOG_BASE,
  OPS_CATALOG_BASE,
  saleModeLabel,
  type OpsProductDetail,
} from './catalog-api'
import OfferPanel from './OfferPanel.vue'
import ProductInfoPanel from './ProductInfoPanel.vue'
import ProductPublishBar from './ProductPublishBar.vue'
import { confirmAndRemoveDraft } from './remove-draft'
import SkuPanel from './SkuPanel.vue'
import { createTradeTermsApi } from './trade-terms-api'
import TradeTermsPanel from './TradeTermsPanel.vue'

/** merchant：商家工作台“我的商品”的详情（接口前缀不同；交易条款由平台维护，这里不显示）。 */
const props = defineProps<{
  merchant?: boolean
}>()

const route = useRoute()
const router = useRouter()
const api = createCatalogApi(http, props.merchant ? MERCHANT_CATALOG_BASE : OPS_CATALOG_BASE)
const termsApi = createTradeTermsApi(http)
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

const removing = ref(false)

async function remove(): Promise<void> {
  if (!detail.value || removing.value) return
  removing.value = true
  try {
    if (await confirmAndRemoveDraft(api, detail.value.product)) await router.replace({ name: 'workbench-products' })
  } finally {
    removing.value = false
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
    :description="detail ? `${categoryLabel(detail.product.category)} · ${saleModeLabel(detail.product.saleMode)}${!merchant && detail.product.ownerName ? ` · 建自商家 ${detail.product.ownerName}` : ''} · 更新于 ${formatDateTime(detail.product.updatedAt)}` : undefined"
    traced
  >
    <template #actions>
      <ProductPublishBar v-if="detail" :api="api" :detail="detail" @changed="changed" />
      <el-button v-if="merchant && detail?.product.status === 'DRAFT'" type="danger" plain :loading="removing"
                 data-testid="product-remove" @click="remove">删除草稿</el-button>
      <el-button @click="router.push({ name: merchant ? 'workbench-products' : 'catalog-products' })">返回列表</el-button>
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
          <OfferPanel :api="api" :detail="detail" :merchant="merchant" @changed="changed" />
        </el-tab-pane>
        <el-tab-pane v-if="!merchant" label="交易条款" name="terms" lazy>
          <!-- 交易条款的改动记在操作记录里，保存后重新读一次详情，操作记录页签跟着更新。 -->
          <TradeTermsPanel :api="termsApi" :product-id="detail.product.productId" :offers="detail.offers"
                           @changed="load" />
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
