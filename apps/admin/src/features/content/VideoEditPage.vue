<script setup lang="ts">
/**
 * 视频的新建和编辑：标题、作者、城市、封面、播放地址（经 /api/v1/ops/media 上传 MP4 或粘贴受控 https 地址）、时长、
 * 横竖屏、一句话说明、话题、运营排序、内容中提到（第一项是视频挂的入口）；上线、下线；改动记录。
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
import PublishDialog from './components/PublishDialog.vue'
import RefsEditor from './components/RefsEditor.vue'
import { createContentOpsApi, type OpsVideo } from './content-api'
import {
  LIMITS,
  MISSING_LABELS,
  ORIENTATION_LABELS,
  SUBJECT_LABELS,
  formatDuration,
  tagsFromText,
  videoForm,
  videoProblems,
  videoRequest,
  type VideoForm,
} from './content-rules'
import { useContentEditor } from './use-content-editor'

const route = useRoute()
const router = useRouter()
const api = createContentOpsApi(http)
const form = reactive<VideoForm>(videoForm())
const editor = useContentEditor<OpsVideo>(api.video, api.videoChanges, form)
const publishing = ref(false)

const videoId = computed(() => (route.params.videoId ? String(route.params.videoId) : null))
const video = computed(() => editor.entity.value)
const problems = computed(() => videoProblems(form, editor.entity.value))
const tags = computed(() => tagsFromText(form.tagsText))

watch(video, (current) => {
  if (!current) return
  Object.assign(form, videoForm(current))
  editor.markSaved()
})

async function save(): Promise<void> {
  if (editor.busy.value || (videoId.value && !video.value)) return
  if (!form.title.trim()) {
    showError(new Error('请填写标题'))
    return
  }
  if (problems.value.length) {
    showError(new Error(problems.value[0]))
    return
  }
  const current = video.value
  const live = current?.status === 'PUBLISHED'
  const confirmed = await confirmAction({
    title: current ? '确认保存修改' : '确认新建草稿',
    message: current
      ? (live ? '这条视频已上线，保存后 App 视频流马上显示新内容；封面和播放地址要保持齐全。' : '保存修改，草稿在 App 上看不到。')
      : '新建一条视频草稿，上线前 App 上看不到。',
    confirmText: '保存',
    danger: live,
  })
  if (!confirmed) return
  const saved = await editor.run(
    () => (current
      ? api.updateVideo(current.videoId, videoRequest(form, current.version))
      : api.createVideo(videoRequest(form, null))),
    current ? '保存修改' : '新建草稿',
    (entity) => entity.videoId,
  )
  if (saved && !current) await router.replace({ name: 'content-video', params: { videoId: saved.videoId } })
}

async function publish(publishAt: string | null): Promise<void> {
  const current = video.value
  if (!current || editor.dirty.value) return
  const done = await editor.run(() => api.publishVideo(current.videoId, current.version, publishAt), '上线',
    (entity) => entity.videoId)
  if (done) publishing.value = false
}

async function offline(): Promise<void> {
  const current = video.value
  if (!current || editor.dirty.value) return
  const confirmed = await confirmAction({
    title: '确认下线',
    message: `下线后 App 发现页和视频流里都不再显示“${current.title}”，点赞和评论保留，可以再上线。`,
    confirmText: '下线',
    danger: true,
  })
  if (!confirmed) return
  await editor.run(() => api.offlineVideo(current.videoId, current.version), '下线', (entity) => entity.videoId)
}

onMounted(() => {
  if (videoId.value) void editor.load(videoId.value)
})
</script>

<template>
  <PageSection
    :title="video?.title ?? (videoId ? '视频详情' : '新建视频')"
    :description="video ? `${ORIENTATION_LABELS[video.orientation]} · ${formatDuration(video.durationSeconds)} · ${video.author.name}${video.sponsored ? ' · 广告' : ''}` : '视频要有城市；上线前要有封面和播放地址。'"
    traced
  >
    <template #actions>
      <el-button @click="router.push({ name: 'content', query: { tab: 'videos' } })">返回列表</el-button>
      <template v-if="video">
        <el-button v-if="video.status !== 'PUBLISHED'" type="success" :disabled="editor.busy.value || editor.dirty.value" @click="publishing = true">上线</el-button>
        <el-button v-else type="danger" plain :disabled="editor.busy.value || editor.dirty.value" @click="offline">下线</el-button>
      </template>
      <el-button v-if="!videoId || video" type="primary" :loading="editor.busy.value" @click="save">保存</el-button>
    </template>

    <el-alert v-if="editor.error.value" type="error" :title="editor.error.value" :closable="false" show-icon>
      <el-button v-if="videoId" size="small" @click="editor.load(videoId)">重新加载</el-button>
    </el-alert>
    <div v-else v-loading="editor.loading.value || editor.busy.value" class="video-edit">
      <el-alert v-if="editor.dirty.value" title="内容有未保存的修改，请先保存再调整上线状态。" type="info" :closable="false" />
      <div v-if="video" class="video-edit__status">
        <el-tag :type="publishStatusTag(video.status)">{{ publishStatusLabel(video.status) }}</el-tag>
        <span>发布时间 {{ formatDateTime(video.publishedAt) }}</span>
        <span>版本 {{ video.version }} · 最后修改 {{ formatDateTime(video.updatedAt) }}</span>
        <span v-if="video.publishMissing.length" class="video-edit__missing">
          上线还差：{{ video.publishMissing.map((key) => MISSING_LABELS[key] ?? key).join('、') }}
        </span>
      </div>

      <el-form :disabled="editor.loading.value || editor.busy.value" label-width="96px" @submit.prevent>
        <el-form-item label="标题" required>
          <el-input v-model="form.title" :maxlength="LIMITS.title" show-word-limit placeholder="比如“4K 航拍黄河口”" />
        </el-form-item>
        <el-form-item label="作者" required>
          <AuthorField v-model="form.author" />
        </el-form-item>
        <el-form-item label="城市" required>
          <CityPicker v-model="form.cityCode" />
        </el-form-item>
        <el-form-item label="封面">
          <MediaField v-model="form.coverUrl" kind="IMAGE" />
        </el-form-item>
        <el-form-item label="播放地址">
          <MediaField v-model="form.playUrl" kind="VIDEO" />
        </el-form-item>
        <el-form-item label="时长（秒）" required>
          <el-input-number v-model="form.durationSeconds" :min="1" :max="LIMITS.videoSeconds" />
          <span class="video-edit__hint">{{ formatDuration(form.durationSeconds || 0) }}</span>
        </el-form-item>
        <el-form-item label="画面方向" required>
          <el-radio-group v-model="form.orientation">
            <el-radio-button value="PORTRAIT">竖屏</el-radio-button>
            <el-radio-button value="LANDSCAPE">横屏</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="一句话说明">
          <el-input v-model="form.caption" type="textarea" :autosize="{ minRows: 2, maxRows: 4 }"
                    :maxlength="LIMITS.videoCaption" show-word-limit />
        </el-form-item>
        <el-form-item label="话题">
          <el-input v-model="form.tagsText" placeholder="空格分隔，比如“航拍 黄河口”；# 会去掉" style="width: 360px" />
          <span class="video-edit__hint">{{ tags.length }} / {{ LIMITS.tags }} 个</span>
        </el-form-item>
        <el-form-item label="运营排序">
          <el-input-number v-model="form.rank" :min="0" :max="LIMITS.rank" />
          <span class="video-edit__hint">大的在前，再按发布时间。</span>
        </el-form-item>
        <el-form-item label="示例数据">
          <el-switch v-model="form.sample" />
        </el-form-item>
        <el-form-item label="内容中提到">
          <RefsEditor :refs="form.refs" first-hint="第一项是视频挂的入口" />
        </el-form-item>
      </el-form>

      <template v-if="video">
        <h3 class="video-edit__section">改动记录</h3>
        <ChangeLogTable :changes="editor.changes.value" :error="editor.changesError.value" :loading="editor.changesLoading.value" :subject-labels="SUBJECT_LABELS" />
      </template>
    </div>
  </PageSection>

  <PublishDialog
    :open="publishing"
    :title="video?.title ?? ''"
    :busy="editor.busy.value"
    @close="publishing = false"
    @confirm="publish"
  />
</template>

<style scoped>
.video-edit {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.video-edit__status {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 16px;
  font-size: 13px;
  color: var(--admin-text-secondary);
}

.video-edit__missing {
  color: var(--el-color-warning-dark-2);
}

.video-edit__hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.video-edit__section {
  margin: 8px 0 0;
  font-size: 15px;
}
</style>
