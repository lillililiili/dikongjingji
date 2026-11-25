<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import { displayMediaUrl } from '@/core/ui/media-url'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  CATALOG_LIMITS,
  CATEGORY_OPTIONS,
  categoryLabel,
  createCatalogApi,
  MERCHANT_CATALOG_BASE,
  OPS_CATALOG_BASE,
  PRODUCT_STATUS_OPTIONS,
  productStatus,
  SALE_MODE_OPTIONS,
  saleModeLabel,
  type OpsProductDetail,
  type OpsProductSummary,
  type ProductFilters,
} from './catalog-api'
import ProductCreateDialog from './ProductCreateDialog.vue'
import { confirmAndRemoveDraft } from './remove-draft'

/** merchant：商家工作台“我的商品”（只列本机构建的，接口前缀不同，详情页也在工作台下）。 */
const props = defineProps<{
  merchant?: boolean
}>()

const router = useRouter()
const api = createCatalogApi(http, props.merchant ? MERCHANT_CATALOG_BASE : OPS_CATALOG_BASE)
const list = usePagedList<OpsProductSummary, ProductFilters>((filters, page, size) => api.list(filters, page, size),
  { keyword: '', category: '', saleMode: '', status: '' })
const creating = ref(false)

function open(productId: string): void {
  void router.push({ name: props.merchant ? 'workbench-product-detail' : 'catalog-product-detail', params: { productId } })
}

function created(detail: OpsProductDetail): void {
  creating.value = false
  open(detail.product.productId)
}

const removing = ref('')

async function remove(product: OpsProductSummary): Promise<void> {
  if (removing.value) return
  removing.value = product.productId
  try {
    if (!(await confirmAndRemoveDraft(api, product))) return
    // 删的是这一页最后一条：退回上一页，不停在空页上。
    if (list.items.value.length === 1 && list.page.value > 1) list.changePage(list.page.value - 1)
    else await list.load()
  } finally {
    removing.value = ''
  }
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    :title="merchant ? '我的商品' : '商品与供给'"
    :description="merchant
      ? '本机构自己建的商品：资料、规格、在售供给（规格 × 城市 × 价格 × 库存）和上下架。新建的商品是草稿，补齐必填、启用供给，并且机构资质审核通过后才能上架；标价商品启用供给前还要平台登记收款账户。草稿可以删除，上架过的只能下架。'
      : '商品资料、规格、在售供给（商家 × 规格 × 城市 × 价格 × 库存）和上下架。新建的商品是草稿，补齐必填并启用供给后才能上架。商家在工作台自己建的商品也在这里，带“建自商家”。'"
    traced
  >
    <template #actions>
      <el-button type="primary" @click="creating = true">新建商品</el-button>
    </template>

    <el-form inline @submit.prevent="list.search()">
      <el-form-item label="商品">
        <el-input v-model="list.filters.keyword" clearable :maxlength="CATALOG_LIMITS.keyword" placeholder="商品名称"
                  @clear="list.search()" />
      </el-form-item>
      <el-form-item label="类目">
        <el-select v-model="list.filters.category" clearable placeholder="全部" style="width: 120px"
                   @change="list.search()">
          <el-option v-for="option in CATEGORY_OPTIONS" :key="option.value" :label="option.label"
                     :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="交易方式">
        <el-select v-model="list.filters.saleMode" clearable placeholder="全部" style="width: 120px"
                   @change="list.search()">
          <el-option v-for="option in SALE_MODE_OPTIONS" :key="option.value" :label="option.label"
                     :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="list.filters.status" clearable placeholder="全部" style="width: 120px"
                   @change="list.search()">
          <el-option v-for="option in PRODUCT_STATUS_OPTIONS" :key="option.value" :label="option.label"
                     :value="option.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" native-type="submit">查询</el-button>
        <el-button @click="list.reset()">重置</el-button>
      </el-form-item>
    </el-form>

    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="productId"
      empty-text="没有符合条件的商品"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="商品" :min-width="merchant ? 200 : 260">
        <template #default="{ row }">
          <div class="product">
            <img v-if="(row as OpsProductSummary).coverUrl" class="product__cover"
                 :src="displayMediaUrl((row as OpsProductSummary).coverUrl)" alt="" />
            <div v-else class="product__cover product__cover--empty" />
            <el-button class="product__name" link type="primary"
                       @click="open((row as OpsProductSummary).productId)">
              {{ (row as OpsProductSummary).name }}
            </el-button>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="类目" width="90">
        <template #default="{ row }">{{ categoryLabel((row as OpsProductSummary).category) }}</template>
      </el-table-column>
      <el-table-column label="交易方式" width="100">
        <template #default="{ row }">{{ saleModeLabel((row as OpsProductSummary).saleMode) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="productStatus((row as OpsProductSummary).status).tag">
            {{ productStatus((row as OpsProductSummary).status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="!merchant" label="建自商家" min-width="140">
        <template #default="{ row }">{{ (row as OpsProductSummary).ownerName ?? '平台' }}</template>
      </el-table-column>
      <el-table-column label="启用规格" width="90" prop="skuCount" />
      <el-table-column label="启用供给" width="90" prop="activeOfferCount" />
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ formatDateTime((row as OpsProductSummary).updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" :width="merchant ? 120 : 80" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" data-testid="product-edit"
                     @click="open((row as OpsProductSummary).productId)">编辑</el-button>
          <el-button v-if="merchant && (row as OpsProductSummary).status === 'DRAFT'" link type="danger"
                     :disabled="removing !== ''" data-testid="product-remove"
                     @click="remove(row as OpsProductSummary)">删除</el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>

  <ProductCreateDialog :api="api" :open="creating" @close="creating = false" @created="created" />
</template>

<style scoped>
.product {
  display: flex;
  align-items: center;
  gap: 10px;
}

/* 名字长的换行，不被截断。 */
.product__name {
  height: auto;
  min-width: 0;
  line-height: 1.4;
  white-space: normal;
  text-align: left;
}

.product__cover {
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 6px;
  object-fit: cover;
  background: #eef2f8;
}
</style>
