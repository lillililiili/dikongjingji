<script setup lang="ts">
import { computed, onMounted, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import AccountsPanel from './AccountsPanel.vue'
import {
  ACTION_LABELS,
  createOrganizationApi,
  organizationTypeLabel,
  qualificationStatus,
  type OpsOrganizationDetail,
} from './organization-api'
import ProviderProfilePanel from './ProviderProfilePanel.vue'
import QualificationPanel from './QualificationPanel.vue'

const route = useRoute()
const router = useRouter()
const api = createOrganizationApi(http)
const detail = shallowRef<OpsOrganizationDetail | null>(null)
const loading = ref(false)
const error = ref('')
const tab = ref('qualification')

const organizationId = computed(() => String(route.params.organizationId ?? ''))

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    detail.value = await api.detail(organizationId.value)
  } catch (failure) {
    detail.value = null
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

function changed(next: OpsOrganizationDetail): void {
  detail.value = next
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    :title="detail?.organization.name ?? '机构详情'"
    :description="detail ? `${organizationTypeLabel(detail.organization.type)} · ${detail.organization.cityName ?? '未填城市'} · ${detail.organization.organizationCode}` : undefined"
    traced
  >
    <template #actions>
      <el-button @click="router.push({ name: 'organizations' })">返回列表</el-button>
    </template>

    <div v-loading="loading">
      <el-result v-if="error && !detail" icon="error" :title="error">
        <template #extra>
          <el-button type="primary" @click="load">重新加载</el-button>
        </template>
      </el-result>

      <template v-if="detail">
        <div class="summary">
          <el-tag :type="qualificationStatus(detail.organization.qualificationStatus).tag" size="large">
            资质{{ qualificationStatus(detail.organization.qualificationStatus).label }}
          </el-tag>
          <span>新建于 {{ formatDateTime(detail.organization.createdAt) }}</span>
          <span>{{ detail.organization.status === 'ACTIVE' ? '在用' : '已停用' }}</span>
        </div>
        <el-tabs v-model="tab">
          <el-tab-pane label="资质资料与审核" name="qualification">
            <QualificationPanel :api="api" :detail="detail" @changed="changed" />
          </el-tab-pane>
          <el-tab-pane v-if="detail.organization.type === 'PROVIDER'" label="服务商资料" name="provider-profile">
            <ProviderProfilePanel :api="api" :detail="detail" @changed="changed" />
          </el-tab-pane>
          <el-tab-pane :label="`账号与角色（${detail.accounts.length}）`" name="accounts">
            <AccountsPanel :api="api" :detail="detail" @changed="changed" @reload="load" />
          </el-tab-pane>
          <el-tab-pane label="操作记录" name="operations">
            <el-empty v-if="detail.operations.length === 0" description="还没有操作记录" />
            <el-timeline v-else>
              <el-timeline-item
                v-for="operation in detail.operations"
                :key="operation.operationId"
                :timestamp="`${formatDateTime(operation.occurredAt)} · ${operation.operatorName}`"
                placement="top"
              >
                <strong>{{ ACTION_LABELS[operation.action] }}</strong>
                <p class="operation__detail">{{ operation.detail }}</p>
              </el-timeline-item>
            </el-timeline>
            <p class="hint">显示最近 50 条。</p>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </PageSection>
</template>

<style scoped>
.summary {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 8px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.operation__detail {
  margin: 4px 0 0;
  color: var(--admin-text-secondary);
}

.hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
