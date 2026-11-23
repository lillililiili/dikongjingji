<script setup lang="ts">
import { ElMessage, type FormInstance } from 'element-plus'
import { onMounted, reactive, ref, shallowRef } from 'vue'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { confirmAction, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import {
  createMerchantProfileApi,
  formFrom,
  merchantProfileRules,
  PROFILE_LIMITS,
  SUBMISSION_STATUS_LABELS,
  SUBMISSION_STATUS_TAGS,
  type MallMerchantProfileState,
  type MerchantProfileApi,
  type MerchantProfileForm,
} from './merchant-profile-api'
import ProfileDescriptions from './ProfileDescriptions.vue'

const props = defineProps<{
  api?: MerchantProfileApi
}>()

const api = props.api ?? createMerchantProfileApi(http)
const state = shallowRef<MallMerchantProfileState | null>(null)
const loading = ref(false)
const error = ref('')
const submitting = ref(false)
const formRef = ref<FormInstance>()
const form = reactive<MerchantProfileForm>({
  businessEntity: '', intro: '', saleScopeNote: '', authorizationUntil: '', categoryNote: '', invoiceIssuer: '',
  aftersaleParty: '',
})

function apply(next: MallMerchantProfileState): void {
  state.value = next
  Object.assign(form, formFrom(next))
  formRef.value?.clearValidate()
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    apply(await api.read())
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function submit(): Promise<void> {
  if (!formRef.value || submitting.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  const pending = state.value?.latest?.status === 'SUBMITTED'
  const confirmed = await confirmAction({
    title: '提交商家资料',
    message: `提交后由平台审核，通过后显示在 App 的商家主页上。${pending ? '现在还有一版在待审核，这次提交会替换它。' : ''}`
      + '审核通过前，App 上仍显示现在对外的那一版。',
    confirmText: '提交审核',
  })
  if (!confirmed) return
  submitting.value = true
  try {
    apply(await api.submit(form))
    ElMessage.success('已提交，等平台审核')
  } catch (failure) {
    showError(failure)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection
    title="商家资料"
    description="提交后由平台审核，通过后显示在 App 的商家主页上；待审核时再提交会替换上一版。默认开票方、默认售后责任方给没单独设置的商品兜底。"
  >
    <template #actions>
      <el-button @click="load()">刷新</el-button>
    </template>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button size="small" @click="load()">重新加载</el-button>
    </el-alert>
    <div v-else-if="loading && !state" v-loading="true" class="placeholder" />
    <template v-else-if="state">
      <div class="columns">
        <div class="column">
          <h2 class="block-title">现在对外的资料</h2>
          <template v-if="state.published">
            <p class="hint">审核通过于 {{ formatDateTime(state.published.approvedAt) }}，App 商家主页显示的就是这一版。</p>
            <ProfileDescriptions :value="state.published" />
          </template>
          <el-empty v-else description="还没有审核通过的资料" :image-size="80" />
        </div>
        <div class="column">
          <h2 class="block-title">最近一次提交</h2>
          <template v-if="state.latest">
            <el-descriptions :column="2" border size="small" class="latest">
              <el-descriptions-item label="状态">
                <el-tag :type="SUBMISSION_STATUS_TAGS[state.latest.status]">
                  {{ SUBMISSION_STATUS_LABELS[state.latest.status] }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="提交人">{{ state.latest.submittedByName }}</el-descriptions-item>
              <el-descriptions-item label="提交时间">{{ formatDateTime(state.latest.submittedAt) }}</el-descriptions-item>
              <el-descriptions-item label="审核时间">{{ formatDateTime(state.latest.reviewedAt) }}</el-descriptions-item>
              <el-descriptions-item v-if="state.latest.rejectReason" label="驳回原因" :span="2">
                <span class="reject">{{ state.latest.rejectReason }}</span>
              </el-descriptions-item>
            </el-descriptions>
            <ProfileDescriptions :value="state.latest.draft" />
          </template>
          <el-empty v-else description="还没有提交过" :image-size="80" />
        </div>
      </div>

      <h2 class="block-title">提交新的一版</h2>
      <p class="hint">
        下面已带出{{ state.latest ? '最近一次提交' : state.published ? '现在对外' : '' }}的内容，改好后提交。
        提交后由平台审核，通过后显示在 App 的商家主页上；待审核时再提交会替换上一版。
      </p>
      <el-form ref="formRef" :model="form" :rules="merchantProfileRules" label-width="120px" class="form">
        <el-form-item label="经营主体" prop="businessEntity" required>
          <el-input v-model="form.businessEntity" class="profile-entity" :maxlength="PROFILE_LIMITS.businessEntity"
                    placeholder="营业执照上的主体名称" />
        </el-form-item>
        <el-form-item label="简介" prop="intro">
          <el-input v-model="form.intro" type="textarea" :rows="3" :maxlength="PROFILE_LIMITS.intro" show-word-limit
                    placeholder="可空，商家主页上的一段介绍" />
        </el-form-item>
        <el-form-item label="可售说明" prop="saleScopeNote">
          <el-input v-model="form.saleScopeNote" :maxlength="PROFILE_LIMITS.saleScopeNote" show-word-limit
                    placeholder="可空，接在可售城市后面，比如：全国发货" />
        </el-form-item>
        <el-form-item label="授权有效期" prop="authorizationUntil">
          <el-date-picker v-model="form.authorizationUntil" type="date" value-format="YYYY-MM-DD" placeholder="可空"
                          clearable />
        </el-form-item>
        <el-form-item label="品类" prop="categoryNote">
          <el-input v-model="form.categoryNote" :maxlength="PROFILE_LIMITS.categoryNote" show-word-limit
                    placeholder="可空，比如：教育机器人" />
        </el-form-item>
        <el-form-item label="默认开票方" prop="invoiceIssuer">
          <el-input v-model="form.invoiceIssuer" :maxlength="PROFILE_LIMITS.invoiceIssuer" show-word-limit
                    placeholder="可空，不填就是本机构" />
        </el-form-item>
        <el-form-item label="默认售后责任方" prop="aftersaleParty">
          <el-input v-model="form.aftersaleParty" :maxlength="PROFILE_LIMITS.aftersaleParty" show-word-limit
                    placeholder="可空，不填就是本机构" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="submit">提交审核</el-button>
        </el-form-item>
      </el-form>
    </template>
  </PageSection>
</template>

<style scoped>
.placeholder {
  min-height: 200px;
}

.columns {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(360px, 1fr));
  gap: 20px;
}

.block-title {
  margin: 20px 0 10px;
  font-size: 15px;
}

.column .block-title {
  margin-top: 0;
}

.latest {
  margin-bottom: 10px;
}

.reject {
  color: var(--el-color-danger);
}

.hint {
  margin: 0 0 10px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.form {
  max-width: 720px;
}
</style>
