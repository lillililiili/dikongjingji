<script setup lang="ts">
import type { FormInstance, UploadRawFile } from 'element-plus'
import { computed, onMounted, reactive, ref, shallowRef } from 'vue'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatBytes, formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'
import {
  PROVIDER_PROFILE_STATUS_LABELS,
  qualificationStatus,
  type QualificationDraft,
} from '@/features/organization/organization-api'
import { attachmentProblem, LIMITS, qualificationRules } from '@/features/organization/organization-rules'

import { createOrganizationSelfApi, type OrganizationSelfApi, type OwnOrganization } from './organization-self-api'

const props = defineProps<{
  api?: OrganizationSelfApi
}>()

const api = props.api ?? createOrganizationSelfApi(http)
const state = shallowRef<OwnOrganization | null>(null)
const loading = ref(false)
const error = ref('')
const busy = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<QualificationDraft>({ subjectName: '', licenseNumber: '', businessScope: '', qualificationNote: '' })

const qualification = computed(() => state.value?.qualification ?? null)
const status = computed(() => qualificationStatus(state.value?.organization.qualificationStatus ?? 'NOT_SUBMITTED'))
const approved = computed(() => state.value?.organization.qualificationStatus === 'APPROVED')
/** 已有审核结果（通过或驳回）时，改资料、动附件都会回到待审核。 */
const reviewed = computed(() => state.value?.organization.qualificationStatus === 'APPROVED'
  || state.value?.organization.qualificationStatus === 'REJECTED')
const REOPEN_WARNING = '资料有改动，资质会回到“待审核”，App 上的“资质已审核”先撤下，审核通过后再显示。'

