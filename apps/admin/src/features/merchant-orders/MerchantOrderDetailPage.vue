<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import {
  canCompletePickup,
  canRefund,
  canShip,
  CLOSE_REASON_LABELS,
  createMerchantOrdersApi,
  DELIVERY_MODE_LABELS,
  orderStatusLabel,
  REFUND_STATUS_LABELS,
  refundableMinor,
  type MerchantOrdersApi,
  type TradeOrder,
} from './merchant-orders-api'
import PickupDialog from './PickupDialog.vue'
import RefundDialog from './RefundDialog.vue'
import ShipDialog from './ShipDialog.vue'

const props = defineProps<{
  api?: MerchantOrdersApi
}>()

const api = props.api ?? createMerchantOrdersApi(http)
const route = useRoute()
const router = useRouter()
const order = shallowRef<TradeOrder | null>(null)
const loading = ref(false)
const error = ref('')
const dialog = ref<'ship' | 'pickup' | 'refund' | null>(null)

const orderId = computed(() => String(route.params.orderId ?? ''))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    order.value = await api.detail(orderId.value)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

/** 时间线：只列已经发生的节点。 */
const timeline = computed(() => {
  const value = order.value
  if (!value) return []
  const items: Array<{ key: string, label: string, at: string }> = [
    { key: 'created', label: '买家下单', at: value.createdAt },
  ]
  if (value.paidAt) items.push({ key: 'paid', label: '买家付款', at: value.paidAt })
  if (value.delivery?.shippedAt) items.push({ key: 'shipped', label: '已发货', at: value.delivery.shippedAt })
  for (const refund of value.refunds) {
    items.push({ key: `refund-${refund.refundId}`, label: `申请退款 ${formatMoney(refund.amountMinor)}`, at: refund.requestedAt })
    if (refund.succeededAt) {
      items.push({ key: `refunded-${refund.refundId}`, label: `退款到账 ${formatMoney(refund.amountMinor)}`, at: refund.succeededAt })
    }
  }
  if (value.completedAt) items.push({ key: 'completed', label: '订单完成', at: value.completedAt })
  if (value.closedAt) {
    const reason = value.closeReason ? `（${CLOSE_REASON_LABELS[value.closeReason]}）` : ''
    items.push({ key: 'closed', label: `订单关闭${reason}`, at: value.closedAt })
  }
  return items.sort((a, b) => a.at.localeCompare(b.at))
})

function changed(next: TradeOrder): void {
  dialog.value = null
  order.value = next
}

