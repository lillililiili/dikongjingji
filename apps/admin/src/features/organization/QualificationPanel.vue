<script setup lang="ts">
import type { FormInstance, UploadRawFile } from 'element-plus'
import { computed, reactive, ref, watch } from 'vue'

import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { formatBytes, formatDateTime } from '@/core/ui/format'

import {
  qualificationStatus,
  type OpsOrganizationDetail,
  type OrganizationApi,
  type QualificationDraft,
} from './organization-api'
import { attachmentProblem, LIMITS, qualificationRules, rejectRules } from './organization-rules'

const props = defineProps<{
  api: OrganizationApi
  detail: OpsOrganizationDetail
}>()

const emit = defineEmits<{
  changed: [detail: OpsOrganizationDetail]
}>()

const formRef = ref<FormInstance>()
const rejectRef = ref<FormInstance>()
const form = reactive<QualificationDraft>({ subjectName: '', licenseNumber: '', businessScope: '', qualificationNote: '' })
const reject = reactive({ open: false, reason: '' })
const busy = ref(false)

const organizationId = computed(() => props.detail.organization.organizationId)
const qualification = computed(() => props.detail.qualification)
const status = computed(() => qualificationStatus(props.detail.organization.qualificationStatus))
const pending = computed(() => qualification.value?.status === 'SUBMITTED')
/** 已有审核结果（通过或驳回）时，改资料、动附件都会回到待审核。 */
const reviewed = computed(() => props.detail.organization.qualificationStatus === 'APPROVED'
  || props.detail.organization.qualificationStatus === 'REJECTED')

watch(qualification, (current) => {
  form.subjectName = current?.subjectName ?? props.detail.organization.name
  form.licenseNumber = current?.licenseNumber ?? ''
  form.businessScope = current?.businessScope ?? ''
  form.qualificationNote = current?.qualificationNote ?? ''
  formRef.value?.clearValidate()
}, { immediate: true })

const REOPEN_WARNING = '资料有改动，资质会回到“待审核”，App 上的“资质已审核”先撤下，审核通过后再显示。'

async function run(action: () => Promise<OpsOrganizationDetail>, done: string): Promise<void> {
  busy.value = true
  try {
    emit('changed', await action())
    notifyRecorded(done, '操作记录')
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
    message: `保存后进入待审核。${reviewed.value ? REOPEN_WARNING : ''}`,
    confirmText: '保存',
    danger: reviewed.value,
  })
  if (!confirmed) return
  await run(() => props.api.submitQualification(organizationId.value, form, qualification.value?.version ?? null),
    '保存资质资料')
}

async function approve(): Promise<void> {
  const current = qualification.value
  if (!current || busy.value) return
  const confirmed = await confirmAction({
    title: '确认审核通过',
    message: `已线下核对营业执照（${current.licenseNumber}）和行业资质？通过后 App 上这家机构显示“资质已审核”。`,
    confirmText: '审核通过',
  })
  if (!confirmed) return
  await run(() => props.api.review(organizationId.value, 'APPROVE', '', current.version), '审核通过')
}

async function submitReject(): Promise<void> {
  const current = qualification.value
  if (!current || !rejectRef.value || busy.value) return
  if (!(await rejectRef.value.validate().catch(() => false))) return
  const confirmed = await confirmAction({
    title: '确认驳回',
    message: '驳回后资质状态是“已驳回”，原因会记在操作记录里；改好资料后可以重新提交。',
    confirmText: '驳回',
    danger: true,
  })
  if (!confirmed) return
  reject.open = false
  await run(() => props.api.review(organizationId.value, 'REJECT', reject.reason, current.version), '驳回资质')
  reject.reason = ''
}

async function upload(raw: UploadRawFile): Promise<boolean> {
  const problem = attachmentProblem(raw, props.detail.attachments.length)
  if (problem) {
    showError(new Error(problem))
    return false
  }
  if (reviewed.value && !(await confirmAction({ title: '确认上传附件', message: REOPEN_WARNING, danger: true }))) {
    return false
  }
  await run(() => props.api.uploadAttachment(organizationId.value, raw), `上传附件“${raw.name}”`)
  // 已经自己上传了，不让 el-upload 再传一次。
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
  await run(() => props.api.removeAttachment(organizationId.value, attachmentId), `移除附件“${fileName}”`)
}

async function download(attachmentId: string, fileName: string): Promise<void> {
  try {
    const file = await props.api.downloadAttachment(organizationId.value, attachmentId)
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
</script>

<template>
  <div class="qualification">
    <el-alert
      v-if="qualification?.status === 'REJECTED'"
      type="error"
      :closable="false"
      show-icon
      :title="`已驳回：${qualification.rejectReason}`"
      description="改好资料或附件后保存，会重新进入待审核。"
    />
    <el-descriptions :column="3" border size="small">
      <el-descriptions-item label="资质状态">
        <el-tag :type="status.tag">{{ status.label }}</el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="提交时间">{{ formatDateTime(qualification?.submittedAt) }}</el-descriptions-item>
      <el-descriptions-item label="审核通过">
        {{ formatDateTime(detail.organization.qualificationReviewedAt) }}
      </el-descriptions-item>
    </el-descriptions>

    <el-form ref="formRef" :model="form" :rules="qualificationRules" label-width="132px" class="qualification__form">
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
        <template v-if="pending">
          <el-button type="success" :disabled="busy" @click="approve">审核通过</el-button>
          <el-button type="danger" plain :disabled="busy" @click="reject.open = true">驳回</el-button>
        </template>
      </el-form-item>
    </el-form>

    <div class="qualification__attachments">
      <div class="qualification__attachments-head">
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
      <el-table :data="detail.attachments" row-key="attachmentId" empty-text="还没有附件" size="small" border>
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

    <el-dialog v-model="reject.open" title="驳回资质" width="480px" :close-on-click-modal="false">
      <el-form ref="rejectRef" :model="reject" :rules="rejectRules" label-width="80px">
        <el-form-item label="驳回原因" prop="reason">
          <el-input v-model="reject.reason" type="textarea" :rows="3" :maxlength="LIMITS.rejectReason" show-word-limit
                    placeholder="写清楚哪里不符合，比如营业执照照片不清、主体名称与执照不一致" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reject.open = false">取消</el-button>
        <el-button type="danger" :loading="busy" @click="submitReject">驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.qualification {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.qualification__form {
  max-width: 720px;
}

.qualification__attachments-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.qualification__attachments-head h3 {
  margin: 0;
  font-size: 15px;
}

.hint {
  flex: 1;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
