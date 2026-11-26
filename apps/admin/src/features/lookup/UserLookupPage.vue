<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'
import { useLookupPosition } from './lookup-return'

import {
  createLookupApi,
  label,
  ROLE_CODE_LABELS,
  USER_QUERY_MAX,
  USER_STATUS_LABELS,
  type LookupApi,
  type UserFilters,
  type UserSummary,
} from './lookup-api'

const props = defineProps<{
  api?: LookupApi
}>()

const api = props.api ?? createLookupApi(http)
const router = useRouter()
const list = usePagedList<UserSummary, UserFilters>((filters, page, size) => api.users(filters, page, size),
  { q: '' })
useLookupPosition(list, 'ops-user-detail')

function open(user: UserSummary): void {
  void router.push({ name: 'ops-user-detail', params: { userId: user.id } })
}

function statusType(user: UserSummary): 'success' | 'warning' | 'info' | 'danger' {
  if (user.status === 'ACTIVE') return 'success'
  if (user.status === 'CLOSING') return 'warning'
  if (user.status === 'CLOSED') return 'info'
  return 'danger'
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="用户查询"
    description="按手机号、账号 ID 或登录名精确查，按显示名模糊查。只能看，不能改资料或停用账号。手机号已打码；打开用户详情会记录查看人和时间。"
  >
    <div class="toolbar">
      <el-input
        v-model="list.filters.q"
        data-testid="user-lookup-q"
        clearable
        :maxlength="USER_QUERY_MAX"
        placeholder="手机号 / 账号 ID / 登录名 / 显示名"
        style="width: 320px"
        @keyup.enter="list.search()"
        @clear="list.search()"
      />
      <el-button type="primary" data-testid="user-lookup-search" @click="list.search()">查询</el-button>
    </div>

    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="id"
      empty-text="没有查到账号"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="显示名" min-width="160">
        <template #default="{ row }">
          <el-button link type="primary" @click="open(row as UserSummary)">{{ (row as UserSummary).displayName }}</el-button>
        </template>
      </el-table-column>
      <el-table-column label="手机号" width="130">
        <template #default="{ row }">{{ (row as UserSummary).maskedPhone ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="登录名" min-width="150" prop="loginName" />
      <el-table-column label="角色" min-width="160">
        <template #default="{ row }">
          <el-tag v-for="role in (row as UserSummary).roles" :key="role" class="role" effect="plain" size="small">
            {{ label(ROLE_CODE_LABELS, role) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="statusType(row as UserSummary)">{{ USER_STATUS_LABELS[(row as UserSummary).status] }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="注册时间" width="150">
        <template #default="{ row }">{{ formatDateTime((row as UserSummary).createdAt) }}</template>
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

.role {
  margin-right: 4px;
}
</style>
