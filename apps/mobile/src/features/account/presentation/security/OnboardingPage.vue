<script setup lang="ts">
import { ref } from 'vue'

import type { AgreementDoc } from '@/features/settings/domain/agreement'
import { APP_MARK } from '@/features/settings/presentation/settings-icons'
import LineIcon from '@/shared/ui/LineIcon.vue'

// 首次启动 (board Onboarding, 390 wide): the privacy consent comes first (what is used and when),
// then why location helps. Nothing is read before the consent; the location prompt is the system's.
const props = defineProps<{ fromSettings: boolean }>()
const emit = defineEmits<{ agreed: []; agreement: [doc: AgreementDoc]; locate: []; manualCity: []; quit: [] }>()

const frame = ref<'privacy' | 'decline' | 'location'>(props.fromSettings ? 'privacy' : 'privacy')
const APP_NAME = '低空产业平台'
const CHECK = 'M5 12l5 5 9-10'
const PIN = 'M12 2a7.5 7.5 0 0 0-7.5 7.5c0 5.6 7.5 12.5 7.5 12.5s7.5-6.9 7.5-12.5A7.5 7.5 0 0 0 12 2zm0 10.2a2.7 2.7 0 1 1 0-5.4a2.7 2.7 0 0 1 0 5.4z'

const uses = [
  { t: '位置', s: '确定当前城市，展示本城的服务和活动', bg: '#fff0e3', fg: '#ff6a00', d: 'M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11zM12 12.5a2.5 2.5 0 1 0 0-5a2.5 2.5 0 1 0 0 5z' },
  { t: '手机号', s: '登录、下单，接收订单与服务通知', bg: '#e6f0ff', fg: '#2f80ed', d: 'M8 3h8a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H8a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1zM11 18h2' },
  { t: '相机与文件', s: '扫码、上传需求附件和反馈截图', bg: '#e2f6ed', fg: '#159a70', d: 'M4 8h3l2-3h6l2 3h3v11H4zM12 16a3 3 0 1 0 0-6a3 3 0 1 0 0 6z' },
]
const gains = [
  { t: '自动切到你所在的城市', s: '首页频道和内容跟随当前城市' },
  { t: '看离你多近', s: '场馆和服务可以按距离排序' },
  { t: '只在使用 App 时获取', s: '随时可以在 设置 → 权限管理 关闭' },
]

function agree(): void {
  emit('agreed')
  frame.value = 'location'
}
</script>

<template>
  <view class="onboarding" data-testid="onboarding-page">
    <template v-if="frame !== 'location'">
      <view class="onboarding__brand">
        <view class="onboarding__mark"><LineIcon :d="APP_MARK" :size="40" color="#ffffff" :stroke-width="1.7" /></view>
        <text class="onboarding__name">{{ APP_NAME }}</text>
        <text class="onboarding__tagline">设备、活动、体验和专业服务</text>
      </view>
      <view class="onboarding__mask" />
      <view v-if="frame === 'privacy'" class="onboarding__dialog" role="dialog" aria-label="隐私同意" data-testid="onboarding-privacy">
        <view class="onboarding__dialog-head">
          <text class="onboarding__dialog-title">欢迎使用{{ APP_NAME }}</text>
          <text class="onboarding__dialog-text">使用前请了解我们如何处理你的信息。以下信息只在你用到对应功能时才会获取：</text>
        </view>
        <view class="onboarding__uses" role="list" aria-label="信息用途">
          <view v-for="use in uses" :key="use.t" class="onboarding__use" role="listitem">
            <view class="onboarding__use-icon" :style="{ background: use.bg }"><LineIcon :d="use.d" :size="20" :color="use.fg" :stroke-width="1.8" /></view>
            <view class="onboarding__use-copy">
              <text class="onboarding__use-title">{{ use.t }}</text>
              <text class="onboarding__use-text">{{ use.s }}</text>
            </view>
          </view>
        </view>
        <text class="onboarding__terms">点“同意并继续”，表示你已阅读并同意<text class="onboarding__link" @click="emit('agreement', 'USER_AGREEMENT')">《用户协议》</text><text class="onboarding__link" @click="emit('agreement', 'PRIVACY_POLICY')">《隐私政策》</text><text class="onboarding__link" @click="emit('agreement', 'CHILD_PRIVACY_RULES')">《儿童个人信息保护规则》</text>。</text>
        <view class="onboarding__buttons">
          <button class="onboarding__primary" data-testid="onboarding-agree" @click="agree">同意并继续</button>
          <button class="onboarding__plain" data-testid="onboarding-decline" @click="frame = 'decline'">不同意</button>
        </view>
      </view>
      <view v-else class="onboarding__dialog onboarding__dialog--decline" role="dialog" aria-label="不同意确认" data-testid="onboarding-decline-dialog">
        <view class="onboarding__dialog-head">
          <text class="onboarding__dialog-title">需要你的同意才能继续</text>
          <text class="onboarding__dialog-text">不同意《用户协议》和《隐私政策》，App 无法提供服务。你可以再看看，也可以退出 App，下次打开时会再次询问。</text>
        </view>
        <view class="onboarding__buttons">
          <button class="onboarding__primary" data-testid="onboarding-reread" @click="frame = 'privacy'">再看看</button>
          <button class="onboarding__plain" data-testid="onboarding-quit" @click="emit('quit')">退出 App</button>
        </view>
      </view>
    </template>

    <view v-else class="onboarding__location" data-testid="onboarding-location">
      <view class="onboarding__halo">
        <view class="onboarding__halo-inner"><LineIcon :d="PIN" :size="56" color="#ff6a00" fill="#ff6a00" :stroke-width="0" /></view>
      </view>
      <text class="onboarding__location-title">开启定位，看本城可用的服务</text>
      <text class="onboarding__location-text">活动、体验和专业服务都按城市开通</text>
      <view class="onboarding__gains">
        <view v-for="gain in gains" :key="gain.t" class="onboarding__gain">
          <view class="onboarding__gain-mark"><LineIcon :d="CHECK" :size="12" color="#159a70" :stroke-width="3.2" /></view>
          <view class="onboarding__use-copy">
            <text class="onboarding__gain-title">{{ gain.t }}</text>
            <text class="onboarding__use-text">{{ gain.s }}</text>
          </view>
        </view>
      </view>
      <button class="onboarding__primary onboarding__primary--wide" data-testid="onboarding-locate" @click="emit('locate')">开启定位</button>
      <button class="onboarding__plain" data-testid="onboarding-manual-city" @click="emit('manualCity')">暂不开启，手动选择城市</button>
    </view>
  </view>
