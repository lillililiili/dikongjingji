<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import {
  createLookupApi,
  label,
  ORGANIZATION_TYPE_LABELS,
  ROLE_CODE_LABELS,
  USER_STATUS_LABELS,
  type LookupApi,
  type UserDetail,
} from './lookup-api'

const props = defineProps<{
  api?: LookupApi
}>()

const ENTERPRISE_ROLE_LABELS: Record<string, string> = { ADMIN: '管理员', MEMBER: '成员' }
const ENTERPRISE_STATUS_LABELS: Record<string, string> = { ACTIVE: '在职', LEFT: '已退出', REMOVED: '已移除' }
const REGISTERED_VIA_LABELS: Record<string, string> = { PHONE: '手机号注册', PASSWORD: '后台建号' }

const api = props.api ?? createLookupApi(http)
const route = useRoute()
const router = useRouter()
const user = shallowRef<UserDetail | null>(null)
const loading = ref(false)
const error = ref('')
const userId = computed(() => String(route.params.userId ?? ''))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    user.value = await api.user(userId.value)
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function openOrders(): void {
  if (!user.value) return
  void router.push({ name: 'ops-orders', query: { buyerId: user.value.id, buyerName: user.value.displayName } })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="user ? user.displayName : '用户详情'"
    :description="user ? `${USER_STATUS_LABELS[user.status]} · 注册于 ${formatDateTime(user.createdAt)}` : undefined"
  >
    <template #actions>
      <el-button v-if="user" type="primary" data-testid="user-lookup-orders" @click="openOrders">
        查看订单（{{ user.orderCount }}）
      </el-button>
      <el-button @click="load()">刷新</el-button>
      <el-button @click="router.push({ name: 'ops-users' })">返回查询</el-button>
    </template>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button size="small" @click="load()">重新加载</el-button>
    </el-alert>
    <div v-else-if="loading && !user" v-loading="true" class="placeholder" />
    <template v-else-if="user">
      <el-descriptions :column="3" border data-testid="user-lookup-basics">
        <el-descriptions-item label="账号 ID">{{ user.id }}</el-descriptions-item>
        <el-descriptions-item label="登录名">{{ user.loginName }}</el-descriptions-item>
        <el-descriptions-item label="手机号">{{ user.maskedPhone ?? '未绑定' }}</el-descriptions-item>
        <el-descriptions-item label="注册方式">{{ label(REGISTERED_VIA_LABELS, user.registeredVia) }}</el-descriptions-item>
        <el-descriptions-item label="角色">
          <el-tag v-for="role in user.roles" :key="role" class="role" effect="plain" size="small">
            {{ label(ROLE_CODE_LABELS, role) }}
          </el-tag>
          <span v-if="!user.roles.length">—</span>
        </el-descriptions-item>
        <el-descriptions-item label="状态">{{ USER_STATUS_LABELS[user.status] }}</el-descriptions-item>
        <el-descriptions-item label="近 30 天登录的设备">{{ user.activeDeviceCount }} 台</el-descriptions-item>
        <el-descriptions-item label="最近活跃">{{ formatDateTime(user.lastActiveAt) }}</el-descriptions-item>
        <el-descriptions-item label="交易订单">{{ user.orderCount }} 笔</el-descriptions-item>
        <el-descriptions-item v-if="user.closingDueAt" label="注销生效">{{ formatDateTime(user.closingDueAt) }}</el-descriptions-item>
        <el-descriptions-item v-if="user.closedAt" label="注销时间">{{ formatDateTime(user.closedAt) }}</el-descriptions-item>
      </el-descriptions>

      <h2 class="block-title">所属机构</h2>
      <el-table :data="user.organizations" row-key="id" border empty-text="不属于任何机构">
        <el-table-column label="机构" min-width="220" prop="name" />
        <el-table-column label="类型" width="140">
          <template #default="{ row }">{{ label(ORGANIZATION_TYPE_LABELS, row.type) }}</template>
        </el-table-column>
      </el-table>

      <h2 class="block-title">企业组织</h2>
      <el-table :data="user.enterprises" row-key="id" border empty-text="没有加入企业组织">
        <el-table-column label="企业" min-width="220" prop="name" />
        <el-table-column label="身份" width="120">
          <template #default="{ row }">{{ label(ENTERPRISE_ROLE_LABELS, row.memberRole) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">{{ label(ENTERPRISE_STATUS_LABELS, row.memberStatus) }}</template>
        </el-table-column>
      </el-table>
      <p class="hint">巡检预约、免费报名这类没有付款环节的记录不在“交易订单”里。</p>
    </template>
  </PageSection>
</template>

<style scoped>
.block-title {
  margin: 20px 0 10px;
  font-size: 15px;
}

.placeholder {
  min-height: 200px;
}

.role {
  margin-right: 4px;
}

.hint {
  margin: 10px 0 0;
  font-size: 13px;
  color: var(--admin-text-secondary);
}
</style>
