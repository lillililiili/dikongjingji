<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'
import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import { experienceApi } from '@/features/experience/data/experience-api-client'
import type { ExperienceItemDetail } from '@/features/experience/domain/experience-record'
import OnlineExperienceUnavailable from '@/features/experience/presentation/shared/OnlineExperienceUnavailable.vue'
import { ApiRequestError } from '@/shared/http/public-api-request'

const id = ref('')
const item = ref<ExperienceItemDetail|null>(null)
const state = ref<'loading'|'unavailable'|'error'|'ready'>('loading')
const playbackError = ref(false)
let revision = 0
async function load() {
  if (!EXPERIENCE_PREVIEW_ENABLED) return
  const request = ++revision
  item.value=null
  playbackError.value=false
  if (!id.value) { state.value='unavailable'; return }
  state.value='loading'
  try {
    const result = await experienceApi.loadExperienceItem(id.value)
    if (request !== revision) return
    if (result.type === 'RECORDED_DEMO') item.value=result
    state.value=item.value ? 'ready' : 'unavailable'
  } catch (error) {
    if (request === revision) state.value=error instanceof ApiRequestError && error.status===404 ? 'unavailable' : 'error'
  }
}
onLoad(query => { id.value=typeof query?.experienceId === 'string' ? query.experienceId : '' })
onShow(load)
</script>

<template>
  <OnlineExperienceUnavailable v-if="!EXPERIENCE_PREVIEW_ENABLED" />
  <view v-else class="recorded" data-testid="recorded-page">
    <text v-if="state==='loading'">正在加载录播…</text>
    <text v-else-if="state==='unavailable'">该录播项目暂不可用</text>
    <view v-else-if="state==='error'"><text>录播内容加载失败</text><button size="mini" @tap="load">重新加载</button></view>
    <view v-else-if="item">
      <video v-if="item.videoUrl && !playbackError" class="player" controls :src="item.videoUrl" :poster="item.coverImage?.url" @error="playbackError=true" />
      <view v-else class="empty"><text>{{playbackError?'视频播放失败':'该项目尚未配置录播视频'}}</text><button v-if="playbackError" size="mini" @tap="load">重试播放</button></view>
      <text class="title">{{item.title}}</text><text v-if="item.summary" class="summary">{{item.summary}}</text>
    </view>
  </view>
</template>
<style scoped>
.recorded { padding: 28rpx; color:#233249; }
.player,.empty { width:100%; aspect-ratio:16/9; height:auto; border-radius:16rpx; overflow:hidden; }
.empty { background:#eef2f7; display:flex; align-items:center; justify-content:center; flex-direction:column; color:#526275; }
.title,.summary { display:block; margin-top:24rpx; }
.title { font-size:38rpx; font-weight:650; }
.summary { line-height:1.65; }
</style>
