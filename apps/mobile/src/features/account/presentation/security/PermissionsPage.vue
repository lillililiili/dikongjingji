<script setup lang="ts">
import { computed, ref } from 'vue'

import AppPageHeader from '@/shared/ui/AppPageHeader.vue'
import IconTile from '@/shared/ui/IconTile.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { iconTileToneAt } from '@/shared/ui/icon-tile-tones'

import type { PermissionCode, PermissionState } from '../../application/app-permissions'
import { openSystemSettings } from '../../application/app-permissions'

// 权限管理 (board SetPerm, 390 wide): what each permission is used for and what happens without it.
// The state comes from the system (read again when the page shows); 去系统设置 opens the system page.
const props = defineProps<{ states: Record<PermissionCode, PermissionState> }>()
const emit = defineEmits<{ back: []; notifications: []; privacy: [] }>()

const CHEVRON = 'M9 5l7 7-7 7'
const hint = ref<PermissionCode | null>(null)

const DEFINITIONS: Array<{ id: PermissionCode; t: string; use: string; off: string; d: string; whenOn: string }> = [
  { id: 'location', t: '定位', use: '确定所在城市、按距离排序、到馆导航', off: '要手动选城市，不能按距离排序',
    d: 'M12 21s-7-6.1-7-11a7 7 0 1 1 14 0c0 4.9-7 11-7 11zM12 12.5a2.5 2.5 0 1 0 0-5a2.5 2.5 0 1 0 0 5z', whenOn: '已开启 · 使用 App 时' },
  { id: 'camera', t: '相机', use: '扫一扫、拍照上传现场图片', off: '不能扫码和拍照，其他功能不受影响',
    d: 'M4 8h4l2-3h4l2 3h4v11H4zM12 16.5a3.5 3.5 0 1 0 0-7a3.5 3.5 0 1 0 0 7z', whenOn: '已开启' },
  { id: 'notify', t: '通知', use: '订单、服务进度和活动提醒的推送', off: '收不到推送，“消息”里的站内消息不受影响',
    d: 'M6 16v-5a6 6 0 1 1 12 0v5l2 2H4zM10 20a2 2 0 0 0 4 0', whenOn: '已开启' },
]

const TAGS: Record<PermissionState, string> = { on: '', off: '未开启', ask: '未开启 · 用到时询问', unknown: '以手机系统设置为准' }

const permissions = computed(() => DEFINITIONS.map((item) => {
  const state = props.states[item.id]
  const on = state === 'on'
  return {
    ...item,
    tag: on ? item.whenOn : TAGS[state],
    iconBg: on ? '#fff0e3' : '#eef2f8',
    iconFg: on ? '#ff6a00' : '#738096',
    tagBg: on ? '#e2f6ed' : '#eef2f8',
    tagFg: on ? '#11804a' : '#536178',
    btn: on || state === 'unknown' ? '去系统设置' : '去开启',
    btnBd: on || state === 'unknown' ? '#c9d3e1' : '#ff6a00',
    btnFg: on || state === 'unknown' ? '#202c42' : '#ba470b',
  }
}))

function open(id: PermissionCode): void {
  if (openSystemSettings()) {
    hint.value = id
    return
  }
  uni.showToast({ title: '请在手机或浏览器的设置里修改这项权限', icon: 'none' })
}
</script>

<template>
  <view class="perm" data-testid="permissions-page">
    <AppPageHeader title="权限管理" test-id="permissions" @back="emit('back')" />
    <view class="perm__body">
      <text class="perm__intro">权限由手机系统管理，改完回到 App 自动更新</text>
      <view v-for="(item, index) in permissions" :key="item.id" class="perm__card" :data-testid="`permission-${item.id}`">
        <view class="perm__head">
          <IconTile :tone="iconTileToneAt(index)" :d="item.d" :size="44" :icon-size="22" />
          <view class="perm__title-block">
            <text class="perm__title">{{ item.t }}</text>
            <text class="perm__tag" :style="{ background: item.tagBg, color: item.tagFg }">{{ item.tag }}</text>
          </view>
          <button class="perm__open" :style="{ borderColor: item.btnBd, color: item.btnFg }" :aria-label="`${item.t}：去系统设置`" @click="open(item.id)">{{ item.btn }}</button>
        </view>
        <view class="perm__grid">
          <text class="perm__key">用在哪里</text><text class="perm__value">{{ item.use }}</text>
          <text class="perm__key">关闭后</text><text class="perm__value">{{ item.off }}</text>
        </view>
        <text v-if="hint === item.id" class="perm__hint" role="status">已打开系统设置，回到应用后这里的状态会刷新</text>
      </view>
      <view class="perm__links">
        <button class="perm__link" @click="emit('notifications')">
          <text class="perm__link-text">通知内容设置</text>
          <LineIcon :d="CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
        <button class="perm__link" data-testid="permissions-privacy" @click="emit('privacy')">
          <text class="perm__link-text">隐私政策</text>
          <LineIcon :d="CHEVRON" :size="14" color="#91a0b4" :stroke-width="2" />
        </button>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board SetPerm (390 wide). */
button { margin: 0; padding: 0; border: 0; background: transparent; font-family: inherit; line-height: normal; text-align: left; }
button::after { border: 0; }
.perm { display: flex; min-height: 100vh; flex-direction: column; color: #202c42; background: #f5f7fc; }
.perm__body { display: flex; padding: 12px 16px calc(24px + env(safe-area-inset-bottom)); flex-direction: column; gap: 10px; }
.perm__intro { padding: 0 4px; color: #627187; font-size: 13px; line-height: 19px; }
.perm__card { display: flex; padding: 14px; flex-direction: column; gap: 10px; border-radius: 14px; background: #fff; }
.perm__head { display: flex; align-items: center; gap: 12px; }
.perm__icon { display: flex; width: 44px; height: 44px; flex: none; align-items: center; justify-content: center; border-radius: 12px; }
.perm__title-block { display: flex; min-width: 0; flex: 1; flex-direction: column; align-items: flex-start; gap: 3px; }
.perm__title { font-size: 16px; font-weight: 700; line-height: 22px; white-space: nowrap; }
.perm__tag { padding: 1px 6px; border-radius: 6px; font-size: 12px; line-height: 16px; white-space: nowrap; }
.perm__open { flex: none; height: 36px; padding: 0 14px; border: 1px solid #c9d3e1; border-radius: 999px; background: #fff; font-size: 13px; line-height: 34px; white-space: nowrap; box-sizing: border-box; }
.perm__grid { display: grid; padding-top: 10px; grid-template-columns: 56px minmax(0, 1fr); gap: 6px 10px; border-top: 1px solid #f0f1f3; font-size: 13px; line-height: 19px; }
.perm__key { color: #738096; white-space: nowrap; }
.perm__value { color: #536178; }
.perm__hint { align-self: flex-start; padding: 4px 8px; border-radius: 6px; color: #ba470b; background: #fff0e3; font-size: 12px; line-height: 17px; }
.perm__links { display: flex; flex-direction: column; border-radius: 14px; background: #fff; }
.perm__link { display: flex; min-height: 50px; padding: 0 14px; align-items: center; gap: 12px; border-bottom: 1px solid #f0f1f3; color: #202c42; }
.perm__link:last-child { border-bottom-color: transparent; }
.perm__link-text { min-width: 0; flex: 1; font-size: 15px; line-height: 21px; white-space: nowrap; }
</style>
