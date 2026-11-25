<script setup lang="ts">
/**
 * 专题的新建和编辑：标题、副标题、发布方、城市、起止日期、封面、示例标记；挂的活动、场馆、图文（填稳定 ID，一行一个，
 * App 按 ID 到各自模块读）和商家内容（商家的商品，App 标“广告”，照画板 Topic）；上线、下线；改动记录。
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
import CityPicker from './components/CityPicker.vue'
import OrganizationPicker from './components/OrganizationPicker.vue'
import { createContentOpsApi, type OpsTopic } from './content-api'
import {
  LIMITS,
  MISSING_LABELS,
  SUBJECT_LABELS,
  idsFromText,
  newKey,
  topicForm,
  topicProblems,
  topicRequest,
  type TopicForm,
} from './content-rules'
import { useContentEditor } from './use-content-editor'

const route = useRoute()
const router = useRouter()
const api = createContentOpsApi(http)
const editor = useContentEditor<OpsTopic>(api.topic, api.topicChanges)
const form = reactive<TopicForm>(topicForm())
const dates = ref<[string, string] | null>(null)

const topicId = computed(() => (route.params.topicId ? String(route.params.topicId) : null))
const topic = computed(() => editor.entity.value)
const problems = computed(() => topicProblems(form, editor.entity.value))
const counts = computed(() => ({
  activities: idsFromText(form.activityIdsText).ids.length,
  venues: idsFromText(form.venueIdsText).ids.length,
  articles: idsFromText(form.articleIdsText).ids.length,
}))

watch(topic, (current) => {
  if (!current) return
  Object.assign(form, topicForm(current))
  dates.value = current.startsOn && current.endsOn ? [current.startsOn, current.endsOn] : null
})

watch(dates, (range) => {
  form.startsOn = range?.[0] ?? ''
  form.endsOn = range?.[1] ?? ''
})

function addMerchantItem(): void {
  form.merchantItems.push({ key: newKey(), productId: '', merchantOrganizationId: '', merchantName: '' })
}

async function save(): Promise<void> {
  if (!form.title.trim()) {
    showError(new Error('请填写标题'))
    return
  }
  if (problems.value.length) {
    showError(new Error(problems.value[0]))
    return
  }
  const current = topic.value
  const live = current?.status === 'PUBLISHED'
  const confirmed = await confirmAction({
    title: current ? '确认保存修改' : '确认新建草稿',
    message: current
      ? (live ? '这个专题已上线，保存后 App 专题页马上显示新内容。' : '保存修改，草稿在 App 上看不到。')
      : '新建一个专题草稿，上线前 App 上看不到。',
    confirmText: '保存',
    danger: live,
  })
  if (!confirmed) return
  const saved = await editor.run(
    () => (current
      ? api.updateTopic(current.topicId, topicRequest(form, current.version))
      : api.createTopic(topicRequest(form, null))),
    current ? '保存修改' : '新建草稿',
    (entity) => entity.topicId,
  )
  if (saved && !current) await router.replace({ name: 'content-topic', params: { topicId: saved.topicId } })
}

async function publish(): Promise<void> {
  const current = topic.value
  if (!current) return
  const confirmed = await confirmAction({
    title: '确认上线',
    message: `上线后 ${current.startsOn} 至 ${current.endsOn} 在本城发现页推荐频道显示“${current.title}”，过期的分享链接仍能打开。`,
    confirmText: '上线',
  })
  if (!confirmed) return
  await editor.run(() => api.publishTopic(current.topicId, current.version), '上线', (entity) => entity.topicId)
}

async function offline(): Promise<void> {
  const current = topic.value
  if (!current) return
  const confirmed = await confirmAction({
    title: '确认下线',
    message: `下线后 App 推荐频道不再显示“${current.title}”，打开专题链接会提示专题已下线。`,
    confirmText: '下线',
    danger: true,
  })
  if (!confirmed) return
  await editor.run(() => api.offlineTopic(current.topicId, current.version), '下线', (entity) => entity.topicId)
}

onMounted(() => {
  if (topicId.value) void editor.load(topicId.value)
})
</script>

<template>
  <PageSection
    :title="topic ? topic.title : '新建专题'"
    :description="topic ? `${topic.publisher.name} · ${topic.startsOn}–${topic.endsOn}` : '一个城市一段时间的合集，挂活动、场馆、图文和商家内容。'"
    traced
  >
    <template #actions>
      <el-button @click="router.push({ name: 'content', query: { tab: 'topics' } })">返回列表</el-button>
      <template v-if="topic">
        <el-button v-if="topic.status !== 'PUBLISHED'" type="success" :disabled="editor.busy.value" @click="publish">上线</el-button>
        <el-button v-else type="danger" plain :disabled="editor.busy.value" @click="offline">下线</el-button>
      </template>
      <el-button type="primary" :loading="editor.busy.value" @click="save">保存</el-button>
    </template>

    <el-alert v-if="editor.error.value" type="error" :title="editor.error.value" :closable="false" show-icon />
    <div v-loading="editor.loading.value" class="topic-edit">
      <div v-if="topic" class="topic-edit__status">
        <el-tag :type="publishStatusTag(topic.status)">{{ publishStatusLabel(topic.status) }}</el-tag>
        <span>版本 {{ topic.version }} · 最后修改 {{ formatDateTime(topic.updatedAt) }}</span>
        <span v-if="topic.publishMissing.length" class="topic-edit__missing">
          上线还差：{{ topic.publishMissing.map((key) => MISSING_LABELS[key] ?? key).join('、') }}
        </span>
      </div>

      <el-form label-width="96px" @submit.prevent>
        <el-form-item label="标题" required>
          <el-input v-model="form.title" :maxlength="LIMITS.topicTitle" show-word-limit placeholder="比如“黄河口低空体验季”" />
        </el-form-item>
        <el-form-item label="副标题">
          <el-input v-model="form.subtitle" :maxlength="LIMITS.subtitle" show-word-limit placeholder="比如“到馆体验、赛事、研学合集”" />
        </el-form-item>
        <el-form-item label="发布方" required>
          <AuthorField v-model="form.publisher" />
        </el-form-item>
        <el-form-item label="城市" required>
          <CityPicker v-model="form.cityCode" />
        </el-form-item>
        <el-form-item label="起止日期" required>
          <el-date-picker
            v-model="dates"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始（北京时间）"
            end-placeholder="结束（含当天）"
          />
        </el-form-item>
        <el-form-item label="封面">
          <MediaField v-model="form.coverUrl" kind="IMAGE" />
        </el-form-item>
        <el-form-item label="示例数据">
          <el-switch v-model="form.sample" />
        </el-form-item>
        <el-form-item :label="`本季活动 ${counts.activities}/${LIMITS.topicActivities}`">
          <el-input v-model="form.activityIdsText" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }"
                    placeholder="活动 ID，一行一个，按页面上的顺序" />
        </el-form-item>
        <el-form-item :label="`到馆体验 ${counts.venues}/${LIMITS.topicVenues}`">
          <el-input v-model="form.venueIdsText" type="textarea" :autosize="{ minRows: 1, maxRows: 4 }"
                    placeholder="场馆 ID，一行一个" />
        </el-form-item>
        <el-form-item :label="`相关内容 ${counts.articles}/${LIMITS.topicArticles}`">
          <el-input v-model="form.articleIdsText" type="textarea" :autosize="{ minRows: 1, maxRows: 6 }"
                    placeholder="图文 ID（本平台的案例或知识），一行一个" />
        </el-form-item>
        <el-form-item label="商家内容">
          <div class="topic-edit__merchants">
            <div v-for="(item, index) in form.merchantItems" :key="item.key" class="topic-edit__merchant">
              <el-input v-model="item.productId" placeholder="商品 ID（专题城市里有在售商家）" style="width: 320px" />
              <OrganizationPicker
                v-model="item.merchantOrganizationId"
                :label="item.merchantName"
                @picked="(picked) => (item.merchantName = picked?.name ?? '')"
              />
              <el-button size="small" link type="danger" @click="form.merchantItems.splice(index, 1)">删除</el-button>
            </div>
            <div class="topic-edit__merchant">
              <el-button size="small" :disabled="form.merchantItems.length >= LIMITS.topicMerchantItems" @click="addMerchantItem">
                加一条
              </el-button>
              <span class="topic-edit__hint">App 上标“广告”，显示“商家名发布”；商品名、价格和在售商家按 ID 到商城读。</span>
            </div>
          </div>
        </el-form-item>
      </el-form>
      <el-alert v-if="problems.length" type="warning" :title="problems.join('；')" :closable="false" show-icon />

      <template v-if="topic">
        <h3 class="topic-edit__section">改动记录</h3>
        <ChangeLogTable :changes="editor.changes.value" :error="editor.changesError.value" :loading="editor.changesLoading.value" :subject-labels="SUBJECT_LABELS" />
      </template>
    </div>
  </PageSection>
</template>

<style scoped>
.topic-edit {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.topic-edit__status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.topic-edit__missing {
  color: var(--el-color-warning-dark-2);
}

.topic-edit__merchants {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.topic-edit__merchant {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.topic-edit__hint {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.topic-edit__section {
  margin: 8px 0 0;
  font-size: 15px;
}
</style>
