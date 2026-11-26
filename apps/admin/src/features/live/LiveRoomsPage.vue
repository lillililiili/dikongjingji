<script setup lang="ts">
/**
 * 直播间（契约 openapi-ops-live.yaml）：全部直播间、强制结束（写原因）、已结束的维护回看链接、改动记录；
 * 另一个标签页是开通了直播主办的机构（开通、收回在“机构与资质”里给账号改角色）。
 */
import { onMounted, reactive, ref } from 'vue'

import { http } from '@/core/http'
import ChangeLogTable from '@/core/ops/ChangeLogTable.vue'
import type { OpsChange } from '@/core/ops/ops-common'
import { confirmAction, notifyRecorded, showError } from '@/core/ui/feedback'
import { charCount, formatDateTime } from '@/core/ui/format'
import PagedTable from '@/core/ui/PagedTable.vue'
import PageSection from '@/core/ui/PageSection.vue'
import { usePagedList } from '@/core/ui/use-paged-list'

import LiveHostsPanel from './LiveHostsPanel.vue'
import {
  createLiveApi,
  END_REASON_LABELS,
  LIVE_ACTION_LABELS,
  LIVE_LIMITS,
  LIVE_STATUS_OPTIONS,
  LIVE_SUBJECT_LABELS,
  liveStatusLabel,
  liveStatusTag,
  replayProblem,
  type LiveRoomFilters,
  type OpsLiveRoom,
} from './live-api'

const api = createLiveApi(http)
const tab = ref<'rooms' | 'hosts'>('rooms')
const list = usePagedList<OpsLiveRoom, LiveRoomFilters>((filters, page, size) => api.rooms(filters, page, size),
  { keyword: '', status: '', hostOrganizationId: '' })

const ending = reactive({ open: false, room: null as OpsLiveRoom | null, note: '', error: '', busy: false })
const replayForm = reactive({
  open: false, room: null as OpsLiveRoom | null, replayUrl: '', replayPlatformName: '', error: '', busy: false,
})
const changeLog = reactive({ open: false, room: null as OpsLiveRoom | null, changes: [] as OpsChange[], loading: false })

function openEnd(room: OpsLiveRoom): void {
  Object.assign(ending, { open: true, room, note: '', error: '', busy: false })
}

async function submitEnd(): Promise<void> {
  const room = ending.room
  const note = ending.note.trim()
  if (!room || ending.busy) return
  if (!note || charCount(note) > LIVE_LIMITS.note) {
    ending.error = `请写强制结束的原因（1–${LIVE_LIMITS.note} 字）`
    return
  }
  ending.error = ''
  const confirmed = await confirmAction({
    title: '确认强制结束',
    message: `“${room.title}”会立刻结束，App 上的观众看不到画面了，主办方也不能再开播这一场。确定吗？`,
    confirmText: '强制结束',
    danger: true,
  })
  if (!confirmed) return
  ending.busy = true
  try {
    await api.end(room.roomId, note, room.version)
    ending.open = false
    notifyRecorded('强制结束直播间', '改动记录')
    await list.load()
  } catch (failure) {
    showError(failure)
  } finally {
    ending.busy = false
  }
}

function openReplay(room: OpsLiveRoom): void {
  Object.assign(replayForm, {
    open: true,
    room,
    replayUrl: room.replayUrl ?? '',
    replayPlatformName: room.replayPlatformName ?? '',
    error: '',
    busy: false,
  })
}

