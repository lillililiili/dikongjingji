<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'

import {
  PROVIDER_PROFILE_STATUS_LABELS,
  providerProfileOpening,
  type OpsOrganizationDetail,
  type OrganizationApi,
} from './organization-api'

// 服务商资料（S6-01，契约 openapi-ops-organization.yaml 1.1.0）：资质审核通过的经营机构在这里开通；
// 开通后机构操作员在工作台“服务商资料”提交主页资料，平台运营在“服务商资料审核”里审。

const props = defineProps<{
  api: OrganizationApi
  detail: OpsOrganizationDetail
}>()

const emit = defineEmits<{
  changed: [detail: OpsOrganizationDetail]
}>()

const router = useRouter()
const busy = ref(false)

const opening = computed(() => providerProfileOpening(props.detail))
const status = computed(() => props.detail.providerProfileStatus
  ? PROVIDER_PROFILE_STATUS_LABELS[props.detail.providerProfileStatus]
  : null)

async function open(): Promise<void> {
  if (!opening.value.canOpen || busy.value) return
  const confirmed = await confirmAction({
    title: '确认开通服务商资料',
    message: `开通后“${props.detail.organization.name}”在 App 上有服务商主页，专业服务和巡检服务详情显示服务商，`
      + '能被需求邀请报价；机构操作员可以在工作台“服务商资料”提交主页资料，等平台审核。',
    confirmText: '开通',
  })
  if (!confirmed) return
  busy.value = true
  try {
    emit('changed', await props.api.openProviderProfile(props.detail.organization.organizationId))
    notifyRecorded('开通服务商资料', '操作记录')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

// 机构还没提交过主页资料时审核详情是 404，所以去审核列表（待审核的排在前面）。
function review(): void {
  void router.push({ name: 'provider-profile-reviews' })
}
</script>

<template>
  <div class="provider-profile">
    <el-descriptions :column="2" border size="small">
      <el-descriptions-item label="服务商资料">
        <el-tag v-if="status" :type="status.tag">{{ status.label }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="资质">
        {{ detail.organization.qualificationReviewedAt ? '已审核通过' : '未审核通过' }}
      </el-descriptions-item>
    </el-descriptions>

    <p class="hint">
      服务商资料开通后，App 的服务商主页能打开，专业服务和巡检服务详情显示这家服务商，需求发布时能邀请它报价。
      主页上的经营主体、服务方式、可交付清单由机构在工作台“服务商资料”提交，平台在“服务商资料审核”里审核通过后才显示。
    </p>

    <div class="provider-profile__actions">
      <el-button
        v-if="detail.providerProfileStatus !== 'ACTIVE'"
        type="primary"
        :loading="busy"
        :disabled="!opening.canOpen"
        data-test="open-provider-profile"
        @click="open"
      >
        {{ detail.providerProfileStatus === 'DISABLED' ? '恢复服务商资料' : '开通服务商资料' }}
      </el-button>
      <el-button v-else data-test="review-provider-profile" @click="review">去“服务商资料审核”</el-button>
      <span v-if="!opening.canOpen && opening.reason && detail.providerProfileStatus !== 'ACTIVE'" class="hint">
        {{ opening.reason }}
      </span>
    </div>
  </div>
</template>

<style scoped>
.provider-profile {
  display: flex;
  flex-direction: column;
  gap: 12px;
  max-width: 720px;
}

.provider-profile__actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.hint {
  margin: 0;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
