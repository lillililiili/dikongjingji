<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { formatDateTime } from '@/core/ui/format'
import { formatYuan } from '@/core/ui/money'
import PageSection from '@/core/ui/PageSection.vue'

import {
  ACTION_LABELS,
  createAftersaleApi,
  dueHint,
  KIND_LABELS,
  REASON_LABELS,
  REFUND_STATUS_LABELS,
  SLOT_LABELS,
  STATUS_LABELS,
  STATUS_TAGS,
  type Aftersale,
  type AftersaleApi,
  type AftersaleImageSlot,
} from './aftersale-api'
import AftersaleImageThumb from './AftersaleImageThumb.vue'
import ApproveDialog from './ApproveDialog.vue'
import ReceiptDialog from './ReceiptDialog.vue'
import RejectDialog from './RejectDialog.vue'

const props = defineProps<{
  api?: AftersaleApi
}>()

const api = props.api ?? createAftersaleApi(http)
const route = useRoute()
const router = useRouter()
const aftersale = shallowRef<Aftersale | null>(null)
const loading = ref(false)
const error = ref('')
const dialog = ref<'approve' | 'reject' | 'receipt' | 'receipt-reject' | null>(null)

const aftersaleId = computed(() => String(route.params.aftersaleId ?? ''))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    aftersale.value = await api.detail(aftersaleId.value)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

/** 凭证按格分组（商品照片、包装照片、其他凭证），空的格不显示。 */
const imageGroups = computed(() => {
  const value = aftersale.value
  if (!value) return []
  const slots: AftersaleImageSlot[] = ['PRODUCT', 'PACKAGE', 'OTHER']
  return slots
    .map((slot) => ({ slot, label: SLOT_LABELS[slot], images: value.images.filter((image) => image.slot === slot) }))
    .filter((group) => group.images.length > 0)
})

const timeline = computed(() => [...(aftersale.value?.timeline ?? [])]
  .sort((a, b) => a.occurredAt.localeCompare(b.occurredAt)))

function changed(next: Aftersale): void {
  dialog.value = null
  aftersale.value = next
}

