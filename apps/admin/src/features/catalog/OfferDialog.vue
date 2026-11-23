<script setup lang="ts">
import type { FormInstance } from 'element-plus'
import { computed, reactive, ref, shallowRef, watch } from 'vue'

import { http } from '@/core/http'
import { newIdempotencyKey } from '@/core/http/api-client'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatYuan, yuanToMinor } from '@/core/ui/money'
import { createCityApi, type OpsCity } from '@/features/city/city-api'
import {
  createMerchantAccountApi,
  type OpsMerchantAccount,
} from '@/features/ops-merchant-account/merchant-account-api'

import {
  CATALOG_LIMITS as L,
  MERCHANT_KIND_OPTIONS,
  PRICE_UNIT_OPTIONS,
  priceAllowed,
  priceUnitsFor,
  type CatalogApi,
  type OpsOffer,
  type OpsProductDetail,
} from './catalog-api'
import { offerRules } from './catalog-rules'
import { emptyOfferForm, offerFormFrom, toOfferDraft, type OfferForm } from './offer-form'

const props = defineProps<{
  api: CatalogApi
  detail: OpsProductDetail
  /** 打开弹窗：'new' 新建；一条供给表示编辑它；null 关着。 */
  target: OpsOffer | 'new' | null
}>()

const emit = defineEmits<{
  close: []
  changed: [detail: OpsProductDetail]
}>()

const merchantApi = createMerchantAccountApi(http)
const cityApi = createCityApi(http)

const formRef = ref<FormInstance>()
const form = reactive<OfferForm>(emptyOfferForm('BUY'))
const merchants = shallowRef<OpsMerchantAccount[]>([])
const merchantsLoading = ref(false)
const cities = shallowRef<OpsCity[]>([])
const citiesLoading = ref(false)
const submitting = ref(false)
let idempotencyKey = newIdempotencyKey()

const saleMode = computed(() => props.detail.product.saleMode)
const creating = computed(() => props.target === 'new')
const editing = computed(() => (props.target && props.target !== 'new' ? props.target : null))
const units = computed(() => PRICE_UNIT_OPTIONS.filter((option) => priceUnitsFor(saleMode.value).includes(option.value)))
const rules = computed(() => offerRules(form, () => saleMode.value, creating.value))
const chosenMerchant = computed(() => merchants.value.find((merchant) => merchant.organizationId === form.merchantId))

watch(() => props.target, (target) => {
  if (!target) return
  idempotencyKey = newIdempotencyKey()
  const activeSku = props.detail.skus.find((sku) => sku.status === 'ACTIVE')
  Object.assign(form, target === 'new' ? emptyOfferForm(saleMode.value, activeSku?.skuId ?? '') : offerFormFrom(target))
  if (target !== 'new' && target.cityCode) {
    cities.value = [{ cityCode: target.cityCode, cityName: target.cityName ?? target.cityCode } as OpsCity]
  }
  formRef.value?.clearValidate()
  if (target === 'new') void searchMerchants('')
}, { immediate: true })

async function searchMerchants(keyword: string): Promise<void> {
  merchantsLoading.value = true
  try {
    merchants.value = (await merchantApi.list({ keyword, registered: '' }, 1, 20)).items
      .filter((merchant) => merchant.organizationStatus === 'ACTIVE')
  } catch (failure) {
    showError(failure)
  } finally {
    merchantsLoading.value = false
  }
}

async function searchCities(keyword: string): Promise<void> {
  if (keyword.trim() === '') return
  citiesLoading.value = true
  try {
    cities.value = (await cityApi.list({ keyword, serviceStatus: '' }, 1, 20)).items
  } catch (failure) {
    showError(failure)
  } finally {
    citiesLoading.value = false
  }
}

