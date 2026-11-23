<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { loadCompareProducts } from '@/features/favorite/application/compare-load'
import { MAX_COMPARE_ITEMS, useCompareSelection } from '@/features/favorite/application/compare-selection'
import { FAVORITE_PREVIEW_ENABLED } from '@/features/favorite/application/favorite-preview'
import { parseMallCompareQuery, type MallCompareQuery } from '@/features/favorite/navigation/favorite-routes'
import MallComparePage from '@/features/favorite/presentation/MallComparePage.vue'
import { mallApi } from '@/features/mall/data/mall-api-client'
import type { MallProductDetail } from '@/features/mall/domain/mall'
import { buildMallProductUrl } from '@/features/mall/navigation/mall-routes'

// 商品对比 (board MallCompare): no login, nothing stored on the server; the picked products' ids
// come in the address and each one is read again from the mall.
const status = ref<'loading' | 'ready' | 'error' | 'too-few'>('loading')
const products = ref<MallProductDetail[]>([])
const query = ref<MallCompareQuery | null>(null)
const compare = useCompareSelection()

async function load(): Promise<void> {
  const value = query.value
  if (!value) {
    status.value = 'too-few'
    return
  }
  status.value = 'loading'
  try {
    const result = await loadCompareProducts(value.productIds, value.cityCode, (id, city) => mallApi.loadProduct(id, city))
    // A product no longer sold here leaves the picks, so the mall and the favorites stop showing it.
    result.missing.forEach((id) => compare.remove(id))
    products.value = result.products
    status.value = result.products.length >= 2 ? 'ready' : 'too-few'
  } catch {
    status.value = 'error'
  }
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('MALL')
}

function open(productId: string): void {
  if (!query.value) return
  uni.navigateTo({ url: buildMallProductUrl(productId, query.value.cityCode) })
}

onLoad((options) => {
  query.value = FAVORITE_PREVIEW_ENABLED ? parseMallCompareQuery(options ?? {}, MAX_COMPARE_ITEMS) : null
  void load()
})
</script>

<template>
  <MallComparePage :status="status" :products="products" @back="goBack" @retry="load" @open="open" />
</template>
