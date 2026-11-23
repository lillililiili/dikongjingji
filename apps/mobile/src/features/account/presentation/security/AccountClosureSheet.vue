<script setup lang="ts">
import { computed } from 'vue'

import LineIcon from '@/shared/ui/LineIcon.vue'
import { formatBeijingTime, toBeijingParts } from '@/shared/time/beijing-time'

import type { AccountClosure } from '../../domain/identity-account'

// 注销账户 (board Settings, the sheet): the conditions, then 申请注销; while the 15-day cooling-off
// runs, the progress and 撤回申请. Sizes and colours follow the board (390 wide).
const props = defineProps<{ closure: AccountClosure; busy: boolean }>()
const emit = defineEmits<{ close: []; apply: []; withdraw: [] }>()

const CHECK = 'M5 12l5 5 9-10'
const CROSS = 'M6 6l12 12M18 6L6 18'
const OK_PATH = 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM8 12l3 3 5-6'
const WARN_PATH = 'M12 4l9 16H3zM12 10v4M12 17h.01'
const CLOCK = 'M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM12 7v5l3 2'

const pending = computed(() => props.closure.status === 'PENDING')
const allMet = computed(() => props.closure.conditions.every((condition) => condition.met))

function day(iso: string | null): string {
  if (!iso) return ''
  const parts = toBeijingParts(new Date(iso))
  return `${parts.month}/${parts.day}`
}

const steps = computed(() => [
  { t: '已提交申请', when: day(props.closure.requestedAt), done: true, now: false },
  { t: '冷静期，可随时撤回', when: props.closure.dueAt ? `${day(props.closure.dueAt)} ${formatBeijingTime(props.closure.dueAt)} 结束` : '', done: false, now: true },
  { t: '完成注销，账户信息匿名化', when: '冷静期结束后', done: false, now: false },
])
</script>

<template>
  <view class="closure-mask" data-testid="closure-mask" @click="emit('close')" />
  <view class="closure" role="dialog" aria-label="注销账户" data-testid="closure-sheet">
    <template v-if="!pending">
      <view class="closure__head">
        <text class="closure__title">注销账户</text>
        <text class="closure__sub">{{ allMet ? '注销条件已全部满足' : '注销前需要满足以下条件' }}</text>
      </view>
      <view class="closure__list" role="list" aria-label="注销条件">
        <view
          v-for="condition in closure.conditions"
          :key="condition.code"
          class="closure__item"
          role="listitem"
          :data-testid="`closure-condition-${condition.code.toLowerCase()}`"
        >
          <view :class="['closure__mark', condition.met ? 'closure__mark--ok' : 'closure__mark--bad']">
            <LineIcon :d="condition.met ? CHECK : CROSS" :size="condition.met ? 12 : 10"
                      :color="condition.met ? '#159a70' : '#d64f67'" :stroke-width="condition.met ? 3.2 : 3.6" />
          </view>
          <view class="closure__item-copy">
            <text class="closure__item-title">{{ condition.title }}</text>
            <text :class="['closure__item-detail', { 'closure__item-detail--ok': condition.met }]">{{ condition.detail }}</text>
          </view>
        </view>
      </view>
      <view :class="['closure__note', allMet ? 'closure__note--ok' : 'closure__note--warn']">
        <LineIcon :d="allMet ? OK_PATH : WARN_PATH" :size="16" :color="allMet ? '#159a70' : '#ff6a00'" :stroke-width="2" />
        <text>{{ allMet ? '可以申请注销；申请后有 15 天冷静期，期间不能下单，可随时撤回' : '暂不能注销，处理完以上事项后再申请' }}</text>
      </view>
      <text class="closure__fine">申请提交后有 15 天冷静期，期间可撤回；到期后账户信息按隐私政策匿名化，订单等交易记录保留但不再对应到你，这个账号不能再登录</text>
      <view class="closure__actions">
        <button class="closure__ghost" data-testid="closure-dismiss" @click="emit('close')">{{ allMet ? '再想想' : '知道了' }}</button>
        <button
          :class="['closure__primary', { 'closure__primary--off': !allMet }]"
          :disabled="!allMet || busy"
          data-testid="closure-apply"
          @click="emit('apply')"
        >申请注销</button>
      </view>
    </template>
    <template v-else>
      <view class="closure__pending-head">
        <view class="closure__pending-icon"><LineIcon :d="CLOCK" :size="22" color="#ff6a00" :stroke-width="2.2" /></view>
        <view class="closure__head">
          <text class="closure__title">注销申请处理中</text>
          <text class="closure__sub">{{ day(closure.requestedAt) }} 提交 · 冷静期 15 天</text>
        </view>
      </view>
      <view class="closure__list" role="list" aria-label="注销进度">
        <view v-for="step in steps" :key="step.t" class="closure__step" role="listitem">
          <view :class="['closure__dot', { 'closure__dot--done': step.done, 'closure__dot--now': step.now }]">
            <LineIcon v-if="step.done" :d="CHECK" :size="11" color="#ffffff" :stroke-width="4" />
          </view>
          <text :class="['closure__step-title', { 'closure__step-title--off': !step.done && !step.now }]">{{ step.t }}</text>
          <text class="closure__step-when">{{ step.when }}</text>
        </view>
      </view>
      <text class="closure__fine">冷静期内不能下单和付款；撤回申请后立即恢复</text>
      <text class="closure__fine">完成后账户信息按隐私政策匿名化，不能恢复</text>
      <view class="closure__actions">
        <button class="closure__ghost" data-testid="closure-withdraw" :disabled="busy" @click="emit('withdraw')">撤回申请</button>
        <button class="closure__primary" data-testid="closure-ok" @click="emit('close')">知道了</button>
      </view>
    </template>
  </view>
