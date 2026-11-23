<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref, shallowRef } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import { errorMessage } from '@/core/http/api-error'
import { confirmAction, showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'
import PageSection from '@/core/ui/PageSection.vue'

import {
  createLiveHostApi,
  ITEM_TYPES,
  itemTypeLabel,
  statusLabel,
  type LiveComment,
  type LiveCommentPage,
  type LiveHostRoom,
} from './live-host-api'
import LiveRoomFormDialog from './LiveRoomFormDialog.vue'
import { itemsProblem, maskPushUrl, parseItemRef, replayProblem, type ItemDraft } from './live-rules'

const route = useRoute()
const router = useRouter()
const api = createLiveHostApi(http)
const roomId = computed(() => String(route.params.roomId ?? ''))

const host = shallowRef<LiveHostRoom | null>(null)
const loading = ref(false)
const error = ref('')
const busy = ref(false)
const editing = ref(false)
const showPushUrl = ref(false)

const items = reactive<ItemDraft[]>([])
const currentSeq = ref<number | null>(null)
const replay = reactive({ url: '', platform: '' })
const comments = shallowRef<LiveCommentPage | null>(null)
const commentsError = ref('')

const room = computed(() => host.value?.room ?? null)
const ended = computed(() => room.value?.status === 'ENDED')
const hidden = computed(() => new Set(comments.value?.hiddenSeqs ?? []))

function apply(next: LiveHostRoom): void {
  host.value = next
  items.splice(0, items.length, ...next.room.items.map((item) => ({ type: item.type, ref: item.refId, title: item.title })))
  currentSeq.value = next.room.currentItemSeq
  replay.url = next.room.replayUrl ?? ''
  replay.platform = next.room.replayPlatformName ?? ''
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    apply(await api.get(roomId.value))
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
  void loadComments()
}

async function loadComments(): Promise<void> {
  commentsError.value = ''
  try {
    comments.value = await api.comments(roomId.value)
  } catch (failure) {
    commentsError.value = errorMessage(failure)
  }
}

async function run(title: string, message: string, action: () => Promise<LiveHostRoom>, done: string): Promise<void> {
  if (busy.value) return
  if (!(await confirmAction({ title, message, confirmText: '确认' }))) return
  busy.value = true
  try {
    apply(await action())
    ElMessage.success(done)
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

function addItem(): void {
  items.push({ type: 'PRODUCT', ref: '', title: '' })
}

function removeItem(index: number): void {
  items.splice(index, 1)
  // 当前讲解项跟着它原来那一项走：删的就是它时清空，删了前面的时序号往前挪。
  const seq = index + 1
  if (currentSeq.value === seq) currentSeq.value = null
  else if (currentSeq.value !== null && currentSeq.value > seq) currentSeq.value -= 1
}

async function saveItems(): Promise<void> {
  const issue = itemsProblem(items)
  if (issue) {
    ElMessage.warning(issue)
    return
  }
  const request = items.map((item) => ({ type: item.type, refId: parseItemRef(item.type, item.ref)!, title: item.title.trim() }))
  await run('保存讲解清单', '观众在直播间“讲解”里看到这份清单，点进去是平台内的详情页。', () =>
    api.setItems(roomId.value, request, currentSeq.value), '已保存讲解清单')
}

async function switchCurrent(seq: number | null): Promise<void> {
  await run('换当前讲解项', seq === null ? '直播间不再显示“正在讲解”。' : `直播间显示正在讲解第 ${seq} 项。`,
    () => api.setCurrentItem(roomId.value, seq), '已换当前讲解项')
}

async function saveReplay(): Promise<void> {
  const issue = replayProblem(replay.url, replay.platform)
  if (issue) {
    ElMessage.warning(issue)
    return
  }
  const url = replay.url.trim() || null
  const platform = replay.platform.trim() || null
  await run('保存回看链接', url ? '观众在 App 里点“看回放”会离开 App 打开这个链接。' : '去掉回看链接。',
    () => api.setReplay(roomId.value, url, platform), '已保存回看链接')
}

async function hide(comment: LiveComment): Promise<void> {
  if (busy.value) return
  const confirmed = await confirmAction({
    title: '隐藏弹幕',
    message: `隐藏后所有观众都看不到这条：“${comment.text}”。隐藏不能撤回。`,
    confirmText: '隐藏',
    danger: true,
  })
  if (!confirmed) return
  busy.value = true
  try {
    await api.hideComment(roomId.value, comment.commentId)
    ElMessage.success('已隐藏')
    await loadComments()
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}

async function copyPushUrl(): Promise<void> {
  if (!host.value) return
  try {
    await navigator.clipboard.writeText(host.value.pushUrl)
    ElMessage.success('已复制推流地址')
  } catch {
    ElMessage.warning('浏览器不让复制，请点“显示”后手动选中复制')
  }
}

function edited(next: LiveHostRoom): void {
  editing.value = false
  apply(next)
}

onMounted(() => {
  void load()
})
</script>

<template>
  <PageSection :title="room?.title ?? '直播间'" :description="room ? `${statusLabel(room.status)} · ${room.host.name}` : undefined">
    <template #actions>
      <el-button v-if="room?.status === 'SCHEDULED'" @click="editing = true">改预告</el-button>
      <el-button @click="load">刷新</el-button>
      <el-button @click="router.push({ name: 'workbench-live-rooms' })">返回列表</el-button>
    </template>

    <el-alert v-if="error" type="error" :title="error" :closable="false" show-icon>
      <el-button size="small" @click="load">重新加载</el-button>
    </el-alert>

    <div v-loading="loading">
      <template v-if="host && room">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="状态">
            {{ statusLabel(room.status) }}<template v-if="room.streamInterrupted">（断流中）</template>
          </el-descriptions-item>
          <el-descriptions-item label="预计开播">{{ formatDateTime(room.scheduledAt) }}</el-descriptions-item>
          <el-descriptions-item label="开播 / 结束">
            {{ formatDateTime(room.startedAt) }} / {{ formatDateTime(room.endedAt) }}
          </el-descriptions-item>
          <el-descriptions-item label="观看 / 点赞">{{ room.viewerCount }} / {{ room.likeCount }}</el-descriptions-item>
          <el-descriptions-item label="主讲">{{ [room.speakerName, room.speakerTitle].filter(Boolean).join(' · ') || '—' }}</el-descriptions-item>
          <el-descriptions-item label="城市">{{ room.cityCode ?? '全国可见' }}</el-descriptions-item>
          <el-descriptions-item label="讲什么" :span="2">
            <div v-for="topic in room.topics" :key="topic">{{ topic }}</div>
            <span v-if="room.topics.length === 0">—</span>
          </el-descriptions-item>
          <el-descriptions-item label="推流地址" :span="2">
            <div class="push">
              <code class="push__url" data-test="push-url">{{ showPushUrl ? host.pushUrl : maskPushUrl(host.pushUrl) }}</code>
              <el-button size="small" @click="showPushUrl = !showPushUrl">{{ showPushUrl ? '隐藏' : '显示' }}</el-button>
              <el-button size="small" @click="copyPushUrl">复制</el-button>
            </div>
            <div class="warn">推流地址只给本机构主播填进推流工具，不要外传：拿到它的人就能用你们的直播间开播。</div>
          </el-descriptions-item>
        </el-descriptions>

        <h2 class="block-title">讲解清单</h2>
        <p class="muted">
          对象 ID 可以直接粘，也可以粘 App 详情页“分享 → 复制链接”得到的链接（页面会从链接里取出 ID）。
          平台不检查对象是否存在，粘错了观众点进去会提示找不到，保存前请核对。最多 10 项。
        </p>
        <div class="items">
          <div v-for="(item, index) in items" :key="index" class="item">
            <span class="item__seq">{{ index + 1 }}</span>
            <el-select v-model="item.type" style="width: 120px" :disabled="ended">
              <el-option v-for="option in ITEM_TYPES" :key="option.value" :label="option.label" :value="option.value" />
            </el-select>
            <el-input v-model="item.ref" placeholder="对象 ID 或分享链接" style="width: 300px" :disabled="ended" />
            <el-input v-model="item.title" placeholder="标题（≤40 字）" maxlength="40" style="width: 200px" :disabled="ended" />
            <el-radio :model-value="currentSeq ?? undefined" :value="index + 1" :disabled="ended"
                      @change="currentSeq = index + 1">当前讲解</el-radio>
            <el-button v-if="!ended" link type="danger" @click="removeItem(index)">删除</el-button>
          </div>
          <div v-if="!ended" class="item-actions">
            <el-button size="small" :disabled="items.length >= 10" @click="addItem">加一项</el-button>
            <el-button size="small" :disabled="currentSeq === null" @click="currentSeq = null">不设当前讲解</el-button>
            <el-button size="small" type="primary" :loading="busy" @click="saveItems">保存讲解清单</el-button>
          </div>
        </div>
        <div v-if="!ended && room.items.length" class="switch">
          只换当前讲解项（不改清单）：
          <el-button v-for="item in room.items" :key="item.seq" size="small"
                     :type="room.currentItemSeq === item.seq ? 'primary' : 'default'" :disabled="busy"
                     @click="switchCurrent(item.seq)">
            {{ item.seq }}. {{ itemTypeLabel(item.type) }} · {{ item.title }}
          </el-button>
          <el-button size="small" :disabled="busy || room.currentItemSeq === null" @click="switchCurrent(null)">都不讲</el-button>
        </div>

        <h2 class="block-title">回看链接</h2>
        <div class="replay">
          <el-input v-model="replay.url" placeholder="合作平台的 https 回看链接" maxlength="500" style="width: 360px" />
          <el-input v-model="replay.platform" placeholder="平台名（≤20 字）" maxlength="20" style="width: 180px" />
          <el-button :loading="busy" @click="saveReplay">保存</el-button>
        </div>

        <h2 class="block-title">
          最近弹幕
          <el-button size="small" @click="loadComments">刷新</el-button>
        </h2>
        <el-alert v-if="commentsError" type="error" :title="commentsError" :closable="false" />
        <el-table :data="comments?.items ?? []" row-key="commentId" border empty-text="还没有弹幕" max-height="420">
          <el-table-column label="时间" width="150">
            <template #default="{ row }">{{ formatDateTime((row as LiveComment).sentAt) }}</template>
          </el-table-column>
          <el-table-column label="谁" width="140">
            <template #default="{ row }">
              {{ (row as LiveComment).authorName }}
              <el-tag v-if="(row as LiveComment).side === 'HOST'" size="small">主播</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="内容" min-width="240">
            <template #default="{ row }">{{ (row as LiveComment).text }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="{ row }">
              <span v-if="hidden.has((row as LiveComment).seq)" class="muted">已隐藏</span>
              <el-button v-else link type="danger" :disabled="busy" @click="hide(row as LiveComment)">隐藏</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </div>
  </PageSection>

  <LiveRoomFormDialog v-if="editing && room" :api="api" :room="room" @saved="edited" @close="editing = false" />
</template>

<style scoped>
.block-title {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 20px 0 10px;
  font-size: 16px;
}

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.warn {
  margin-top: 4px;
  font-size: 12px;
  color: #c45656;
}

.push {
  display: flex;
  align-items: center;
  gap: 8px;
}

.push__url {
  word-break: break-all;
}

.items {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.item,
.replay,
.item-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.item__seq {
  width: 20px;
  color: var(--admin-text-secondary);
}

.switch {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 12px;
  font-size: 13px;
}
</style>
