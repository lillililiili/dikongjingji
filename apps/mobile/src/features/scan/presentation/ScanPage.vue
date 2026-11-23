<script setup lang="ts">
import { computed, ref } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'

import { parseScanText } from '../application/scan-target'

// Board Scan: the viewfinder and its two lines on dark; in the App the system scanner opens at
// once (uni.scanCode, no extra SDK) and 重新扫描 opens it again; in a browser, which has no camera
// scanner, the code or link is typed or pasted. A platform link opens its page; the three other
// answers are a sheet: 未识别到平台二维码, 这是核销码 (for the staff at the door) and 需要相机权限.
const props = defineProps<{
  // Whether the system scanner is there (the App); a browser types the code instead.
  canScan: boolean
  origins: readonly string[]
}>()

const emit = defineEmits<{
  back: []
  open: [url: string]
  search: []
  home: []
}>()

type Sheet = 'UNKNOWN' | 'CHECK_IN_CODE' | 'DENIED' | null

const UNKNOWN_ICON = 'M4 8V4h4M16 4h4v4M20 16v4h-4M8 20H4v-4M9 9l6 6M15 9l-6 6'
const CODE_ICON = 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h2v2h-2zM18 14h2M14 18h2v2M18 18h2v2'
const CAMERA_ICON = 'M4 8h3l2-3h6l2 3h3v11H4zM12 16a3 3 0 1 0 0-6a3 3 0 1 0 0 6z'

const SHEETS = {
  UNKNOWN: { title: '未识别到平台二维码', text: '这不是平台内的码，为了安全不会打开外部链接。可以换一个码，或者直接搜索。', icon: UNKNOWN_ICON, background: '#eef2f8', color: '#738096' },
  CHECK_IN_CODE: { title: '这是核销码', text: '核销码由现场工作人员扫描，用于签到和到馆核销。', icon: CODE_ICON, background: '#fff0e3', color: '#ff6a00' },
  DENIED: { title: '需要相机权限', text: '相机只用于扫描二维码，不保存画面。', icon: CAMERA_ICON, background: '#fff0e3', color: '#ff6a00' },
} as const

// 普通用户扫到别人的码时不显示码内信息 (board Scan). The staff's work mode comes with the
// check-in workbench (plan 08 S3-3), so there is no button to it yet.
const ROUTES = [
  { who: '普通用户', go: '停在这里，不显示码内信息' },
  { who: '已授权核销员', go: '由现场核销工作台核销' },
]

const sheet = ref<Sheet>(null)
const typed = ref('')
const scanning = ref(false)
const current = computed(() => (sheet.value ? SHEETS[sheet.value] : null))

function handle(text: string): void {
  const target = parseScanText(text, props.origins)
  if (target.kind === 'PAGE') {
    sheet.value = null
    emit('open', target.url)
    return
  }
  sheet.value = target.kind
}

function isDenied(message: string): boolean {
  return /auth|permission|denied|权限/i.test(message)
}

function scan(): void {
  if (!props.canScan || scanning.value) return
  scanning.value = true
  sheet.value = null
  uni.scanCode({
    scanType: ['qrCode'],
    success: (result) => handle(String(result.result ?? '')),
    fail: (failure) => {
      const message = String((failure as { errMsg?: string }).errMsg ?? '')
      if (/cancel/i.test(message)) return
      sheet.value = isDenied(message) ? 'DENIED' : 'UNKNOWN'
    },
    complete: () => {
      scanning.value = false
    },
  })
}

function submitTyped(): void {
  const text = typed.value.trim()
  if (!text) {
    uni.showToast({ title: '请输入码内容或链接', icon: 'none' })
    return
  }
  handle(text)
}

// The H5 input event comes at most once per 100ms, so a link pasted and 识别 tapped right after would
// read the previous text. Tapping the button first blurs the input, and blur carries the current value.
function onInput(event: Event): void {
  typed.value = ((event as Event & { detail?: { value?: string } }).detail?.value ?? '').toString().slice(0, 600)
}

function onConfirm(event: Event): void {
  onInput(event)
  submitTyped()
}

function again(): void {
  sheet.value = null
  if (props.canScan) scan()
}

function openSettings(): void {
  const open = (uni as unknown as { openAppAuthorizeSetting?: (options: object) => void }).openAppAuthorizeSetting
  if (typeof open === 'function') {
    open({ fail: () => uni.showToast({ title: '请在系统设置里允许使用相机', icon: 'none' }) })
    return
  }
  uni.showToast({ title: '请在系统设置里允许使用相机', icon: 'none' })
}