async function submit(): Promise<void> {
  if (!formRef.value || submitting.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const draft = toOfferDraft(form, saleMode.value)
  const productId = props.detail.product.productId
  const current = editing.value
  const priceChanged = current !== null && current.priceAmountMinor !== draft.priceAmountMinor
  const confirmed = await confirmAction({
    title: creating.value ? '确认新建供给' : '确认修改供给',
    message: creating.value
      ? '新建的供给是停用的，核对后再在列表里启用。'
      : (priceChanged
        ? `价格从 ${formatYuan(current.priceAmountMinor)} 改成 ${formatYuan(draft.priceAmountMinor)}，改价会单独留痕。`
        : '保存这条供给的改动？')
        + (current?.status === 'ACTIVE' ? '这条供给正在启用，保存后 App 马上按新数据显示。' : ''),
    confirmText: '保存',
    danger: priceChanged && current?.status === 'ACTIVE',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    const detail = current
      ? await props.api.updateOffer(productId, current.offerId, draft, current.version)
      : await props.api.createOffer(productId, form.skuId, form.merchantId, draft, idempotencyKey)
    notifyRecorded(creating.value ? '新建供给' : (priceChanged ? '改价' : '修改供给'), '操作记录')
    emit('changed', detail)
  } catch (failure) {
    showError(failure)
  } finally {
    submitting.value = false
  }
}

const pricePreview = computed(() => {
  const minor = yuanToMinor(form.priceYuan)
  return minor === null ? '' : `= ${minor} 分`
})
</script>

<template>
  <el-dialog :model-value="target !== null" :title="creating ? '新建在售供给' : '修改在售供给'" width="640px"
             :close-on-click-modal="false" @close="emit('close')">
    <el-form v-if="target" ref="formRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item label="规格" prop="skuId">
        <el-select v-if="creating" v-model="form.skuId" style="width: 100%" placeholder="选择规格">
          <el-option v-for="sku in detail.skus" :key="sku.skuId" :value="sku.skuId"
                     :label="sku.status === 'ACTIVE' ? sku.name : `${sku.name}（已停用）`" />
        </el-select>
        <span v-else>{{ editing?.skuName }}</span>
      </el-form-item>
      <el-form-item label="商家" prop="merchantId">
        <template v-if="creating">
          <el-select v-model="form.merchantId" filterable remote :remote-method="searchMerchants"
                     :loading="merchantsLoading" placeholder="输入机构名称或编码搜索" style="width: 100%">
            <el-option v-for="merchant in merchants" :key="merchant.organizationId" :value="merchant.organizationId"
                       :label="merchant.organizationName">
              <span>{{ merchant.organizationName }}</span>
              <span :class="['merchant__pay', { 'merchant__pay--off': !(merchant.registered && merchant.active) }]">
                {{ merchant.registered && merchant.active ? '在收款' : '未登记收款账户' }}
              </span>
            </el-option>
          </el-select>
          <div v-if="chosenMerchant && !(chosenMerchant.registered && chosenMerchant.active)" class="field-warn">
            这家还没有有效的收款账户：标价的购买商品启用前要先在“收款账户”里登记。
          </div>
        </template>
        <span v-else>{{ editing?.merchantName }}</span>
      </el-form-item>
      <el-form-item label="商家类型" prop="merchantKind">
        <el-radio-group v-model="form.merchantKind">
          <el-radio v-for="option in MERCHANT_KIND_OPTIONS" :key="option.value" :value="option.value">
            {{ option.label }}
          </el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="城市">
        <el-select v-model="form.cityCode" filterable remote clearable :remote-method="searchCities"
                   :loading="citiesLoading" placeholder="不选表示全国可售" style="width: 100%">
          <el-option v-for="city in cities" :key="city.cityCode" :value="city.cityCode"
                     :label="city.provinceName ? `${city.cityName}（${city.provinceName}）` : city.cityName" />
        </el-select>
      </el-form-item>
      <el-form-item label="价格（元）" prop="priceYuan">
        <el-input-number v-model="form.priceYuan" :min="0" :precision="2" :step="1" :controls="false"
                         :disabled="!priceAllowed(saleMode)" :placeholder="priceAllowed(saleMode) ? '启用前必填' : '询价不标价'"
                         style="width: 180px" />
        <el-select v-model="form.priceUnit" style="width: 110px; margin-left: 8px">
          <el-option v-for="option in units" :key="option.value" :value="option.value" :label="option.label" />
        </el-select>
        <span class="field-hint">{{ pricePreview }}</span>
      </el-form-item>
      <el-form-item label="库存">
        <el-input-number v-model="form.stockQuantity" :min="0" :max="L.stock" :controls="false"
                         placeholder="空着表示不限" style="width: 180px" />
        <span class="field-hint">这家在这个城市的现货件数；0 表示缺货，App 上不能选这个规格</span>
      </el-form-item>
      <el-form-item label="配送">
        <el-checkbox v-model="form.expressAvailable">能快递</el-checkbox>
        <span class="field-label">运费（元）</span>
        <el-input-number v-model="form.shippingFeeYuan" :min="0" :precision="2" :controls="false"
                         :disabled="!form.expressAvailable" style="width: 120px" />
      </el-form-item>
      <el-form-item label="自提点" prop="pickupPoint">
        <el-input v-model="form.pickupPoint" :maxlength="L.pickupPoint" show-word-limit
                  :placeholder="form.expressAvailable ? '可空；有自提点才能到店自提' : '不能快递时必填'" />
      </el-form-item>
      <el-form-item label="供给说明" prop="deliveryNote">
        <el-input v-model="form.deliveryNote" :maxlength="L.note" placeholder="如：东营现货 · 48 小时发货 · 一年保修" />
      </el-form-item>
      <el-form-item label="配送说明" prop="shippingNote">
        <el-input v-model="form.shippingNote" :maxlength="L.note" placeholder="如：东营可买 · 包邮 · 48 小时内发货" />
      </el-form-item>
      <el-form-item label="发货地" prop="fulfilledFrom">
        <el-input v-model="form.fulfilledFrom" :maxlength="L.fulfilledFrom" placeholder="如：示例机器人商家 东营仓" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.field-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.field-label {
  margin: 0 8px 0 16px;
  font-size: 13px;
}

.field-warn {
  width: 100%;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-color-danger);
}

.merchant__pay {
  float: right;
  font-size: 12px;
  color: var(--el-color-success);
}

.merchant__pay--off {
  color: var(--el-color-danger);
}
</style>