</template>

<style scoped>
/* Sizes and colours follow the 2026-11-14 prototype board Onboarding (390 wide). */
button { margin: 0; padding: 0; border: 0; background: transparent; font-family: inherit; line-height: normal; }
button::after { border: 0; }
.onboarding { position: relative; display: flex; min-height: 100vh; flex-direction: column; overflow: hidden; color: #202c42; background: linear-gradient(180deg, #fff0e3 0%, #fffaf5 48%, #fff 100%); }
.onboarding__brand { display: flex; padding-top: 96px; flex-direction: column; align-items: center; gap: 4px; }
.onboarding__mark { display: flex; width: 76px; height: 76px; margin-bottom: 10px; align-items: center; justify-content: center; border-radius: 20px; background: linear-gradient(145deg, #ff8f3d 0%, #ff6a00 100%); box-shadow: 0 6px 16px rgba(255, 106, 0, 0.24); }
.onboarding__name { font-size: 22px; font-weight: 700; line-height: 30px; }
.onboarding__tagline { color: #627187; font-size: 14px; line-height: 20px; }
.onboarding__mask { position: fixed; inset: 0; z-index: 1; background: rgba(0, 0, 0, 0.45); }
.onboarding__dialog { position: fixed; top: 252px; right: 20px; left: 20px; z-index: 2; display: flex; padding: 22px 20px 12px; flex-direction: column; gap: 14px; border-radius: 20px; background: #fff; }
.onboarding__dialog--decline { top: 296px; }
.onboarding__dialog-head { display: flex; flex-direction: column; gap: 6px; }
.onboarding__dialog-title { font-size: 18px; font-weight: 700; line-height: 25px; text-align: center; }
.onboarding__dialog-text { color: #536178; font-size: 14px; line-height: 22px; }
.onboarding__uses { display: flex; flex-direction: column; gap: 12px; }
.onboarding__use { display: flex; align-items: center; gap: 10px; }
.onboarding__use-icon { display: flex; width: 36px; height: 36px; flex: none; align-items: center; justify-content: center; border-radius: 10px; }
.onboarding__use-copy { display: flex; min-width: 0; flex-direction: column; gap: 2px; }
.onboarding__use-title { font-size: 14px; font-weight: 500; line-height: 20px; }
.onboarding__use-text { color: #627187; font-size: 12px; line-height: 17px; }
.onboarding__terms { color: #536178; font-size: 13px; line-height: 20px; }
.onboarding__link { color: #ba470b; }
.onboarding__buttons { display: flex; flex-direction: column; gap: 2px; }
.onboarding__primary { height: 46px; border-radius: 999px; color: #fff; background: #ff6a00; font-size: 16px; font-weight: 500; line-height: 46px; text-align: center; white-space: nowrap; }
.onboarding__plain { min-height: 44px; color: #627187; font-size: 14px; line-height: 44px; text-align: center; white-space: nowrap; }
.onboarding__location { display: flex; padding: 92px 24px calc(36px + env(safe-area-inset-bottom)); flex: 1; flex-direction: column; }
.onboarding__halo { display: flex; width: 196px; height: 196px; align-self: center; align-items: center; justify-content: center; border-radius: 50%; background: rgba(255, 106, 0, 0.08); }
.onboarding__halo-inner { display: flex; width: 132px; height: 132px; align-items: center; justify-content: center; border-radius: 50%; background: rgba(255, 106, 0, 0.14); }
.onboarding__location-title { margin-top: 32px; font-size: 22px; font-weight: 700; line-height: 30px; text-align: center; }
.onboarding__location-text { margin-top: 6px; color: #627187; font-size: 14px; line-height: 22px; text-align: center; }
.onboarding__gains { display: flex; margin-top: 26px; padding: 16px 14px; flex-direction: column; gap: 16px; border-radius: 14px; background: #fff; box-shadow: 0 2px 10px rgba(0, 0, 0, 0.04); }
.onboarding__gain { display: flex; align-items: flex-start; gap: 10px; }
.onboarding__gain-mark { display: flex; width: 20px; height: 20px; margin-top: 1px; flex: none; align-items: center; justify-content: center; border-radius: 50%; background: #e2f6ed; }
.onboarding__gain-title { font-size: 15px; font-weight: 500; line-height: 21px; }
.onboarding__primary--wide { height: 48px; margin-top: auto; line-height: 48px; }
</style>