defineExpose({ scan })
</script>

<template>
  <view class="scan" data-testid="scan-page">
    <view class="scan__head">
      <button class="scan__back" data-testid="scan-back" aria-label="返回" @click="emit('back')">
        <LineIcon name="back" :size="22" color="#ffffff" :stroke-width="2" />
      </button>
      <text class="scan__title" role="heading">扫一扫</text>
    </view>

    <view class="scan__view">
      <view class="scan__frame">
        <view class="scan__corner scan__corner--tl" />
        <view class="scan__corner scan__corner--tr" />
        <view class="scan__corner scan__corner--bl" />
        <view class="scan__corner scan__corner--br" />
        <view class="scan__line" />
        <text class="scan__frame-text">{{ canScan ? (scanning ? '正在打开相机…' : '系统扫码') : '浏览器里无法调用相机' }}</text>
      </view>
      <text class="scan__lead">将二维码放入框内，自动识别</text>
      <text class="scan__note">可识别平台内的商品、活动和场馆码</text>
      <button v-if="canScan" class="scan__again" data-testid="scan-start" @click="scan">重新扫描</button>
    </view>

    <view v-if="!canScan" class="scan__manual" data-testid="scan-manual">
      <text class="scan__manual-title">输入码内容或链接</text>
      <text class="scan__manual-text">在浏览器里打开时，可以把二维码下面的链接或码粘贴到这里</text>
      <view class="scan__manual-row">
        <input
          class="scan__manual-input"
          data-testid="scan-input"
          type="text"
          confirm-type="go"
          :value="typed"
          placeholder="粘贴平台链接或码"
          placeholder-class="scan__placeholder"
          aria-label="码内容或链接"
          @input="onInput"
          @blur="onInput"
          @confirm="onConfirm"
        />
        <button class="scan__manual-go" data-testid="scan-submit" @click="submitTyped">识别</button>
      </view>
      <text class="scan__manual-hint">码对应的对象已下架或已过期时，打开后页面会说明</text>
    </view>

    <template v-if="current">
      <button class="scan__mask" aria-label="关闭" @click="sheet = null" />
      <view class="scan__sheet" role="dialog" :aria-label="current.title" :data-testid="`scan-sheet-${sheet?.toLowerCase()}`">
        <view class="scan__sheet-top">
          <view class="scan__sheet-icon" :style="{ background: current.background }">
            <LineIcon :d="current.icon" :size="30" :color="current.color" :stroke-width="1.7" />
          </view>
          <text class="scan__sheet-title">{{ current.title }}</text>
          <text class="scan__sheet-text">{{ current.text }}</text>
        </view>
        <view v-if="sheet === 'CHECK_IN_CODE'" class="scan__routes" role="list" aria-label="核销码去向">
          <view v-for="route in ROUTES" :key="route.who" class="scan__route" role="listitem">
            <text class="scan__route-who">{{ route.who }}</text>
            <text class="scan__route-go">{{ route.go }}</text>
          </view>
        </view>
        <view class="scan__sheet-actions">
          <template v-if="sheet === 'UNKNOWN'">
            <button class="scan__button" data-testid="scan-go-search" @click="emit('search')">去搜索</button>
            <button class="scan__button scan__button--primary" data-testid="scan-again" @click="again">{{ canScan ? '重新扫描' : '重新输入' }}</button>
          </template>
          <button v-else-if="sheet === 'CHECK_IN_CODE'" class="scan__button scan__button--primary" data-testid="scan-ok" @click="sheet = null">知道了</button>
          <template v-else>
            <button class="scan__button" data-testid="scan-not-now" @click="emit('home')">暂不开启</button>
            <button class="scan__button scan__button--primary" data-testid="scan-settings" @click="openSettings">去设置</button>
          </template>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 board Scan (390 wide). */
.scan {
  position: relative;
  display: flex;
  min-height: 100vh;
  flex-direction: column;
  color: #fff;
  background: #16181b;
}

