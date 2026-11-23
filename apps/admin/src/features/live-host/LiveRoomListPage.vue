<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import {
  createLiveHostApi,
  HOST_PAGE_MAX,
  STATUS_OPTIONS,
  statusLabel,
  type LiveHostRoom,
  type LiveRoomFilters,
} from './live-host-api'
import LiveRoomFormDialog from './LiveRoomFormDialog.vue'

const router = useRouter()
const api = createLiveHostApi(http)
// 后端每页最多 20 条：每页条数选 50 时也只取 20 条。
const list = usePagedList<LiveHostRoom, LiveRoomFilters>((filters, page, size) => api.list(filters, page, size),
  { status: '' }, HOST_PAGE_MAX)
const creating = ref(false)

function setStatus(value: string | number | boolean | undefined): void {
  list.filters.status = (value as LiveRoomFilters['status']) ?? ''
  void list.search()
}

function open(roomId: string): void {
  void router.push({ name: 'workbench-live-room', params: { roomId } })
}

function created(room: LiveHostRoom): void {
  creating.value = false
  open(room.room.roomId)
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="直播间"
    description="本机构的直播间。开播、断流、结束由直播服务商的推流状态决定，这里不能手动开播；平台客服可以隐藏弹幕或强制结束。"
  >
    <template #actions>
      <el-button type="primary" @click="creating = true">建直播间</el-button>
    </template>
    <div class="toolbar">
      <el-radio-group :model-value="list.filters.status" @change="setStatus">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button v-for="option in STATUS_OPTIONS" :key="option.value" :value="option.value">
          {{ option.label }}
        </el-radio-button>
      </el-radio-group>
    </div>
    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="room.roomId"
      empty-text="还没有直播间"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="标题" min-width="220">
        <template #default="{ row }">
          <div>{{ (row as LiveHostRoom).room.title }}</div>
          <div v-if="(row as LiveHostRoom).room.subtitle" class="muted">{{ (row as LiveHostRoom).room.subtitle }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="(row as LiveHostRoom).room.status === 'LIVE' ? 'danger' : 'info'" effect="plain">
            {{ statusLabel((row as LiveHostRoom).room.status) }}
          </el-tag>
          <div v-if="(row as LiveHostRoom).room.streamInterrupted" class="muted">断流中</div>
        </template>
      </el-table-column>
      <el-table-column label="开播时间" width="160">
        <template #default="{ row }">{{ formatDateTime((row as LiveHostRoom).room.startedAt ?? (row as LiveHostRoom).room.scheduledAt) }}</template>
      </el-table-column>
      <el-table-column label="观看 / 点赞" width="130">
        <template #default="{ row }">{{ (row as LiveHostRoom).room.viewerCount }} / {{ (row as LiveHostRoom).room.likeCount }}</template>
      </el-table-column>
      <el-table-column label="讲解项" width="90">
        <template #default="{ row }">{{ (row as LiveHostRoom).room.items.length }} 项</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="open((row as LiveHostRoom).room.roomId)">管理</el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>

  <LiveRoomFormDialog v-if="creating" :api="api" @saved="created" @close="creating = false" />
</template>

<style scoped>
.toolbar {
  margin-bottom: 16px;
}

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
