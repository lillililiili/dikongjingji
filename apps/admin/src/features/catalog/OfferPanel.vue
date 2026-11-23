<script setup lang="ts">
import { computed, ref } from 'vue'

import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatYuan } from '@/core/ui/money'

import {
  merchantKindLabel,
  priceUnitLabel,
  type CatalogApi,
  type OpsOffer,
  type OpsProductDetail,
} from './catalog-api'
import OfferDialog from './OfferDialog.vue'
import { stockLabel } from './offer-form'

const props = defineProps<{
  api: CatalogApi
  detail: OpsProductDetail
}>()

const emit = defineEmits<{
  changed: [detail: OpsProductDetail]
}>()

const target = ref<OpsOffer | 'new' | null>(null)
const busy = ref(false)
const noSku = computed(() => props.detail.skus.length === 0)

function changed(detail: OpsProductDetail): void {
  target.value = null
  emit('changed', detail)
}

async function toggle(offer: OpsOffer): Promise<void> {
  const activating = offer.status !== 'ACTIVE'
  const where = offer.cityName ?? '全国'
  const confirmed = await confirmAction({
    title: activating ? '确认启用供给' : '确认停用供给',
    message: activating
      ? `启用“${offer.merchantName} · ${offer.skuName} · ${where}”？商品已上架时，App 上马上能看到、能下单。`
      : `停用“${offer.merchantName} · ${offer.skuName} · ${where}”？App 上不再显示这家的这个规格，购物车里的也会失效。`,
    confirmText: activating ? '启用' : '停用',
    danger: !activating,
  })
  if (!confirmed) return
  busy.value = true
  try {
    emit('changed', await props.api.changeOfferStatus(props.detail.product.productId, offer.offerId,
      activating ? 'ACTIVATE' : 'DEACTIVATE', offer.version))
    notifyRecorded(activating ? '启用供给' : '停用供给', '操作记录')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="toolbar">
    <span class="hint">一条供给 = 商家 × 规格 × 城市（不选城市是全国可售）。新建的供给是停用的；标价的购买商品，商家要有有效收款账户才能启用。</span>
    <el-button type="primary" :disabled="noSku" @click="target = 'new'">新建供给</el-button>
  </div>
  <el-alert v-if="noSku" type="info" :closable="false" title="先在“规格”里建一个规格，再加供给。" class="notice" />
  <el-table :data="detail.offers" row-key="offerId" border empty-text="还没有供给">
    <el-table-column label="规格" min-width="150">
      <template #default="{ row }">{{ (row as OpsOffer).skuName }}</template>
    </el-table-column>
    <el-table-column label="商家" min-width="190">
      <template #default="{ row }">
        <div>{{ (row as OpsOffer).merchantName }}</div>
        <div class="sub">{{ merchantKindLabel((row as OpsOffer).merchantKind) }}</div>
        <div v-if="!(row as OpsOffer).merchantCollecting" class="warn">未登记收款账户</div>
      </template>
    </el-table-column>
    <el-table-column label="城市" width="100">
      <template #default="{ row }">{{ (row as OpsOffer).cityCode ? ((row as OpsOffer).cityName ?? (row as OpsOffer).cityCode) : '全国' }}</template>
    </el-table-column>
    <el-table-column label="价格" width="130">
      <template #default="{ row }">
        <template v-if="(row as OpsOffer).priceAmountMinor !== null">
          {{ formatYuan((row as OpsOffer).priceAmountMinor) }}
          <span class="sub">/{{ priceUnitLabel((row as OpsOffer).priceUnit).replace('每', '') }}</span>
        </template>
        <span v-else class="sub">询价</span>
      </template>
    </el-table-column>
    <el-table-column label="库存" width="80">
      <template #default="{ row }">
        <span :class="{ warn: (row as OpsOffer).stockQuantity === 0 }">{{ stockLabel((row as OpsOffer).stockQuantity) }}</span>
      </template>
    </el-table-column>
    <el-table-column label="配送" min-width="180">
      <template #default="{ row }">
        <div v-if="(row as OpsOffer).expressAvailable">
          快递 · {{ (row as OpsOffer).shippingFeeMinor === 0 ? '包邮' : `运费 ${formatYuan((row as OpsOffer).shippingFeeMinor)}` }}
        </div>
        <div v-if="(row as OpsOffer).pickupPoint" class="sub">自提：{{ (row as OpsOffer).pickupPoint }}</div>
      </template>
    </el-table-column>
    <el-table-column label="状态" width="80">
      <template #default="{ row }">
        <el-tag :type="(row as OpsOffer).status === 'ACTIVE' ? 'success' : 'info'">
          {{ (row as OpsOffer).status === 'ACTIVE' ? '启用' : '停用' }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="130" fixed="right">
      <template #default="{ row }">
        <el-button link type="primary" :disabled="busy" @click="target = row as OpsOffer">编辑</el-button>
        <el-button link :type="(row as OpsOffer).status === 'ACTIVE' ? 'danger' : 'primary'" :disabled="busy"
                   @click="toggle(row as OpsOffer)">
          {{ (row as OpsOffer).status === 'ACTIVE' ? '停用' : '启用' }}
        </el-button>
      </template>
    </el-table-column>
  </el-table>

  <OfferDialog :api="api" :detail="detail" :target="target" @close="target = null" @changed="changed" />
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
}

.hint,
.sub {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.warn {
  font-size: 12px;
  color: var(--el-color-danger);
}

.notice {
  margin-bottom: 12px;
}
</style>