function apply(next: OwnOrganization): void {
  state.value = next
  form.subjectName = next.qualification?.subjectName ?? next.organization.name
  form.licenseNumber = next.qualification?.licenseNumber ?? ''
  form.businessScope = next.qualification?.businessScope ?? ''
  form.qualificationNote = next.qualification?.qualificationNote ?? ''
  formRef.value?.clearValidate()
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    apply(await api.read())
  } catch (failure) {
    state.value = null
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function run(action: () => Promise<OwnOrganization>, done: string): Promise<void> {
  busy.value = true
  try {
    apply(await action())
    notifyRecorded(done, '平台的机构操作记录')
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

async function save(): Promise<void> {
  if (!formRef.value || busy.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const confirmed = await confirmAction({
    title: qualification.value ? '确认修改资质资料' : '确认提交资质资料',
    message: `保存后进入待审核，由平台审核。${reviewed.value ? REOPEN_WARNING : ''}`,
    confirmText: '保存',
    danger: reviewed.value,
  })
  if (!confirmed) return
  await run(() => api.submitQualification(form, qualification.value?.version ?? null), '保存资质资料')
}

async function upload(raw: UploadRawFile): Promise<boolean> {
  const problem = attachmentProblem(raw, state.value?.attachments.length ?? 0)
  if (problem) {
    showError(new Error(problem))
    return false
  }
  if (reviewed.value && !(await confirmAction({ title: '确认上传附件', message: REOPEN_WARNING, danger: true }))) {
    return false
  }
  await run(() => api.uploadAttachment(raw), `上传附件“${raw.name}”`)
  return false
}

async function remove(attachmentId: string, fileName: string): Promise<void> {
  const confirmed = await confirmAction({
    title: '确认移除附件',
    message: `移除“${fileName}”？${reviewed.value ? REOPEN_WARNING : ''}`,
    confirmText: '移除',
    danger: true,
  })
  if (!confirmed) return
  await run(() => api.removeAttachment(attachmentId), `移除附件“${fileName}”`)
}

async function download(attachmentId: string, fileName: string): Promise<void> {
  try {
    const file = await api.downloadAttachment(attachmentId)
    const url = URL.createObjectURL(file.blob)
    const link = document.createElement('a')
    link.href = url
    link.download = file.fileName ?? fileName
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
  } catch (failure) {
    showError(failure)
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    title="入驻资料"
    description="本机构的资质资料和营业执照等附件。平台审核通过后，App 上显示“资质已审核”，商品才能上架。"
    traced
  >
    <div v-loading="loading">
      <el-result v-if="error && !state" icon="error" :title="error">
        <template #extra>
          <el-button type="primary" @click="load">重新加载</el-button>
        </template>
      </el-result>
      <div v-if="state" class="self">
        <el-alert
          v-if="qualification?.status === 'REJECTED'"
          type="error"
          :closable="false"
          show-icon
          :title="`平台已驳回：${qualification.rejectReason}`"
          description="按原因改好资料或附件后保存，会重新进入待审核。"
        />
        <el-alert
          v-else-if="!approved"
          type="warning"
          :closable="false"
          show-icon
          title="资质待平台审核"
          :description="state.attachments.length === 0
            ? '请先上传营业执照（和行业资质）附件：平台审核通过前至少要有一份附件。审核通过前可以先建草稿商品，但不能上架。'
            : '平台会核对资料和附件；审核通过前可以先建草稿商品，但不能上架。'"
        />
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="机构名称">{{ state.organization.name }}</el-descriptions-item>
          <el-descriptions-item label="机构编码">{{ state.organization.organizationCode }}</el-descriptions-item>
          <el-descriptions-item label="所在城市">{{ state.organization.cityName ?? state.organization.cityCode ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="资质状态">
            <el-tag :type="status.tag" data-testid="qualification-status">{{ status.label }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ formatDateTime(qualification?.submittedAt) }}</el-descriptions-item>
          <el-descriptions-item label="审核通过">
            {{ formatDateTime(state.organization.qualificationReviewedAt) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="state.providerProfileStatus" label="服务商资料" :span="3">
            <el-tag :type="PROVIDER_PROFILE_STATUS_LABELS[state.providerProfileStatus].tag" effect="plain">
              {{ PROVIDER_PROFILE_STATUS_LABELS[state.providerProfileStatus].label }}
            </el-tag>
            <span class="hint">由平台在资质审核通过后开通；开通后可在“服务商资料”里填主页。</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-form ref="formRef" :model="form" :rules="qualificationRules" label-width="132px" class="self__form">
          <el-form-item label="主体名称" prop="subjectName">
            <el-input v-model="form.subjectName" :maxlength="LIMITS.subjectName" placeholder="营业执照上的名称" />
          </el-form-item>
          <el-form-item label="统一社会信用代码" prop="licenseNumber">
            <el-input v-model="form.licenseNumber" maxlength="18" placeholder="18 位，营业执照上的代码" />
          </el-form-item>
          <el-form-item label="经营范围" prop="businessScope">
            <el-input v-model="form.businessScope" type="textarea" :rows="2" :maxlength="LIMITS.businessScope"
                      show-word-limit placeholder="可空" />
          </el-form-item>
          <el-form-item label="行业资质说明" prop="qualificationNote">
            <el-input v-model="form.qualificationNote" type="textarea" :rows="2" :maxlength="LIMITS.qualificationNote"
                      show-word-limit placeholder="可空，比如民用无人机运营合格证编号、有效期" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="busy" @click="save">
              {{ qualification ? '保存修改' : '提交资料' }}
            </el-button>
          </el-form-item>
        </el-form>

        <div class="self__attachments">
          <div class="self__attachments-head">
            <h3>资质附件</h3>
            <span class="hint">PDF、JPEG、PNG，单个 ≤10 MB，最多 {{ LIMITS.attachments }} 份；审核通过前至少一份。</span>
            <el-upload
              :show-file-list="false"
              accept="application/pdf,image/jpeg,image/png"
              :before-upload="upload"
              :disabled="busy"
            >
              <el-button :loading="busy">上传附件</el-button>
            </el-upload>
          </div>
          <el-table :data="state.attachments" row-key="attachmentId" empty-text="还没有附件" size="small" border>
            <el-table-column prop="fileName" label="文件" min-width="220" />
            <el-table-column label="大小" width="100">
              <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
            </el-table-column>
            <el-table-column label="上传时间" width="160">
              <template #default="{ row }">{{ formatDateTime(row.uploadedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="150">
              <template #default="{ row }">
                <el-button link type="primary" @click="download(row.attachmentId, row.fileName)">下载</el-button>
                <el-button link type="danger" :disabled="busy" @click="remove(row.attachmentId, row.fileName)">
                  移除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </div>
  </PageSection>
</template>

<style scoped>
.self {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.self__form {
  max-width: 720px;
}

.self__attachments-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.self__attachments-head h3 {
  margin: 0;
  font-size: 15px;
}

.hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