</template>

<style scoped>
button { margin: 0; padding: 0; border: 0; background: transparent; font-family: inherit; line-height: normal; }
button::after { border: 0; }
.closure-mask { position: fixed; inset: 0; z-index: 900; background: rgba(0, 0, 0, 0.45); }
.closure { position: fixed; right: 0; bottom: 0; left: 0; z-index: 901; display: flex; padding: 20px 16px calc(24px + env(safe-area-inset-bottom)); flex-direction: column; gap: 12px; border-radius: 20px 20px 0 0; color: #202c42; background: #fff; }
.closure__head { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 4px; }
.closure__title { font-size: 18px; font-weight: 700; line-height: 25px; }
.closure__sub { color: #536178; font-size: 13px; line-height: 18px; }
.closure__list { display: flex; padding: 12px; flex-direction: column; gap: 10px; border-radius: 10px; background: #f5f7fc; }
.closure__item { display: flex; align-items: flex-start; gap: 10px; }
.closure__mark { display: flex; width: 20px; height: 20px; flex: none; align-items: center; justify-content: center; border-radius: 50%; }
.closure__mark--ok { background: #e2f6ed; }
.closure__mark--bad { background: #fdecec; }
.closure__item-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 2px; }
.closure__item-title { font-size: 14px; line-height: 20px; }
.closure__item-detail { color: #536178; font-size: 12px; line-height: 17px; }
.closure__item-detail--ok { color: #11804a; }
.closure__note { display: flex; padding: 10px 12px; align-items: flex-start; gap: 6px; border-radius: 10px; font-size: 13px; line-height: 18px; }
.closure__note--ok { color: #11804a; background: #e2f6ed; }
.closure__note--warn { color: #ba470b; background: #fff0e3; }
.closure__fine { color: #738096; font-size: 12px; line-height: 17px; }
.closure__actions { display: flex; gap: 10px; }
.closure__ghost, .closure__primary { height: 44px; flex: 1 1 0; border-radius: 999px; font-size: 15px; line-height: 44px; text-align: center; }
.closure__ghost { border: 1px solid #c9d3e1; color: #202c42; background: #fff; box-sizing: border-box; }
.closure__primary { color: #fff; background: #ff6a00; font-weight: 500; }
.closure__primary--off, .closure__primary[disabled] { color: #fff; background: #c9d3e1; }
.closure__pending-head { display: flex; align-items: center; gap: 10px; }
.closure__pending-icon { display: flex; width: 40px; height: 40px; flex: none; align-items: center; justify-content: center; border-radius: 50%; background: #fff0e3; }
.closure__step { display: flex; align-items: center; gap: 10px; }
.closure__dot { display: flex; width: 20px; height: 20px; flex: none; align-items: center; justify-content: center; border: 2px solid #c9d3e1; border-radius: 50%; background: #fff; box-sizing: border-box; }
.closure__dot--done { border-color: #ff6a00; background: #ff6a00; }
.closure__dot--now { border-color: #ff6a00; }
.closure__step-title { min-width: 0; flex: 1; font-size: 14px; line-height: 20px; }
.closure__step-title--off { color: #738096; }
.closure__step-when { flex: none; color: #738096; font-size: 12px; line-height: 17px; }
</style>