/** 409、412：别人（或系统的自动处理）已经改过这张单，关掉弹窗重新读一次。 */
function stale(): void {
  dialog.value = null
  void load()
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="aftersale ? `售后 ${aftersale.aftersaleNo}` : '售后详情'"
    :description="aftersale
      ? `${KIND_LABELS[aftersale.kind]} · ${STATUS_LABELS[aftersale.status]} · 提交于 ${formatDateTime(aftersale.submittedAt)}`
      : undefined"
  >
    <template #actions>
      <template v-if="aftersale">
        <el-button v-if="aftersale.actions.canReview" type="primary" @click="dialog = 'approve'">同意退货</el-button>
        <el-button v-if="aftersale.actions.canReview" type="danger" plain @click="dialog = 'reject'">不同意</el-button>
        <el-button v-if="aftersale.actions.canConfirmReceipt" type="primary" @click="dialog = 'receipt'">
          确认收货并退款
        </el-button>
        <el-button v-if="aftersale.actions.canRejectReceipt" type="danger" plain @click="dialog = 'receipt-reject'">
          验货不通过
        </el-button>
      </template>
      <el-button @click="load()">刷新</el-button>
      <el-button @click="router.push({ name: 'workbench-aftersales' })">返回列表</el-button>
    </template>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button size="small" @click="load()">重新加载</el-button>
    </el-alert>
    <div v-else-if="loading && !aftersale" v-loading="true" class="placeholder" />
    <template v-else-if="aftersale">
      <el-alert
        v-if="aftersale.dueAt && dueHint(aftersale.status)"
        type="warning"
        :closable="false"
        show-icon
        :title="`处理时限 ${formatDateTime(aftersale.dueAt)}：${dueHint(aftersale.status)}`"
        class="due"
      />

      <h2 class="block-title">申请</h2>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="售后编号">{{ aftersale.aftersaleNo }}</el-descriptions-item>
        <el-descriptions-item label="订单号">{{ aftersale.orderNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="STATUS_TAGS[aftersale.status]" effect="plain">{{ STATUS_LABELS[aftersale.status] }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="类型">{{ KIND_LABELS[aftersale.kind] }}</el-descriptions-item>
        <el-descriptions-item label="原因">{{ REASON_LABELS[aftersale.reason] }}</el-descriptions-item>
        <el-descriptions-item label="售后责任方">{{ aftersale.merchant.aftersaleParty ?? aftersale.merchant.name }}</el-descriptions-item>
        <el-descriptions-item label="订单实付">{{ formatYuan(aftersale.paidMinor) }}</el-descriptions-item>
        <el-descriptions-item label="申请金额">{{ formatYuan(aftersale.requestedMinor) }}</el-descriptions-item>
        <el-descriptions-item label="退款金额">
          {{ aftersale.refundMinor === null ? '还没发起退款' : formatYuan(aftersale.refundMinor) }}
        </el-descriptions-item>
        <el-descriptions-item label="补充描述" :span="3">
          <span class="multiline">{{ aftersale.description ?? '买家没有填写' }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <h2 class="block-title">商品</h2>
      <el-table :data="aftersale.lines" row-key="lineId" border>
        <el-table-column label="商品" min-width="220">
          <template #default="{ row }">{{ row.productName }}</template>
        </el-table-column>
        <el-table-column label="规格" min-width="140">
          <template #default="{ row }">{{ row.skuName }}</template>
        </el-table-column>
        <el-table-column label="单价" width="120" align="right">
          <template #default="{ row }">{{ formatYuan(row.unitAmountMinor) }}</template>
        </el-table-column>
        <el-table-column label="数量" width="80" align="right">
          <template #default="{ row }">{{ row.quantity }}</template>
        </el-table-column>
        <el-table-column label="小计" width="120" align="right">
          <template #default="{ row }">{{ formatYuan(row.subtotalMinor) }}</template>
        </el-table-column>
      </el-table>

      <h2 class="block-title">凭证图片</h2>
      <p v-if="imageGroups.length === 0" class="hint">买家没有上传凭证图片。</p>
      <div v-for="group in imageGroups" :key="group.slot" class="images">
        <div class="images__label">{{ group.label }}</div>
        <div class="images__row">
          <AftersaleImageThumb v-for="image in group.images" :key="image.imageId" :api="api"
                               :aftersale-id="aftersale.aftersaleId" :image-id="image.imageId" />
        </div>
      </div>

      <template v-if="aftersale.kind === 'RETURN_REFUND'">
        <h2 class="block-title">寄回</h2>
        <el-descriptions :column="2" border>
          <el-descriptions-item label="寄回地址" :span="2">
            <template v-if="aftersale.returnAddress">
              {{ aftersale.returnAddress.contactName ?? '—' }}，{{ aftersale.returnAddress.phone ?? '—' }}，
              {{ aftersale.returnAddress.address }}
              <div v-if="aftersale.returnAddress.note" class="hint">说明：{{ aftersale.returnAddress.note }}</div>
            </template>
            <template v-else>{{ aftersale.reviewedAt ? '没有寄回地址（没设默认寄回地址时自动同意的单），请联系买家' : '同意后给出' }}</template>
          </el-descriptions-item>
          <el-descriptions-item label="退货运单">
            <template v-if="aftersale.returnWaybill">
              {{ aftersale.returnWaybill.carrierName }} {{ aftersale.returnWaybill.trackingNo }}
            </template>
            <template v-else>买家还没填</template>
          </el-descriptions-item>
          <el-descriptions-item label="寄出时间">{{ formatDateTime(aftersale.returnWaybill?.shippedAt ?? aftersale.returnShippedAt) }}</el-descriptions-item>
        </el-descriptions>
      </template>

      <template v-if="aftersale.rejectReason || aftersale.merchantNote">
        <h2 class="block-title">商家处理</h2>
        <el-descriptions :column="1" border>
          <el-descriptions-item v-if="aftersale.rejectReason" label="原因">{{ aftersale.rejectReason }}</el-descriptions-item>
          <el-descriptions-item v-if="aftersale.merchantNote" label="商家说明">
            <span class="multiline">{{ aftersale.merchantNote }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </template>

      <h2 class="block-title">退款</h2>
      <el-descriptions v-if="aftersale.refund" :column="2" border>
        <el-descriptions-item label="状态">{{ REFUND_STATUS_LABELS[aftersale.refund.status] }}</el-descriptions-item>
        <el-descriptions-item label="金额">{{ formatYuan(aftersale.refund.amountMinor) }}</el-descriptions-item>
        <el-descriptions-item label="到账时间">{{ formatDateTime(aftersale.refund.succeededAt) }}</el-descriptions-item>
        <el-descriptions-item label="微信交易号">{{ aftersale.refund.channelTransactionId ?? '—' }}</el-descriptions-item>
      </el-descriptions>
      <p v-else class="hint">还没有发起退款。</p>
      <p class="hint">退款经平台支付渠道（微信支付）原路退回买家，状态以微信的退款结果为准；“退款异常”需要人工处理。</p>

      <h2 class="block-title">进度</h2>
      <el-timeline>
        <el-timeline-item v-for="(item, index) in timeline" :key="`${item.action}-${item.occurredAt}-${index}`"
                          :timestamp="formatDateTime(item.occurredAt)">
          {{ ACTION_LABELS[item.action] ?? '其他' }}
          <span class="hint">（之后：{{ STATUS_LABELS[item.status] }}）</span>
        </el-timeline-item>
      </el-timeline>
    </template>
  </PageSection>

  <template v-if="aftersale">
    <ApproveDialog :api="api" :aftersale="dialog === 'approve' ? aftersale : null" @close="dialog = null"
                   @done="changed" @stale="stale" />
    <RejectDialog :api="api" mode="review" :aftersale="dialog === 'reject' ? aftersale : null" @close="dialog = null"
                  @done="changed" @stale="stale" />
    <ReceiptDialog :api="api" :aftersale="dialog === 'receipt' ? aftersale : null" @close="dialog = null"
                   @done="changed" @stale="stale" />
    <RejectDialog :api="api" mode="receipt" :aftersale="dialog === 'receipt-reject' ? aftersale : null"
                  @close="dialog = null" @done="changed" @stale="stale" />
  </template>
</template>

<style scoped>
.block-title {
  margin: 20px 0 10px;
  font-size: 15px;
}

.due {
  margin-bottom: 4px;
}

.placeholder {
  min-height: 200px;
}

.hint {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.multiline {
  white-space: pre-wrap;
}

.images {
  display: flex;
  gap: 12px;
  margin-bottom: 10px;
}

.images__label {
  width: 72px;
  flex-shrink: 0;
  padding-top: 4px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.images__row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
