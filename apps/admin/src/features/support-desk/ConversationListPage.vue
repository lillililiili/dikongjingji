<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  COUNTERPART_LABELS,
  createSupportDeskApi,
  lastMessageLabel,
  type DeskConversation,
  type DeskConversationPage,
} from './support-desk-api'

const api = createSupportDeskApi(http)
const router = useRouter()
const list = usePagedList<DeskConversation, object>((filters, page, size) => api.conversations(filters, page, size), {})
const unread = computed(() => (list.last.value as DeskConversationPage | null)?.unreadCount ?? 0)

function open(row: DeskConversation): void {
  void router.push({ name: 'workbench-conversation', params: { conversationId: row.conversationId } })
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="客服会话"
    :description="`客户在 App 里找本机构（商家或服务商）发来的消息，最近有消息的在前。现在共有 ${unread} 条未读。`"
  >
    <template #actions>
      <el-button @click="list.load()">刷新</el-button>
    </template>
    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="conversationId"
      empty-text="还没有客户发来消息"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="客户" width="160">
        <template #default="{ row }">{{ (row as DeskConversation).customer.name }}</template>
      </el-table-column>
      <el-table-column label="客户找的是" width="110">
        <template #default="{ row }">{{ COUNTERPART_LABELS[(row as DeskConversation).counterpartKind] }}</template>
      </el-table-column>
      <el-table-column label="最后一条" min-width="300">
        <template #default="{ row }">{{ lastMessageLabel((row as DeskConversation).lastMessage) }}</template>
      </el-table-column>
      <el-table-column label="时间" width="150">
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
.muted {
  color: var(--admin-text-secondary);
}
</style>