async function submitReplay(): Promise<void> {
  const room = replayForm.room
  if (!room || replayForm.busy) return
  const draft = { replayUrl: replayForm.replayUrl, replayPlatformName: replayForm.replayPlatformName }
  const problem = replayProblem(draft)
  if (problem) {
    replayForm.error = problem
    return
  }
  replayForm.error = ''
  const clearing = !draft.replayUrl.trim()
  const confirmed = await confirmAction({
    title: clearing ? '确认去掉回看' : '确认保存回看',
    message: clearing
      ? 'App 的直播列表里，已结束的直播只列有回看的；去掉后这一场就不再出现。'
      : '保存后 App 上这一场显示“看回看”，点开去这个地址。',
    confirmText: '保存',
    danger: clearing,
  })
  if (!confirmed) return
  replayForm.busy = true
  try {
    await api.replay(room.roomId, draft, room.version)
    replayForm.open = false
    notifyRecorded(clearing ? '去掉回看' : '保存回看链接', '改动记录')
    await list.load()
  } catch (failure) {
    showError(failure)
  } finally {
    replayForm.busy = false
  }
}

async function openHistory(room: OpsLiveRoom): Promise<void> {
  Object.assign(changeLog, { open: true, room, changes: [], loading: true })
  try {
    changeLog.changes = await api.changes(room.roomId)
  } catch (failure) {
    showError(failure)
  } finally {
    changeLog.loading = false
  }
}

/** 从主办机构标签页点“看直播间”：按这家机构筛。 */
function showHostRooms(organizationId: string): void {
  list.filters.hostOrganizationId = organizationId
  tab.value = 'rooms'
  void list.search()
}

function clearHostFilter(): void {
  list.filters.hostOrganizationId = ''
  void list.search()
}

onMounted(() => {
  void list.load()
})
</script>

