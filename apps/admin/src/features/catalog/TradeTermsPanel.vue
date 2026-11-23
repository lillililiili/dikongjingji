<script setup lang="ts">
import { onMounted, reactive, ref, shallowRef } from 'vue'

import { errorMessage } from '@/core/http/api-error'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'

import type { OpsOffer } from './catalog-api'
import {
  OFFER_TERMS_STATUS_LABELS,
  offerTermsDirty,
  offerTermsDraft,
  offerTermsError,
  TERMS_LIMITS,
  type MallOfferTerms,
  type MallTradeTerms,
  type OfferTermsDraft,
  type TradeTermsApi,
} from './trade-terms-api'

/**
 * 交易条款：七天无理由开关，每个供给的开票方、售后责任方（留空按商家资料，再没有就是商家自己）。
 * 改动记在本商品的操作记录里；保存后通知详情页重新读一次，操作记录页签就能看到。
 */
const props = defineProps<{
  api: TradeTermsApi
  productId: string
  /** 详情里的在售供给，只用来把城市编码显示成城市名。 */
  offers: OpsOffer[]
}>()

const emit = defineEmits<{
  changed: []
}>()

const terms = shallowRef<MallTradeTerms | null>(null)
const drafts = reactive<Record<string, OfferTermsDraft>>({})
const loading = ref(false)
const error = ref('')
const busy = ref(false)

function apply(next: MallTradeTerms): void {
  terms.value = next
  for (const key of Object.keys(drafts)) delete drafts[key]
  for (const offer of next.offers) drafts[offer.offerId] = offerTermsDraft(offer)
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    apply(await props.api.read(props.productId))
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function cityText(offer: MallOfferTerms): string {
  if (offer.cityCode === null) return '全国'
  return props.offers.find((candidate) => candidate.offerId === offer.offerId)?.cityName ?? offer.cityCode
}

async function toggleNoReason(value: string | number | boolean): Promise<void> {
  const current = terms.value
  if (!current || busy.value) return
  const next = value === true
  const confirmed = await confirmAction({
    title: next ? '改为支持七天无理由' : '改为不支持七天无理由',
    message: next
      ? '买家确认收货后 7 天内可以选“七天无理由”申请退货退款。'
      : '以后下单的买家不能选“七天无理由”退货；质量问题（与描述不符、有损坏）不受影响。已经下的订单按下单时的约定。',
    confirmText: '确认修改',
    danger: !next,
  })
  if (!confirmed) return
  busy.value = true
  try {
    apply(await props.api.setNoReasonReturn(props.productId, next))
    notifyRecorded(next ? '改为支持七天无理由' : '改为不支持七天无理由', '操作记录')
    emit('changed')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

async function saveOffer(offer: MallOfferTerms): Promise<void> {
  const draft = drafts[offer.offerId]
  if (!draft || busy.value) return
  const problem = offerTermsError(draft)
  if (problem) {
    showError(new Error(problem))
    return
  }
  const confirmed = await confirmAction({
    title: '保存开票方和售后责任方',
    message: `“${offer.skuName} · ${offer.merchantName} · ${cityText(offer)}”这条供给：留空的项按商家资料，`
      + '再没有就是商家自己。只影响以后的订单，已经下的订单用下单时的快照。',
    confirmText: '保存',
  })
  if (!confirmed) return
  busy.value = true
  try {
    apply(await props.api.setOfferTerms(props.productId, offer.offerId, draft))
    notifyRecorded('保存交易条款', '操作记录')
    emit('changed')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div v-loading="loading" class="terms">
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button size="small" @click="load()">重新加载</el-button>
    </el-alert>
    <template v-else-if="terms">
      <div class="terms__row">
        <span class="terms__label">七天无理由</span>
        <el-switch :model-value="terms.noReasonReturn" :disabled="busy" active-text="支持" inactive-text="不支持"
                   class="terms-no-reason" @change="toggleNoReason" />
        <span class="hint">默认支持。标“不支持”后买家不能选七天无理由退货，质量问题的售后不受影响。</span>
      </div>

      <h3 class="terms__title">各供给的开票方、售后责任方</h3>
      <p class="hint">留空表示按商家资料（商家在工作台填、平台审核通过的默认值），再没有就是商家自己。“实际生效”是 App 商品详情和订单上显示的值。</p>
      <el-table :data="terms.offers" row-key="offerId" border empty-text="还没有供给">
        <el-table-column label="规格" min-width="120">
          <template #default="{ row }">{{ (row as MallOfferTerms).skuName }}</template>
        </el-table-column>
        <el-table-column label="商家" min-width="140">
          <template #default="{ row }">{{ (row as MallOfferTerms).merchantName }}</template>
        </el-table-column>
        <el-table-column label="城市" width="90">
          <template #default="{ row }">{{ cityText(row as MallOfferTerms) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="(row as MallOfferTerms).status === 'ACTIVE' ? 'success' : 'info'">
              {{ OFFER_TERMS_STATUS_LABELS[(row as MallOfferTerms).status] }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="开票方" min-width="200">
          <template #default="{ row }">
            <el-input v-model="drafts[(row as MallOfferTerms).offerId]!.invoiceIssuer" size="small"
                      :maxlength="TERMS_LIMITS.invoiceIssuer" placeholder="留空按商家资料" />
            <div class="effective">实际生效：{{ (row as MallOfferTerms).effectiveInvoiceIssuer ?? '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="售后责任方" min-width="200">
          <template #default="{ row }">
            <el-input v-model="drafts[(row as MallOfferTerms).offerId]!.aftersaleParty" size="small"
                      :maxlength="TERMS_LIMITS.aftersaleParty" placeholder="留空按商家资料" />
            <div class="effective">实际生效：{{ (row as MallOfferTerms).effectiveAftersaleParty ?? '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="busy
                         || !offerTermsDirty(row as MallOfferTerms, drafts[(row as MallOfferTerms).offerId]!)"
                       @click="saveOffer(row as MallOfferTerms)">保存</el-button>
          </template>
        </el-table-column>
      </el-table>
      <p class="hint">改动记在“操作记录”页签里。已经下的订单用下单时的快照，不受影响。</p>
    </template>
  </div>
</template>

<style scoped>
.terms {
  min-height: 120px;
}

.terms__row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.terms__label {
  font-weight: 600;
}

.terms__title {
  margin: 24px 0 6px;
  font-size: 15px;
}

.effective {
  margin-top: 4px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.hint {
  margin: 6px 0 10px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
