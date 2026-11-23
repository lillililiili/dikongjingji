<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import { formatYuan } from '@/core/ui/money'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  AFTERSALE_TABS,
  createAftersaleApi,
  dueHint,
  KIND_LABELS,
  REASON_LABELS,
  STATUS_LABELS,
  STATUS_TAGS,
  type Aftersale,
  type AftersaleApi,
  type AftersaleFilters,
  type AftersaleTabKey,
} from './aftersale-api'
import ReturnAddressDialog from './ReturnAddressDialog.vue'

const props = defineProps<{
  /** 测试时传入；页面里用全站的接口客户端。 */
  api?: AftersaleApi
}>()

const api = props.api ?? createAftersaleApi(http)
const router = useRouter()
const list = usePagedList<Aftersale, AftersaleFilters>((filters, page, size) => api.list(filters, page, size),
  { tab: 'PENDING_REVIEW' })
const addressOpen = ref(false)

function setTab(value: string | number | boolean | undefined): void {
  list.filters.tab = (value as AftersaleTabKey) ?? 'ALL'
  void list.search()
}

function open(aftersale: Aftersale): void {
  void router.push({ name: 'workbench-aftersale', params: { aftersaleId: aftersale.aftersaleId } })
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="售后"
    description="本机构卖出的商品的售后申请。退货退款由你同意或拒绝、收货验货后原路退款；仅退款（未发货）买家申请即退，不用处理。平台只协助沟通，不裁决。"
  >
    <template #actions>
      <el-button @click="addressOpen = true">默认寄回地址</el-button>
    </template>

    <div class="toolbar">
      <el-radio-group :model-value="list.filters.tab" @change="setTab">
        <el-radio-button v-for="tab in AFTERSALE_TABS" :key="tab.key" :value="tab.key">{{ tab.label }}</el-radio-button>
      </el-radio-group>
      <el-button @click="list.load()">刷新</el-button>
    </div>

    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="aftersaleId"
      empty-text="没有这类售后单"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="售后编号" width="190">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as Aftersale)">{{ (row as Aftersale).aftersaleNo }}</el-button>
        </template>
      </el-table-column>
      <el-table-column label="订单号" width="180">
        <template #default="{ row }">{{ (row as Aftersale).orderNo }}</template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ KIND_LABELS[(row as Aftersale).kind] }}</template>
      </el-table-column>
      <el-table-column label="原因" min-width="160">
        <template #default="{ row }">{{ REASON_LABELS[(row as Aftersale).reason] }}</template>
      </el-table-column>
      <el-table-column label="申请金额" width="120" align="right">
        <template #default="{ row }">{{ formatYuan((row as Aftersale).requestedMinor) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="150">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAGS[(row as Aftersale).status]" effect="plain">
            {{ STATUS_LABELS[(row as Aftersale).status] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="提交时间" width="150">
        <template #default="{ row }">{{ formatDateTime((row as Aftersale).submittedAt) }}</template>
      </el-table-column>
      <el-table-column label="处理时限" width="190">
        <template #default="{ row }">
          <template v-if="(row as Aftersale).dueAt">
            <div>{{ formatDateTime((row as Aftersale).dueAt) }}</div>
            <div class="due-hint">{{ dueHint((row as Aftersale).status) }}</div>
          </template>
          <template v-else>—</template>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as Aftersale)">
            {{ (row as Aftersale).actions.canReview || (row as Aftersale).actions.canConfirmReceipt ? '处理' : '查看' }}
          </el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>

  <ReturnAddressDialog :api="api" :open="addressOpen" @close="addressOpen = false" @saved="addressOpen = false" />
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.due-hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
