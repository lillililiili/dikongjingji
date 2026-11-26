<script setup lang="ts">
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { newIdempotencyKey } from '@/core/http/api-client'
import { confirmAction, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import { maxChars, required } from '@/core/ui/form-rules'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'
import { createOrganizationApi, type DirectoryCity } from '@/features/organization/organization-api'

import {
  AGREEMENT_OPTIONS,
  LIMITS,
  LINK_OPTIONS,
  createSystemNoticeApi,
  draftProblems,
  emptyNoticeForm,
  type NoticeForm,
  type SystemNotice,
} from './system-notice-api'

const api = createSystemNoticeApi(http)
const directory = createOrganizationApi(http)
const list = usePagedList<SystemNotice, Record<string, never>>((_filters, page, size) => api.list(page, size), {})

const cities = ref<DirectoryCity[]>([])
const cityError = ref('')
const cityName = (code: string | null) => cities.value.find((city) => city.code === code)?.name ?? code ?? ''
const agreementName = (code: string | null) => AGREEMENT_OPTIONS.find((option) => option.value === code)?.label ?? ''

const dialogOpen = ref(false)
const busy = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<NoticeForm>(emptyNoticeForm())
// 同一次填写重复点“发布”只登记一份；关掉对话框重新填才换新的幂等键。
let idempotencyKey = newIdempotencyKey()

const rules: FormRules = {
  title: [required('标题'), maxChars('标题', LIMITS.title)],
  summary: [required('列表摘要'), maxChars('列表摘要', LIMITS.summary)],
  paragraphsText: [required('正文')],
  footnote: [maxChars('文末说明', LIMITS.footnote)],
  actionLabel: [maxChars('主按钮文字', LIMITS.actionLabel)],
}

function openPublish(): void {
  Object.assign(form, emptyNoticeForm())
  idempotencyKey = newIdempotencyKey()
  dialogOpen.value = true
  formRef.value?.clearValidate()
}

function addFact(): void {
  if (form.facts.length < LIMITS.facts) form.facts.push({ label: '', value: '' })
}

function addItem(): void {
  if (form.items.length < LIMITS.items) form.items.push({ title: '', description: '', linkType: '', agreement: '' })
}

async function publish(): Promise<void> {
  if (!formRef.value || busy.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const problems = draftProblems(form)
  if (problems.length) {
    ElMessage({ type: 'warning', message: problems.join('；') })
    return
  }
  const audience = form.scope === 'ALL' ? '全体用户' : `${cityName(form.cityCode)}的用户`
  const confirmed = await confirmAction({
    title: '确认发布系统通知',
    message: `发给${audience}：发布后马上推送到他们登记过的设备，打开 App“消息”时收到。发布后不能修改，只能撤回。`,
    confirmText: '发布',
  })
  if (!confirmed) return
  busy.value = true
  try {
    await api.publish(form, idempotencyKey)
    dialogOpen.value = false
    ElMessage({ type: 'success', message: '已发布' })
    void list.load()
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

async function withdraw(notice: SystemNotice): Promise<void> {
  const confirmed = await confirmAction({
    title: '确认撤回',
    message: `撤回“${notice.title}”后，它从所有人的“消息”里拿掉，详情也打不开；已经推送到手机的提醒收不回。`,
    confirmText: '撤回',
    danger: true,
  })
  if (!confirmed) return
  busy.value = true
  try {
    await api.withdraw(notice.id)
    ElMessage({ type: 'success', message: '已撤回' })
    void list.load()
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

onMounted(() => {
  void list.load()
  directory.cities().then((loaded) => { cities.value = loaded }).catch((failure) => { cityError.value = errorMessage(failure) })
})
</script>

<template>
  <PageSection
    title="系统通知"
    description="平台发给全体或某个城市用户的公告（协议更新、服务调整等）。用户打开 App“消息”时收到，发布时推送到登记过的设备；协议更新会在协议页显示“有更新”。"
  >
    <div class="toolbar">
      <el-button type="primary" data-testid="notice-publish-open" @click="openPublish">发布系统通知</el-button>
    </div>

    <PagedTable
      :items="list.items.value"
      :total="list.total.value"
      :page="list.page.value"
      :size="list.size.value"
      :loading="list.loading.value"
      :error="list.error.value"
      row-key="id"
      empty-text="还没有系统通知"
      @change-page="list.changePage"
      @change-size="list.changeSize"
      @retry="list.load()"
    >
      <el-table-column label="发布时间" width="150">
        <template #default="{ row }">{{ formatDateTime((row as SystemNotice).publishedAt) }}</template>
      </el-table-column>
      <el-table-column label="标题" min-width="200">
        <template #default="{ row }">
          <div>{{ (row as SystemNotice).title }}</div>
          <div class="muted">{{ (row as SystemNotice).summary }}</div>
        </template>
      </el-table-column>
      <el-table-column label="发给" width="120">
        <template #default="{ row }">
          {{ (row as SystemNotice).audience.scope === 'ALL' ? '全体' : cityName((row as SystemNotice).audience.cityCode) }}
        </template>
      </el-table-column>
      <el-table-column label="关于协议" width="150">
        <template #default="{ row }">{{ agreementName((row as SystemNotice).aboutAgreement) || '—' }}</template>
      </el-table-column>
      <el-table-column label="有效期至" width="150">
        <template #default="{ row }">{{ (row as SystemNotice).expiresAt ? formatDateTime((row as SystemNotice).expiresAt) : '一直有效' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="(row as SystemNotice).status === 'PUBLISHED' ? 'success' : 'info'">
            {{ (row as SystemNotice).status === 'PUBLISHED' ? '发布中' : '已撤回' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button v-if="(row as SystemNotice).status === 'PUBLISHED'" link type="danger" :disabled="busy"
                     @click="withdraw(row as SystemNotice)">撤回</el-button>
        </template>
      </el-table-column>
    </PagedTable>
  </PageSection>

  <el-dialog v-model="dialogOpen" title="发布系统通知" width="760px" :close-on-click-modal="false">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
      <el-form-item label="标题" prop="title">
        <el-input v-model="form.title" :maxlength="LIMITS.title" show-word-limit placeholder="比如：隐私政策更新摘要" />
      </el-form-item>
      <el-form-item label="列表摘要" prop="summary">
        <el-input v-model="form.summary" :maxlength="LIMITS.summary" show-word-limit
                  placeholder="“消息”列表里那一行，比如：查看主要变化与生效日期" />
      </el-form-item>
      <el-form-item label="正文" prop="paragraphsText">
        <el-input v-model="form.paragraphsText" type="textarea" :rows="4"
                  :placeholder="`段落之间空一行；最多 ${LIMITS.paragraphs} 段，每段 ${LIMITS.paragraph} 字以内`" />
      </el-form-item>
      <el-form-item label="要点">
        <div class="rows">
          <div v-for="(fact, index) in form.facts" :key="`f-${index}`" class="row">
            <el-input v-model="fact.label" :maxlength="LIMITS.factLabel" placeholder="名称，如 版本" style="width: 160px" />
            <el-input v-model="fact.value" :maxlength="LIMITS.factValue" placeholder="值，如 v1.1" style="width: 200px" />
            <el-button link type="danger" @click="form.facts.splice(index, 1)">删除</el-button>
          </div>
          <el-button v-if="form.facts.length < LIMITS.facts" link type="primary" @click="addFact">加一项（最多 {{ LIMITS.facts }} 项）</el-button>
        </div>
      </el-form-item>
      <el-form-item label="主要变化">
        <div class="rows">
          <div v-for="(item, index) in form.items" :key="`i-${index}`" class="row row--wrap">
            <el-input v-model="item.title" :maxlength="LIMITS.itemTitle" placeholder="标题" style="width: 150px" />
            <el-input v-model="item.description" :maxlength="LIMITS.itemDescription" placeholder="说明" style="width: 240px" />
            <el-select v-model="item.linkType" clearable placeholder="不带链接" style="width: 180px">
              <el-option v-for="option in LINK_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
            <el-select v-if="item.linkType === 'AGREEMENT'" v-model="item.agreement" placeholder="哪份协议" style="width: 170px">
              <el-option v-for="option in AGREEMENT_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
            <el-button link type="danger" @click="form.items.splice(index, 1)">删除</el-button>
          </div>
          <el-button v-if="form.items.length < LIMITS.items" link type="primary" @click="addItem">加一条（最多 {{ LIMITS.items }} 条）</el-button>
        </div>
      </el-form-item>
      <el-form-item label="文末说明" prop="footnote">
        <el-input v-model="form.footnote" type="textarea" :rows="2" :maxlength="LIMITS.footnote" show-word-limit />
      </el-form-item>
      <el-form-item label="主按钮" prop="actionLabel">
        <div class="row row--wrap">
          <el-input v-model="form.actionLabel" :maxlength="LIMITS.actionLabel" placeholder="文字，如 查看完整隐私政策" style="width: 200px" />
          <el-select v-model="form.actionLinkType" clearable placeholder="不放主按钮" style="width: 180px">
            <el-option v-for="option in LINK_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-select v-if="form.actionLinkType === 'AGREEMENT'" v-model="form.actionAgreement" placeholder="哪份协议" style="width: 170px">
            <el-option v-for="option in AGREEMENT_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
        </div>
      </el-form-item>
      <el-form-item label="关于哪份协议">
        <el-select v-model="form.aboutAgreement" clearable placeholder="不关协议" style="width: 220px">
          <el-option v-for="option in AGREEMENT_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <span class="hint">选了以后，发给全体时协议页显示“有更新 · 查看本次更新摘要”</span>
      </el-form-item>
      <el-form-item label="发给">
        <el-radio-group v-model="form.scope">
          <el-radio-button value="ALL">全体用户</el-radio-button>
          <el-radio-button value="CITY">某个城市</el-radio-button>
        </el-radio-group>
        <el-select v-if="form.scope === 'CITY'" v-model="form.cityCode" filterable placeholder="选城市" style="width: 200px; margin-left: 12px">
          <el-option v-for="city in cities" :key="city.code" :label="`${city.provinceName} ${city.name}`" :value="city.code" />
        </el-select>
        <span v-if="cityError" role="alert">城市目录加载失败：{{ cityError }}</span>
      </el-form-item>
      <el-form-item label="有效期至">
        <el-date-picker v-model="form.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ" placeholder="不填一直有效" />
        <span class="hint">过期后还没收到的人不再收到，已经收到的不受影响</span>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogOpen = false">取消</el-button>
      <el-button type="primary" :loading="busy" data-testid="notice-publish-submit" @click="publish">发布</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.toolbar {
  display: flex;
  margin-bottom: 16px;
}

.muted,
.hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.hint {
  margin-left: 12px;
}

.rows {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
}

.row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.row--wrap {
  flex-wrap: wrap;
}
</style>
