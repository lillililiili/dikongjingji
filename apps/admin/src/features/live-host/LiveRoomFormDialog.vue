<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { reactive, ref } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import { confirmAction, showError } from '@/core/ui/feedback'

import type { LiveHostApi, LiveHostRoom, LiveRoom } from './live-host-api'
import { draftOf, draftProblem, emptyDraft, requestOf } from './live-rules'

/** 建直播间（预告）或改预告。只有预告能改；设过提醒的观众不另外通知。 */
const props = defineProps<{
  api: LiveHostApi
  /** 传了就是改这一场的预告，不传是新建。 */
  room?: LiveRoom | null
}>()

const emit = defineEmits<{
  saved: [room: LiveHostRoom]
  close: []
}>()

const draft = reactive(props.room ? draftOf(props.room) : emptyDraft())
const busy = ref(false)
let key = newIdempotencyKey()

async function submit(): Promise<void> {
  if (busy.value) return
  const issue = draftProblem(draft)
  if (issue) {
    ElMessage.warning(issue)
    return
  }
  const editing = !!props.room
  const confirmed = await confirmAction({
    title: editing ? '保存预告' : '建直播间',
    message: editing
      ? '改好后 App 的直播列表和预告页马上显示新内容；设过提醒的观众不另外通知。'
      : '建好后是预告，出现在 App 发现页的直播列表里；开播用推流工具推流（推流地址在直播间详情里）。',
    confirmText: editing ? '保存' : '建直播间',
  })
  if (!confirmed) return
  busy.value = true
  try {
    const saved = props.room
      ? await props.api.update(props.room.roomId, requestOf(draft))
      : await props.api.create(requestOf(draft), key)
    key = newIdempotencyKey()
    ElMessage.success(editing ? '已保存预告' : '已建直播间')
    emit('saved', saved)
  } catch (failure) {
    showError(failure)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <el-dialog :model-value="true" :title="room ? '改预告' : '建直播间'" width="600px" :close-on-click-modal="false"
             @close="emit('close')">
    <el-form label-width="96px">
      <el-form-item label="标题" required>
        <el-input v-model="draft.title" maxlength="30" show-word-limit />
      </el-form-item>
      <el-form-item label="副标题">
        <el-input v-model="draft.subtitle" maxlength="40" show-word-limit />
      </el-form-item>
      <el-form-item label="开播时间" required>
        <el-date-picker v-model="draft.scheduledAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss.SSSZ"
                        :clearable="false" />
        <div class="hint">要在现在之后、90 天以内。</div>
      </el-form-item>
      <el-form-item label="城市编码">
        <el-input v-model="draft.cityCode" maxlength="6" placeholder="如 370500（东营），空着表示全国可见"
                  style="width: 300px" />
      </el-form-item>
      <el-form-item label="主讲人">
        <el-input v-model="draft.speakerName" maxlength="20" placeholder="如 王工" style="width: 160px" />
        <el-input v-model="draft.speakerTitle" maxlength="30" placeholder="身份，如 项目负责人"
                  style="width: 220px; margin-left: 8px" />
      </el-form-item>
      <el-form-item label="讲什么">
        <el-input v-model="draft.topics" type="textarea" :rows="4" placeholder="一行一条，最多 5 条，每条 ≤40 字" />
      </el-form-item>
      <el-form-item label="封面地址">
        <el-input v-model="draft.coverUrl" maxlength="500" placeholder="https 图片地址（选填，要有使用权）" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('close')">取消</el-button>
      <el-button type="primary" :loading="busy" @click="submit">{{ room ? '保存' : '建直播间' }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.hint {
  width: 100%;
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