<template>
  <PageSection
    title="直播间"
    description="全部机构的直播间：强制结束、给已结束的设回看。开通直播主办在“机构与资质”里给账号授“直播主办”角色。"
    traced
  >
    <el-tabs v-model="tab">
      <el-tab-pane label="直播间" name="rooms">
        <el-form inline @submit.prevent="list.search()">
          <el-form-item label="直播间">
            <el-input v-model="list.filters.keyword" clearable maxlength="20" placeholder="标题或主办机构" />
          </el-form-item>
          <el-form-item label="状态">
            <el-select v-model="list.filters.status" clearable placeholder="全部" style="width: 120px">
              <el-option v-for="option in LIVE_STATUS_OPTIONS" :key="option.value" :value="option.value" :label="option.label" />
            </el-select>
          </el-form-item>
          <el-form-item>
            <el-button type="primary" native-type="submit">查询</el-button>
            <el-button @click="list.reset()">重置</el-button>
          </el-form-item>
          <el-form-item v-if="list.filters.hostOrganizationId">
            <el-tag closable @close="clearHostFilter">只看一家主办机构</el-tag>
          </el-form-item>
        </el-form>

        <PagedTable
          :items="list.items.value"
          :total="list.total.value"
          :page="list.page.value"
          :size="list.size.value"
          :loading="list.loading.value"
          :error="list.error.value"
          row-key="roomId"
          origin-resource="LIVE"
          empty-text="没有符合条件的直播间"
          @change-page="list.changePage"
          @change-size="list.changeSize"
          @retry="list.load"
        >
          <el-table-column label="直播间" min-width="220">
            <template #default="{ row }">
              <div class="live-title">{{ (row as OpsLiveRoom).title }}</div>
              <div class="live-sub">{{ (row as OpsLiveRoom).hostName }}{{ (row as OpsLiveRoom).cityCode ? '' : ' · 全国可见' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="130">
            <template #default="{ row }">
              <el-tag :type="liveStatusTag((row as OpsLiveRoom).status)">{{ liveStatusLabel((row as OpsLiveRoom).status) }}</el-tag>
              <el-tag v-if="(row as OpsLiveRoom).streamInterrupted" type="warning" class="live-tag">断流中</el-tag>
              <div v-if="(row as OpsLiveRoom).endReason" class="live-sub">
                {{ END_REASON_LABELS[(row as OpsLiveRoom).endReason!] }}
              </div>
            </template>
          </el-table-column>
          <el-table-column label="开播时间" width="150">
            <template #default="{ row }">{{ formatDateTime((row as OpsLiveRoom).scheduledAt) }}</template>
          </el-table-column>
          <el-table-column label="观看 / 点赞 / 弹幕" width="150">
            <template #default="{ row }">
              {{ (row as OpsLiveRoom).viewerCount }} / {{ (row as OpsLiveRoom).likeCount }} / {{ (row as OpsLiveRoom).commentCount }}
            </template>
          </el-table-column>
          <el-table-column label="回看" min-width="160">
            <template #default="{ row }">
              <template v-if="(row as OpsLiveRoom).replayUrl">
                {{ (row as OpsLiveRoom).replayPlatformName }}
                <div class="live-sub live-url">{{ (row as OpsLiveRoom).replayUrl }}</div>
              </template>
              <span v-else class="live-sub">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button
                link
                type="danger"
                :disabled="(row as OpsLiveRoom).status === 'ENDED'"
                @click="openEnd(row as OpsLiveRoom)"
              >
                强制结束
              </el-button>
              <el-button link type="primary" :disabled="(row as OpsLiveRoom).status !== 'ENDED'" @click="openReplay(row as OpsLiveRoom)">
                回看
              </el-button>
              <el-button link @click="openHistory(row as OpsLiveRoom)">改动记录</el-button>
            </template>
          </el-table-column>
        </PagedTable>
      </el-tab-pane>
      <el-tab-pane label="主办机构" name="hosts" lazy>
        <LiveHostsPanel @show-rooms="showHostRooms" />
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="ending.open" title="强制结束直播间" width="480px" :close-on-click-modal="false">
      <p class="live-dialog-lead">{{ ending.room?.title }} · {{ ending.room?.hostName }}</p>
      <el-input
        v-model="ending.note"
        type="textarea"
        :rows="3"
        :maxlength="LIVE_LIMITS.note"
        show-word-limit
        placeholder="原因（写进改动记录），比如：画面违规、主办方要求取消"
      />
      <p v-if="ending.error" class="live-error">{{ ending.error }}</p>
      <template #footer>
        <el-button @click="ending.open = false">取消</el-button>
        <el-button type="danger" :loading="ending.busy" @click="submitEnd">强制结束</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="replayForm.open" title="回看链接" width="520px" :close-on-click-modal="false">
      <p class="live-dialog-lead">{{ replayForm.room?.title }}：App 上点“看回看”去这个地址；两项都清空表示去掉回看。</p>
      <div class="live-fields">
        <label class="live-field">
          <span>回看地址</span>
          <el-input v-model="replayForm.replayUrl" maxlength="500" placeholder="以 https 开头的受控地址" />
        </label>
        <label class="live-field">
          <span>平台名称</span>
          <el-input v-model="replayForm.replayPlatformName" :maxlength="LIVE_LIMITS.platformName" show-word-limit placeholder="比如：视频号" />
        </label>
      </div>
      <p v-if="replayForm.error" class="live-error">{{ replayForm.error }}</p>
      <template #footer>
        <el-button @click="replayForm.open = false">取消</el-button>
        <el-button type="primary" :loading="replayForm.busy" @click="submitReplay">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="changeLog.open" :title="`改动记录 · ${changeLog.room?.title ?? ''}`" size="640px">
      <ChangeLogTable
        :changes="changeLog.changes"
        :loading="changeLog.loading"
        :subject-labels="LIVE_SUBJECT_LABELS"
        :action-labels="LIVE_ACTION_LABELS"
      />
    </el-drawer>
  </PageSection>
</template>

<style scoped>
.live-title {
  font-weight: 500;
}

.live-sub {
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.live-url {
  word-break: break-all;
}

.live-tag {
  margin-left: 4px;
}

.live-dialog-lead {
  margin: 0 0 12px;
  color: var(--admin-text-secondary);
}

.live-fields {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.live-field {
  display: grid;
  grid-template-columns: 72px 1fr;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.live-error {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--el-color-danger);
}
</style>
