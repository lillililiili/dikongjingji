<script setup lang="ts">
/**
 * 图文（案例、知识）的新建和编辑：标题、作者、城市、封面、示例标记、正文块（含正文配图）、内容中提到；
 * 上线（立即或定时）、下线；改动记录。新建保存后跳到详情地址继续编辑。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import ChangeLogTable from '@/core/ops/ChangeLogTable.vue'
import MediaField from '@/core/ops/MediaField.vue'
import { publishStatusLabel, publishStatusTag } from '@/core/ops/ops-common'
import { confirmAction, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import AuthorField from './components/AuthorField.vue'
import BlockEditor from './components/BlockEditor.vue'
import CityPicker from './components/CityPicker.vue'
import PublishDialog from './components/PublishDialog.vue'
import RefsEditor from './components/RefsEditor.vue'
import { createContentOpsApi, type OpsArticle } from './content-api'
import {
  ARTICLE_KIND_LABELS,
  LIMITS,
  MISSING_LABELS,
  SUBJECT_LABELS,
  articleForm,
  articleProblems,
  articleRequest,
  type ArticleForm,
} from './content-rules'
import { useContentEditor } from './use-content-editor'

const route = useRoute()
const router = useRouter()
const api = createContentOpsApi(http)
const form = reactive<ArticleForm>(articleForm())
const editor = useContentEditor<OpsArticle>(api.article, api.articleChanges, form)
const publishing = ref(false)

const articleId = computed(() => (route.params.articleId ? String(route.params.articleId) : null))
const article = computed(() => editor.entity.value)
const problems = computed(() => articleProblems(form, editor.entity.value))

watch(article, (current) => {
  if (!current) return
  Object.assign(form, articleForm(current))
  editor.markSaved()
})

async function save(): Promise<void> {
  if (editor.busy.value || (articleId.value && !article.value)) return
  if (problems.value.length) {
    showError(new Error(problems.value[0]))
    return
  }
  if (!form.title.trim()) {
    showError(new Error('请填写标题'))
    return
  }
  const current = article.value
  const live = current?.status === 'PUBLISHED'
  const confirmed = await confirmAction({
    title: current ? '确认保存修改' : '确认新建草稿',
    message: current
      ? (live ? '这篇图文已上线，保存后 App 上马上显示新内容；封面、正文等必填项要保持齐全。' : '保存修改，草稿在 App 上看不到。')
      : '新建一篇草稿，上线前 App 上看不到。',
    confirmText: '保存',
    danger: live,
  })
  if (!confirmed) return
  const saved = await editor.run(
    () => (current
      ? api.updateArticle(current.articleId, articleRequest(form, current.version))
      : api.createArticle(articleRequest(form, null))),
    current ? '保存修改' : '新建草稿',
    (entity) => entity.articleId,
  )
  if (saved && !current) await router.replace({ name: 'content-article', params: { articleId: saved.articleId } })
}

async function publish(publishAt: string | null): Promise<void> {
  const current = article.value
  if (!current || editor.dirty.value) return
  const done = await editor.run(() => api.publishArticle(current.articleId, current.version, publishAt), '上线',
    (entity) => entity.articleId)
  if (done) publishing.value = false
}

async function offline(): Promise<void> {
  const current = article.value
  if (!current || editor.dirty.value) return
  const confirmed = await confirmAction({
    title: '确认下线',
    message: `下线后 App 发现页、相关内容和专题里都不再显示《${current.title}》，发布时间保留，可以再上线。`,
    confirmText: '下线',
    danger: true,
  })
  if (!confirmed) return
  await editor.run(() => api.offlineArticle(current.articleId, current.version), '下线', (entity) => entity.articleId)
}

onMounted(() => {
  if (articleId.value) void editor.load(articleId.value)
})
</script>

<template>
  <PageSection
    :title="article?.title ?? (articleId ? '图文详情' : '新建图文')"
    :description="article ? `${ARTICLE_KIND_LABELS[article.kind]} · ${article.author.name}${article.sponsored ? ' · 广告' : ''}` : '案例由服务商、商家发布；知识可以由平台发布、不分城市。'"
    traced
  >
    <template #actions>
      <el-button @click="router.push({ name: 'content', query: { tab: 'articles' } })">返回列表</el-button>
      <template v-if="article">
        <el-button v-if="article.status !== 'PUBLISHED'" type="success" :disabled="editor.busy.value || editor.dirty.value" @click="publishing = true">上线</el-button>
        <el-button v-else type="danger" plain :disabled="editor.busy.value || editor.dirty.value" @click="offline">下线</el-button>
      </template>
      <el-button v-if="!articleId || article" type="primary" :loading="editor.busy.value" data-testid="article-save" @click="save">保存</el-button>
    </template>

    <el-alert v-if="editor.error.value" type="error" :title="editor.error.value" :closable="false" show-icon>
      <el-button v-if="articleId" size="small" @click="editor.load(articleId)">重新加载</el-button>
    </el-alert>
    <div v-else v-loading="editor.loading.value || editor.busy.value" class="article-edit">
      <el-alert v-if="editor.dirty.value" title="内容有未保存的修改，请先保存再调整上线状态。" type="info" :closable="false" />
      <div v-if="article" class="article-edit__status">
        <el-tag :type="publishStatusTag(article.status)">{{ publishStatusLabel(article.status) }}</el-tag>
        <span>发布时间 {{ formatDateTime(article.publishedAt) }}</span>
        <span>版本 {{ article.version }} · 最后修改 {{ formatDateTime(article.updatedAt) }}</span>
        <span v-if="article.publishMissing.length" class="article-edit__missing">
          上线还差：{{ article.publishMissing.map((key) => MISSING_LABELS[key] ?? key).join('、') }}
        </span>
      </div>

      <el-form :disabled="editor.loading.value || editor.busy.value" label-width="96px" @submit.prevent>
        <el-form-item label="类型" required>
          <el-radio-group v-model="form.kind">
            <el-radio-button value="CASE">案例</el-radio-button>
            <el-radio-button value="KNOWLEDGE">知识</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="form.title" :maxlength="LIMITS.title" show-word-limit placeholder="比如“一台机器狗如何完成园区夜间巡逻”" />
        </el-form-item>
        <el-form-item label="作者" required>
          <AuthorField v-model="form.author" :allow-platform="form.kind !== 'CASE'" />
        </el-form-item>
        <el-form-item label="城市" :required="form.kind === 'CASE'">
          <CityPicker v-model="form.cityCode" :placeholder="form.kind === 'CASE' ? '案例要选城市' : '不选为不分城市'" />
        </el-form-item>
        <el-form-item label="封面">
          <MediaField v-model="form.coverUrl" kind="IMAGE" />
        </el-form-item>
        <el-form-item label="示例数据">
          <el-switch v-model="form.sample" />
          <span class="article-edit__hint">示例内容在 App 上标“示例”。</span>
        </el-form-item>
        <el-form-item label="正文" required>
          <BlockEditor :blocks="form.blocks" />
        </el-form-item>
        <el-form-item label="内容中提到">
          <RefsEditor :refs="form.refs" />
        </el-form-item>
      </el-form>

      <template v-if="article">
        <h3 class="article-edit__section">改动记录</h3>
        <ChangeLogTable :changes="editor.changes.value" :error="editor.changesError.value" :loading="editor.changesLoading.value" :subject-labels="SUBJECT_LABELS" />
      </template>
    </div>
  </PageSection>

  <PublishDialog
    :open="publishing"
    :title="article?.title ?? ''"
    :busy="editor.busy.value"
    @close="publishing = false"
    @confirm="publish"
  />
</template>

<style scoped>
.article-edit {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.article-edit__status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.article-edit__missing {
  color: var(--el-color-warning-dark-2);
}

.article-edit__hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.article-edit__section {
  margin: 8px 0 0;
  font-size: 15px;
}
</style>
