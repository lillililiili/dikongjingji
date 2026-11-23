<script setup lang="ts">
/**
 * 开通了直播主办的机构（GET /api/v1/ops/live/hosts）。“开通直播主办”：选一家服务商机构、选机构里的一个账号，
 * 给它加上“直播主办”（LIVE_HOST）角色——调机构与资质的改角色接口，记在那家机构的操作记录里。收回去机构详情里改。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'

import { http } from '@/core/http'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { errorMessage } from '@/core/http/api-error'
import ProviderSelect from '@/features/organization/ProviderSelect.vue'
import {
  createOrganizationApi,
  type GrantableRole,
  type OpsAccount,
  type OpsOrganizationDetail,
} from '@/features/organization/organization-api'

import { createLiveApi, type OpsLiveHost } from './live-api'
import { grantLiveHostRoles } from './live-hosts'

const emit = defineEmits<{
  'show-rooms': [organizationId: string]
}>()

const router = useRouter()
const api = createLiveApi(http)
const organizations = createOrganizationApi(http)
const hosts = ref<OpsLiveHost[]>([])
const loading = ref(false)
const error = ref('')

const grant = reactive({
  open: false,
  organizationId: '',
  detail: null as OpsOrganizationDetail | null,
  accountId: '',
  loadingDetail: false,
  busy: false,
})

/** 能授的账号：在用、还没有直播主办角色的。 */
const candidates = computed<OpsAccount[]>(() => (grant.detail?.accounts ?? [])
  .filter((account) => account.status === 'ACTIVE' && !account.roles.includes('LIVE_HOST')))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    hosts.value = await api.hosts()
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function openGrant(): void {
  Object.assign(grant, { open: true, organizationId: '', detail: null, accountId: '', loadingDetail: false, busy: false })
}

async function pickOrganization(organizationId: string): Promise<void> {
  grant.organizationId = organizationId
  grant.detail = null
  grant.accountId = ''
  if (!organizationId) return
  grant.loadingDetail = true
  try {
    grant.detail = await organizations.detail(organizationId)
  } catch (failure) {
    showError(failure)
  } finally {
    grant.loadingDetail = false
  }
}

async function submitGrant(): Promise<void> {
  const detail = grant.detail
  const account = candidates.value.find((candidate) => candidate.accountId === grant.accountId)
  if (!detail || !account || grant.busy) return
  if (!detail.grantableRoles.includes('LIVE_HOST')) {
    showError(new Error('这类机构不能授直播主办'))
    return
  }
  const confirmed = await confirmAction({
    title: '确认开通直播主办',
    message: `给“${detail.organization.name}”的账号“${account.displayName}”加上直播主办角色。之后这个账号能建直播间、改预告和讲解清单（账号重新登录后生效）。`,
    confirmText: '开通',
  })
  if (!confirmed) return
  grant.busy = true
  try {
    const roles: GrantableRole[] = grantLiveHostRoles(account.roles)
    await organizations.replaceRoles(detail.organization.organizationId, account.accountId, roles)
    grant.open = false
    notifyRecorded('开通直播主办', `机构与资质 › ${detail.organization.name} › 操作记录`)
    await load()
  } catch (failure) {
    showError(failure)
  } finally {
    grant.busy = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="hosts">
    <div class="hosts__head">
      <span class="hosts__hint">收回直播主办：进机构详情，在“账号”里去掉这个账号的“直播主办”角色。</span>
      <el-button type="primary" @click="openGrant">开通直播主办</el-button>
    </div>
    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon class="hosts__error">
      <el-button size="small" @click="load">重新加载</el-button>
    </el-alert>
    <el-table v-loading="loading" :data="hosts" row-key="organizationId" border empty-text="还没有机构开通直播主办">
      <el-table-column prop="organizationName" label="机构" min-width="200" />
      <el-table-column label="主办账号" width="100">
        <template #default="{ row }">{{ (row as OpsLiveHost).hostAccountCount }} 个</template>
      </el-table-column>
      <el-table-column label="直播间（直播中 / 预告 / 全部）" width="220">
        <template #default="{ row }">
          {{ (row as OpsLiveHost).liveRoomCount }} / {{ (row as OpsLiveHost).scheduledRoomCount }} / {{ (row as OpsLiveHost).roomCount }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200">
        <template #default="{ row }">
          <el-button link type="primary" @click="emit('show-rooms', (row as OpsLiveHost).organizationId)">看直播间</el-button>
          <el-button
            link
            @click="router.push({ name: 'organization-detail', params: { organizationId: (row as OpsLiveHost).organizationId } })"
          >
            管理账号
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="grant.open" title="开通直播主办" width="520px" :close-on-click-modal="false">
      <el-form label-width="80px" @submit.prevent>
        <el-form-item label="机构">
          <ProviderSelect :model-value="grant.organizationId" @update:model-value="pickOrganization" />
        </el-form-item>
        <el-form-item label="账号">
          <el-select
            v-model="grant.accountId"
            :loading="grant.loadingDetail"
            :disabled="!grant.detail"
            placeholder="选机构里的一个账号"
            style="width: 100%"
          >
            <el-option
              v-for="account in candidates"
              :key="account.accountId"
              :value="account.accountId"
              :label="`${account.displayName}（${account.loginName}）`"
            />
          </el-select>
          <span v-if="grant.detail && candidates.length === 0" class="hosts__hint">
            这家机构没有能授的账号（都已经是直播主办，或已停用）。先在机构详情里建账号。
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="grant.open = false">取消</el-button>
        <el-button type="primary" :loading="grant.busy" :disabled="!grant.accountId" @click="submitGrant">开通</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.hosts__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.hosts__hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.hosts__error {
  margin-bottom: 12px;
}
</style>
