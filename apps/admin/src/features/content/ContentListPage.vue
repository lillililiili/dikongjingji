<script setup lang="ts">
/** 发现内容：图文 / 视频 / 专题三个标签页，各自筛选、分页，最近修改的在前；点标题进编辑页。 */
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { PUBLISH_STATUS_OPTIONS, publishStatusLabel, publishStatusTag } from '@/core/ops/ops-common'
import { formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import CityPicker from './components/CityPicker.vue'
import {
  createContentOpsApi,
  type ContentFilters,
  type OpsContentSummary,
  type OpsTopicSummary,
  type TopicFilters,
} from './content-api'
import { CONTENT_KIND_LABELS } from './content-rules'

type Tab = 'articles' | 'videos' | 'topics'

const route = useRoute()
const router = useRouter()
const api = createContentOpsApi(http)
const initialTab = (['articles', 'videos', 'topics'] as const).find((tab) => tab === route.query.tab) ?? 'articles'
const tab = ref<Tab>(initialTab)

const articles = usePagedList<OpsContentSummary, ContentFilters>((filters, page, size) => api.articles(filters, page, size),
  { keyword: '', kind: '', cityCode: '', status: '' })
const videos = usePagedList<OpsContentSummary, ContentFilters>((filters, page, size) => api.videos(filters, page, size),
  { keyword: '', kind: '', cityCode: '', status: '' })
const topics = usePagedList<OpsTopicSummary, TopicFilters>((filters, page, size) => api.topics(filters, page, size),
  { keyword: '', cityCode: '', status: '' })
const loaded = new Set<Tab>()

function show(next: Tab): void {
  void router.replace({ query: { ...route.query, tab: next } })
  if (loaded.has(next)) return
  loaded.add(next)
  if (next === 'articles') void articles.load()
  else if (next === 'videos') void videos.load()
  else void topics.load()
}

watch(tab, show)

function create(): void {
  const name = tab.value === 'articles' ? 'content-article-new' : tab.value === 'videos' ? 'content-video-new' : 'content-topic-new'
  void router.push({ name })
}

function openContent(row: OpsContentSummary): void {
  void router.push(row.kind === 'VIDEO'
    ? { name: 'content-video', params: { videoId: row.contentId } }
    : { name: 'content-article', params: { articleId: row.contentId } })
}

onMounted(() => show(tab.value))
</script>

<template>
  <PageSection
    title="发现内容"
    description="App 发现页的图文、视频和专题。新建都是草稿；上线前会检查封面等必填项，商家发布的一律标“广告”。"
    traced
  >
    <template #actions>
      <el-button type="primary" data-testid="content-create" @click="create">
        新建{{ tab === 'articles' ? '图文' : tab === 'videos' ? '视频' : '专题' }}
      </el-button>
    </template>

    <el-tabs v-model="tab">
      <el-tab-pane label="图文" name="articles">
        <el-form class="filters" inline @submit.prevent="articles.search()">
          <el-form-item label="关键词">
            <el-input v-model="articles.filters.keyword" clearable maxlength="20" placeholder="标题或作者" @clear="articles.search()" />
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="articles.filters.kind" clearable placeholder="全部" style="width: 110px" @change="articles.search()">
              <el-option label="案例" value="CASE" />
              <el-option label="知识" value="KNOWLEDGE" />
            </el-select>
          </el-form-item>
          <el-form-item label="城市">
            <CityPicker v-model="articles.filters.cityCode" placeholder="全部城市" @update:model-value="articles.search()" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="articles.filters.status" clearable placeholder="全部" style="width: 110px" @change="articles.search()">
              <el-option v-for="option in PUBLISH_STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" native-type="submit">查询</el-button>
            <el-button @click="articles.reset()">重置</el-button>
          </el-form-item>
        </el-form>
        <PagedTable
          :items="articles.items.value"
          :total="articles.total.value"
          :page="articles.page.value"
          :size="articles.size.value"
          :loading="articles.loading.value"
          :error="articles.error.value"
          row-key="contentId"
          empty-text="没有符合条件的图文"
          @change-page="articles.changePage"
          @change-size="articles.changeSize"
          @retry="articles.load()"
        >
          <el-table-column label="标题" min-width="240">
            <template #default="{ row }">
              <el-button link type="primary" @click="openContent(row as OpsContentSummary)">{{ (row as OpsContentSummary).title }}</el-button>
              <el-tag v-if="(row as OpsContentSummary).sponsored" size="small" type="info">广告</el-tag>
              <el-tag v-if="(row as OpsContentSummary).sample" size="small" type="info" effect="plain">示例</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="80">
            <template #default="{ row }">{{ CONTENT_KIND_LABELS[(row as OpsContentSummary).kind] }}</template>
          </el-table-column>
          <el-table-column prop="authorName" label="作者" min-width="140" />
          <el-table-column label="城市" width="90">
            <template #default="{ row }">{{ (row as OpsContentSummary).cityCode ?? '不分城市' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="publishStatusTag((row as OpsContentSummary).status)">{{ publishStatusLabel((row as OpsContentSummary).status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发布时间" width="150">
            <template #default="{ row }">{{ formatDateTime((row as OpsContentSummary).publishedAt) }}</template>
          </el-table-column>
          <el-table-column label="最后修改" width="150">
            <template #default="{ row }">{{ formatDateTime((row as OpsContentSummary).updatedAt) }}</template>
          </el-table-column>
        </PagedTable>
      </el-tab-pane>

      <el-tab-pane label="视频" name="videos">
        <el-form class="filters" inline @submit.prevent="videos.search()">
          <el-form-item label="关键词">
            <el-input v-model="videos.filters.keyword" clearable maxlength="20" placeholder="标题或作者" @clear="videos.search()" />
          </el-form-item>
          <el-form-item label="城市">
            <CityPicker v-model="videos.filters.cityCode" placeholder="全部城市" @update:model-value="videos.search()" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="videos.filters.status" clearable placeholder="全部" style="width: 110px" @change="videos.search()">
              <el-option v-for="option in PUBLISH_STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" native-type="submit">查询</el-button>
            <el-button @click="videos.reset()">重置</el-button>
          </el-form-item>
        </el-form>
        <PagedTable
          :items="videos.items.value"
          :total="videos.total.value"
          :page="videos.page.value"
          :size="videos.size.value"
          :loading="videos.loading.value"
          :error="videos.error.value"
          row-key="contentId"
          empty-text="没有符合条件的视频"
          @change-page="videos.changePage"
          @change-size="videos.changeSize"
          @retry="videos.load()"
        >
          <el-table-column label="标题" min-width="240">
            <template #default="{ row }">
              <el-button link type="primary" @click="openContent(row as OpsContentSummary)">{{ (row as OpsContentSummary).title }}</el-button>
              <el-tag v-if="(row as OpsContentSummary).sponsored" size="small" type="info">广告</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="authorName" label="作者" min-width="140" />
          <el-table-column prop="cityCode" label="城市" width="90" />
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="publishStatusTag((row as OpsContentSummary).status)">{{ publishStatusLabel((row as OpsContentSummary).status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发布时间" width="150">
            <template #default="{ row }">{{ formatDateTime((row as OpsContentSummary).publishedAt) }}</template>
          </el-table-column>
          <el-table-column label="最后修改" width="150">
            <template #default="{ row }">{{ formatDateTime((row as OpsContentSummary).updatedAt) }}</template>
          </el-table-column>
        </PagedTable>
      </el-tab-pane>

      <el-tab-pane label="专题" name="topics">
        <el-form class="filters" inline @submit.prevent="topics.search()">
          <el-form-item label="关键词">
            <el-input v-model="topics.filters.keyword" clearable maxlength="20" placeholder="标题或发布方" @clear="topics.search()" />
          </el-form-item>
          <el-form-item label="城市">
            <CityPicker v-model="topics.filters.cityCode" placeholder="全部城市" @update:model-value="topics.search()" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="topics.filters.status" clearable placeholder="全部" style="width: 110px" @change="topics.search()">
              <el-option v-for="option in PUBLISH_STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" native-type="submit">查询</el-button>
            <el-button @click="topics.reset()">重置</el-button>
          </el-form-item>
        </el-form>
        <PagedTable
          :items="topics.items.value"
          :total="topics.total.value"
          :page="topics.page.value"
          :size="topics.size.value"
          :loading="topics.loading.value"
          :error="topics.error.value"
          row-key="topicId"
          empty-text="没有符合条件的专题"
          @change-page="topics.changePage"
          @change-size="topics.changeSize"
          @retry="topics.load()"
        >
          <el-table-column label="标题" min-width="220">
            <template #default="{ row }">
              <el-button link type="primary" @click="router.push({ name: 'content-topic', params: { topicId: (row as OpsTopicSummary).topicId } })">
                {{ (row as OpsTopicSummary).title }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column prop="publisherName" label="发布方" min-width="140" />
          <el-table-column prop="cityCode" label="城市" width="90" />
          <el-table-column label="起止日期" width="190">
            <template #default="{ row }">{{ (row as OpsTopicSummary).startsOn }} 至 {{ (row as OpsTopicSummary).endsOn }}</template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag :type="publishStatusTag((row as OpsTopicSummary).status)">{{ publishStatusLabel((row as OpsTopicSummary).status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最后修改" width="150">
            <template #default="{ row }">{{ formatDateTime((row as OpsTopicSummary).updatedAt) }}</template>
          </el-table-column>
        </PagedTable>
      </el-tab-pane>
    </el-tabs>
  </PageSection>
</template>

<style scoped>
.filters {
  margin-bottom: 4px;
}
</style>