function refunded(): void {
  dialog.value = null
  void load()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="order ? `订单 ${order.orderNo}` : '订单详情'"
    :description="order ? `${orderStatusLabel(order)} · 下单于 ${formatDateTime(order.createdAt)}` : undefined"
  >
    <template #actions>
      <template v-if="order">
        <el-button v-if="canShip(order)" type="primary" @click="dialog = 'ship'">发货</el-button>
        <el-button v-if="canCompletePickup(order)" type="primary" @click="dialog = 'pickup'">自提核销</el-button>
        <el-button v-if="canRefund(order)" type="danger" plain @click="dialog = 'refund'">退款</el-button>
      </template>
      <el-button @click="load()">刷新</el-button>
      <el-button @click="router.push({ name: 'workbench-mall-orders' })">返回列表</el-button>
    </template>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button size="small" @click="load()">重新加载</el-button>
    </el-alert>
    <div v-else-if="loading && !order" v-loading="true" class="placeholder" />
    <template v-else-if="order">
      <h2 class="block-title">商品</h2>
      <el-table :data="order.lines" row-key="lineId" border>
        <el-table-column label="商品" min-width="220">
          <template #default="{ row }">{{ row.productName }}</template>
        </el-table-column>
        <el-table-column label="规格" min-width="140">
          <template #default="{ row }">{{ row.skuName }}</template>
        </el-table-column>
        <el-table-column label="单价" width="120" align="right">
          <template #default="{ row }">{{ formatMoney(row.unitAmountMinor) }}</template>
        </el-table-column>
        <el-table-column label="数量" width="80" align="right">
          <template #default="{ row }">{{ row.quantity }}</template>
        </el-table-column>
        <el-table-column label="小计" width="120" align="right">
          <template #default="{ row }">{{ formatMoney(row.subtotalMinor) }}</template>
        </el-table-column>
      </el-table>

      <h2 class="block-title">金额</h2>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="商品金额">{{ formatMoney(order.amounts.goodsMinor) }}</el-descriptions-item>
        <el-descriptions-item label="运费">{{ formatMoney(order.amounts.shippingMinor) }}</el-descriptions-item>
        <el-descriptions-item label="应付">{{ formatMoney(order.amounts.payableMinor) }}</el-descriptions-item>
        <el-descriptions-item label="实付">{{ formatMoney(order.amounts.paidMinor) }}</el-descriptions-item>
        <el-descriptions-item label="已退">{{ formatMoney(order.amounts.refundedMinor) }}</el-descriptions-item>
        <el-descriptions-item label="还可退">{{ formatMoney(refundableMinor(order)) }}</el-descriptions-item>
      </el-descriptions>

      <h2 class="block-title">{{ order.delivery?.mode === 'PICKUP' ? '自提' : '收货信息' }}</h2>
      <el-descriptions v-if="order.delivery" :column="2" border>
        <el-descriptions-item label="配送方式">{{ DELIVERY_MODE_LABELS[order.delivery.mode] }}</el-descriptions-item>
        <template v-if="order.delivery.mode === 'EXPRESS'">
          <el-descriptions-item label="收货人">{{ order.delivery.recipientName ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="手机号">
            {{ order.delivery.recipientPhone ?? order.delivery.phoneMasked ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="收货地址">
            {{ order.delivery.addressLine ?? '买家付款后显示' }}
          </el-descriptions-item>
          <el-descriptions-item label="物流公司">{{ order.delivery.carrierName ?? '未发货' }}</el-descriptions-item>
          <el-descriptions-item label="运单号">{{ order.delivery.trackingNo ?? '未发货' }}</el-descriptions-item>
        </template>
        <template v-else>
          <el-descriptions-item label="自提点">{{ order.delivery.pickupPoint ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="取货码">买家到店时出示，商家这里看不到</el-descriptions-item>
        </template>
      </el-descriptions>
      <p v-if="order.buyerNote" class="note">买家留言：{{ order.buyerNote }}</p>

      <h2 class="block-title">退款记录</h2>
      <el-table :data="order.refunds" row-key="refundId" border empty-text="没有退款">
        <el-table-column label="申请时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.requestedAt) }}</template>
        </el-table-column>
        <el-table-column label="金额" width="120" align="right">
          <template #default="{ row }">{{ formatMoney(row.amountMinor) }}</template>
        </el-table-column>
        <el-table-column label="原因" min-width="200">
          <template #default="{ row }">{{ row.reason }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">{{ REFUND_STATUS_LABELS[row.status as keyof typeof REFUND_STATUS_LABELS] }}</template>
        </el-table-column>
        <el-table-column label="到账时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.succeededAt) }}</template>
        </el-table-column>
      </el-table>
      <p class="hint">退款按原路退回买家的微信，状态以微信的退款结果为准。“退款异常”需要在微信商户平台人工处理。</p>

      <h2 class="block-title">进度</h2>
      <el-timeline>
        <el-timeline-item v-for="item in timeline" :key="item.key" :timestamp="formatDateTime(item.at)">
          {{ item.label }}
        </el-timeline-item>
      </el-timeline>
    </template>
  </PageSection>

  <template v-if="order">
    <ShipDialog :api="api" :order="dialog === 'ship' ? order : null" @close="dialog = null" @done="changed" />
    <PickupDialog :api="api" :order="dialog === 'pickup' ? order : null" @close="dialog = null" @done="changed" />
    <RefundDialog :api="api" :order="dialog === 'refund' ? order : null" @close="dialog = null" @done="refunded" />
  </template>
</template>

<style scoped>
.block-title {
  margin: 20px 0 10px;
  font-size: 15px;
}

.block-title:first-child {
  margin-top: 0;
}

.placeholder {
  min-height: 200px;
}

.note,
.hint {
  margin: 10px 0 0;
  font-size: 13px;
  color: var(--admin-text-secondary);
}
</style>
