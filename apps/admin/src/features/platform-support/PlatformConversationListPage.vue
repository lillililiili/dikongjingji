<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'
import {
  createSupportDeskApi,
  lastMessageLabel,
  type DeskConversation,
  type DeskConversationFilters,
  type DeskConversationPage,
  type DeskSort,
} from '@/features/support-desk/support-desk-api'

const api = createSupportDeskApi(http, 'platform')
const router = useRouter()
// 默认未读优先：有客户在等回复的会话排在最前面，各自再按最后一条消息新的在前。
const list = usePagedList<DeskConversation, DeskConversationFilters>(
  (filters, page, size) => api.conversations(filters, page, size), { sort: 'UNREAD_FIRST' })
const unread = computed(() => (list.last.value as DeskConversationPage | null)?.unreadCount ?? 0)

function setSort(value: string | number | boolean | undefined): void {
  list.filters.sort = (value as DeskSort | undefined) ?? 'UNREAD_FIRST'
  void list.search()
}

function open(row: DeskConversation): void {
  void router.push({ name: 'support-conversation', params: { conversationId: row.conversationId } })
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="客服会话"
    description="客户在 App 里找“平台客服”发来的消息（找商家、服务商的会话在机构工作台，不在这里）。客户只看到“平台客服”，看不到是谁回的；平台只协助沟通，不裁决。"
  >
    <template #actions>
      <el-button @click="list.load()">刷新</el-button>
    </template>
    <div class="toolbar">
      <el-radio-group :model-value="list.filters.sort" @change="setSort">
        <el-radio-button value="UNREAD_FIRST">未读优先（{{ unread }} 条未读）</el-radio-button>
        <el-radio-button value="LATEST">按最后消息时间</el-radio-button>
      </el-radio-group>
    </div>
    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="conversationId"
      empty-text="还没有客户找平台客服"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="客户" width="160">
        <template #default="{ row }">{{ (row as DeskConversation).customer.name }}</template>
      </el-table-column>
      <el-table-column label="最后一条" min-width="320">
        <template #default="{ row }">{{ lastMessageLabel((row as DeskConversation).lastMessage) }}</template>
      </el-table-column>
      <el-table-column label="最后消息时间" width="160">
        <template #default="{ row }">{{ formatDateTime((row as DeskConversation).lastMessage?.sentAt) }}</template>
      </el-table-column>
      <el-table-column label="未读" width="80">
        <template #default="{ row }">
          <el-badge v-if="(row as DeskConversation).unreadCount > 0" :value="(row as DeskConversation).unreadCount" />
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as DeskConversation)">回复</el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.muted {
  color: var(--admin-text-secondary);
}
</style>