button {
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.scan__head {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: calc(var(--status-bar-height) + 8px) 8px 8px;
}

.scan__back {
  display: flex;
  width: 44px;
  height: 44px;
  align-items: center;
  justify-content: center;
}

.scan__title {
  font-size: 18px;
  font-weight: 700;
}

.scan__view {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding-top: 76px;
}

.scan__frame {
  position: relative;
  display: flex;
  width: 248px;
  height: 248px;
  margin-bottom: 16px;
  align-items: center;
  justify-content: center;
}

.scan__corner {
  position: absolute;
  width: 34px;
  height: 34px;
  box-sizing: border-box;
}

.scan__corner--tl {
  top: 0;
  left: 0;
  border-top: 3px solid #ff6a00;
  border-left: 3px solid #ff6a00;
  border-radius: 10px 0 0;
}

.scan__corner--tr {
  top: 0;
  right: 0;
  border-top: 3px solid #ff6a00;
  border-right: 3px solid #ff6a00;
  border-radius: 0 10px 0 0;
}

.scan__corner--bl {
  bottom: 0;
  left: 0;
  border-bottom: 3px solid #ff6a00;
  border-left: 3px solid #ff6a00;
  border-radius: 0 0 0 10px;
}

.scan__corner--br {
  right: 0;
  bottom: 0;
  border-right: 3px solid #ff6a00;
  border-bottom: 3px solid #ff6a00;
  border-radius: 0 0 10px;
}

.scan__line {
  position: absolute;
  top: 92px;
  right: 14px;
  left: 14px;
  height: 2px;
  border-radius: 1px;
  background: linear-gradient(90deg, rgba(255, 106, 0, 0), #ff6a00 50%, rgba(255, 106, 0, 0));
}

.scan__frame-text {
  color: rgba(255, 255, 255, 0.64);
  font-size: 13px;
  line-height: 18px;
}

.scan__lead {
  font-size: 15px;
  font-weight: 500;
  line-height: 21px;
}

.scan__note {
  color: rgba(255, 255, 255, 0.72);
  font-size: 13px;
  line-height: 18px;
}

.scan__again {
  display: flex;
  height: 40px;
  margin-top: 18px;
  align-items: center;
  padding: 0 22px;
  border: 1px solid rgba(255, 255, 255, 0.5);
  border-radius: 999px;
  color: #fff;
  font-size: 14px;
  box-sizing: border-box;
}

.scan__manual {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 18px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  color: #202c42;
  background: #fff;
}

.scan__manual-title {
  font-size: 16px;
  font-weight: 700;
  line-height: 22px;
}

.scan__manual-text,
.scan__manual-hint {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
}

.scan__manual-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.scan__manual-input {
  height: 44px;
  min-width: 0;
  flex: 1 1 auto;
  padding: 0 12px;
  border-radius: 12px;
  background: #eef2f8;
  font-size: 14px;
}

.scan__placeholder {
  color: #738096;
}

.scan__manual-go {
  display: flex;
  height: 44px;
  align-items: center;
  flex: none;
  padding: 0 18px;
  border-radius: 999px;
  color: #fff;
  background: #ff6a00;
  font-size: 15px;
  font-weight: 500;
}

.scan__mask {
  position: fixed;
  z-index: 10;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  background: rgba(0, 0, 0, 0.45);
}

.scan__sheet {
  position: fixed;
  z-index: 11;
  right: 0;
  bottom: 0;
  left: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 24px 16px calc(24px + env(safe-area-inset-bottom));
  border-radius: 20px 20px 0 0;
  color: #202c42;
  background: #fff;
}

.scan__sheet-top {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  text-align: center;
}

.scan__sheet-icon {
  display: flex;
  width: 64px;
  height: 64px;
  margin-bottom: 6px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
}

.scan__sheet-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
}

.scan__sheet-text {
  color: #536178;
  font-size: 14px;
  line-height: 21px;
}

.scan__routes {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border-radius: 10px;
  background: #f5f7fc;
}

.scan__route {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.scan__route-who {
  flex: none;
  font-size: 13px;
  font-weight: 500;
  line-height: 19px;
}

.scan__route-go {
  min-width: 0;
  color: #536178;
  font-size: 13px;
  line-height: 19px;
  text-align: right;
}

.scan__sheet-actions {
  display: flex;
  gap: 10px;
  margin-top: 2px;
}

.scan__button {
  display: flex;
  height: 46px;
  flex: 1 1 0;
  align-items: center;
  justify-content: center;
  border: 1px solid #c9d3e1;
  border-radius: 999px;
  color: #202c42;
  background: #fff;
  font-size: 15px;
  box-sizing: border-box;
}

.scan__button--primary {
  border-color: #ff6a00;
  color: #fff;
  background: #ff6a00;
  font-weight: 500;
}
</style>
