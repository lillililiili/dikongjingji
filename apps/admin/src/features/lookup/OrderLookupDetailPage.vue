<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { formatDateTime, formatMoney } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'
import { useLookupReturn } from './lookup-return'

import {
  AFTERSALE_KIND_LABELS,
  AFTERSALE_REASON_LABELS,
  AFTERSALE_STATUS_LABELS,
  CAUSE_LABELS,
  createLookupApi,
  label,
  PAYMENT_STATUS_LABELS,
  REFUND_INITIATOR_LABELS,
  REFUND_STATUS_LABELS,
  SOURCE_LABELS,
  STATUS_LABELS,
  type LookupApi,
  type OrderDetail,
} from './lookup-api'

const props = defineProps<{
  api?: LookupApi
}>()

const api = props.api ?? createLookupApi(http)
const route = useRoute()
const router = useRouter()
const returnToList = useLookupReturn('ops-orders')
const order = shallowRef<OrderDetail | null>(null)
const loading = ref(false)
const error = ref('')
const orderId = computed(() => String(route.params.orderId ?? ''))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    order.value = await api.order(orderId.value)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function openBuyer(): void {
  if (!order.value) return
  void router.push({ name: 'ops-user-detail', params: { userId: order.value.buyer.userId } })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="order ? `订单 ${order.orderNo}` : '订单详情'"
    :description="order
      ? `${SOURCE_LABELS[order.source]} · ${STATUS_LABELS[order.status]} · 下单于 ${formatDateTime(order.createdAt)}`
      : undefined"
  >
    <template #actions>
      <el-button @click="load()">刷新</el-button>
      <el-button @click="returnToList">返回查询</el-button>
    </template>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button size="small" @click="load()">重新加载</el-button>
    </el-alert>
    <div v-else-if="loading && !order" v-loading="true" class="placeholder" />
    <template v-else-if="order">
      <el-descriptions :column="3" border data-testid="order-lookup-basics">
        <el-descriptions-item label="买家">
          <el-button link type="primary" @click="openBuyer">{{ order.buyer.displayName ?? '已注销' }}</el-button>
          <span class="muted">{{ order.buyer.maskedPhone ?? '' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="付款主体">{{ order.payerName ?? '个人' }}</el-descriptions-item>
        <el-descriptions-item label="收款方">{{ order.merchantName }}</el-descriptions-item>
        <el-descriptions-item label="商品金额">{{ formatMoney(order.goodsMinor) }}</el-descriptions-item>
        <el-descriptions-item label="运费">{{ formatMoney(order.shippingMinor) }}</el-descriptions-item>
        <el-descriptions-item label="应付">{{ formatMoney(order.payableMinor) }}</el-descriptions-item>
        <el-descriptions-item label="实付">{{ formatMoney(order.paidMinor) }}</el-descriptions-item>
        <el-descriptions-item label="已退">{{ formatMoney(order.refundedMinor) }}</el-descriptions-item>
        <el-descriptions-item label="付款截止">{{ formatDateTime(order.payBefore) }}</el-descriptions-item>
        <el-descriptions-item label="付款时间">{{ formatDateTime(order.paidAt) }}</el-descriptions-item>
        <el-descriptions-item label="完成时间">{{ formatDateTime(order.completedAt) }}</el-descriptions-item>
        <el-descriptions-item label="关闭时间">{{ formatDateTime(order.closedAt) }}</el-descriptions-item>
      </el-descriptions>
      <p v-if="order.buyerNote" class="note">买家留言：{{ order.buyerNote }}</p>
      <p v-if="order.sourceRef" class="note">业务单 ID：{{ order.sourceRef }}（{{ SOURCE_LABELS[order.source] }}）</p>

      <h2 class="block-title">内容</h2>
      <el-table :data="order.lines" border>
        <el-table-column label="名称" min-width="220" prop="productName" />
        <el-table-column label="规格" min-width="140">
          <template #default="{ row }">{{ row.skuName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="单价" width="120" align="right">
          <template #default="{ row }">{{ formatMoney(row.unitAmountMinor) }}</template>
        </el-table-column>
        <el-table-column label="数量" width="80" align="right" prop="quantity" />
        <el-table-column label="小计" width="120" align="right">
          <template #default="{ row }">{{ formatMoney(row.subtotalMinor) }}</template>
        </el-table-column>
      </el-table>

      <template v-if="order.delivery">
        <h2 class="block-title">{{ order.delivery.mode === 'PICKUP' ? '自提' : '收货与物流' }}</h2>
        <el-descriptions :column="3" border>
          <template v-if="order.delivery.mode === 'EXPRESS'">
            <el-descriptions-item label="收货人">{{ order.delivery.recipientMasked ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="手机号">{{ order.delivery.phoneMasked ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="收货城市">{{ order.delivery.cityName ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="物流公司">{{ order.delivery.carrierName ?? '未发货' }}</el-descriptions-item>
            <el-descriptions-item label="运单号">{{ order.delivery.trackingNo ?? '未发货' }}</el-descriptions-item>
            <el-descriptions-item label="发货时间">{{ formatDateTime(order.shippedAt) }}</el-descriptions-item>
          </template>
          <template v-else>
            <el-descriptions-item label="自提点">{{ order.delivery.pickupPoint ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="取货码">只有买家能看到</el-descriptions-item>
          </template>
        </el-descriptions>
        <p class="hint">详细地址不在这里显示；需要时请商家在工作台查看。</p>
      </template>

      <h2 class="block-title">微信支付</h2>
      <el-table :data="order.payments" border empty-text="还没有发起支付">
        <el-table-column label="商户订单号" min-width="200" prop="outTradeNo" />
        <el-table-column label="微信交易号" min-width="220">
          <template #default="{ row }">{{ row.transactionId ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">{{ formatMoney(row.amountMinor) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ label(PAYMENT_STATUS_LABELS, row.status) }}</template>
        </el-table-column>
        <el-table-column label="付款时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.succeededAt) }}</template>
        </el-table-column>
      </el-table>

      <h2 class="block-title">退款</h2>
      <el-table :data="order.refunds" border empty-text="没有退款">
        <el-table-column label="退款单号" min-width="200" prop="outRefundNo" />
        <el-table-column label="发起" width="100">
          <template #default="{ row }">{{ label(REFUND_INITIATOR_LABELS, row.initiator) }}</template>
        </el-table-column>
        <el-table-column label="金额" width="110" align="right">
          <template #default="{ row }">{{ formatMoney(row.amountMinor) }}</template>
        </el-table-column>
        <el-table-column label="原因" min-width="160">
          <template #default="{ row }">{{ row.reason ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ label(REFUND_STATUS_LABELS, row.status) }}</template>
        </el-table-column>
        <el-table-column label="到账时间" width="150">
          <template #default="{ row }">{{ formatDateTime(row.succeededAt) }}</template>
        </el-table-column>
      </el-table>

      <template v-if="order.aftersales.length">
        <h2 class="block-title">售后</h2>
        <el-table :data="order.aftersales" row-key="id" border>
          <el-table-column label="售后单号" min-width="180" prop="aftersaleNo" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">{{ label(AFTERSALE_KIND_LABELS, row.kind) }}</template>
          </el-table-column>
          <el-table-column label="原因" min-width="160">
            <template #default="{ row }">{{ label(AFTERSALE_REASON_LABELS, row.reason) }}</template>
          </el-table-column>
          <el-table-column label="申请 / 退款" width="160" align="right">
            <template #default="{ row }">
              {{ formatMoney(row.requestedMinor) }} / {{ formatMoney(row.refundMinor) }}
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">{{ label(AFTERSALE_STATUS_LABELS, row.status) }}</template>
          </el-table-column>
          <el-table-column label="申请时间" width="150">
            <template #default="{ row }">{{ formatDateTime(row.submittedAt) }}</template>
          </el-table-column>
        </el-table>
      </template>

      <h2 class="block-title">状态变化</h2>
      <el-timeline>
        <el-timeline-item
          v-for="(item, index) in order.history"
          :key="`${item.occurredAt}-${index}`"
          :timestamp="formatDateTime(item.occurredAt)"
        >
          {{ label(CAUSE_LABELS, item.cause) }} → {{ label(STATUS_LABELS, item.toStatus) }}
        </el-timeline-item>
      </el-timeline>
    </template>
  </PageSection>
</template>

<style scoped>
.block-title {
  margin: 20px 0 10px;
  font-size: 15px;
}

.placeholder {
  min-height: 200px;
}

.muted {
  margin-left: 6px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.note,
.hint {
  margin: 10px 0 0;
  font-size: 13px;
  color: var(--admin-text-secondary);
}
</style>
