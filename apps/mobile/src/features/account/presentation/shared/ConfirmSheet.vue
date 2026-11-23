<script setup lang="ts">
// The confirmation sheet of the boards (SetDevices, OrgMembers, OrgInvite): title, text, a ghost
// button that keeps things as they are and an orange one that goes ahead.
defineProps<{ title: string; text: string; cancelText: string; confirmText: string; busy?: boolean; testId: string }>()
const emit = defineEmits<{ cancel: []; confirm: [] }>()
</script>

<template>
  <view class="confirm-mask" @click="emit('cancel')" />
  <view class="confirm" role="dialog" :aria-label="title" :data-testid="testId">
    <view class="confirm__copy">
      <text class="confirm__title">{{ title }}</text>
      <text class="confirm__text">{{ text }}</text>
    </view>
    <view class="confirm__actions">
      <button class="confirm__ghost" :data-testid="`${testId}-cancel`" @click="emit('cancel')">{{ cancelText }}</button>
      <button class="confirm__primary" :disabled="busy" :data-testid="`${testId}-confirm`" @click="emit('confirm')">{{ confirmText }}</button>
    </view>
  </view>
</template>

<style scoped>
button { margin: 0; padding: 0; border: 0; background: transparent; font-family: inherit; line-height: normal; }
button::after { border: 0; }
.confirm-mask { position: fixed; inset: 0; z-index: 900; background: rgba(0, 0, 0, 0.45); }
.confirm { position: fixed; right: 0; bottom: 0; left: 0; z-index: 901; display: flex; padding: 20px 16px calc(24px + env(safe-area-inset-bottom)); flex-direction: column; gap: 14px; border-radius: 20px 20px 0 0; color: #202c42; background: #fff; }
.confirm__copy { display: flex; flex-direction: column; gap: 4px; }
.confirm__title { font-size: 18px; font-weight: 700; line-height: 25px; }
.confirm__text { color: #536178; font-size: 14px; line-height: 21px; }
.confirm__actions { display: flex; gap: 10px; }
.confirm__ghost, .confirm__primary { height: 44px; flex: 1 1 0; border-radius: 999px; font-size: 15px; line-height: 44px; text-align: center; }
.confirm__ghost { border: 1px solid #c9d3e1; color: #202c42; background: #fff; box-sizing: border-box; }
.confirm__primary { color: #fff; background: #ff6a00; font-weight: 500; }
.confirm__primary[disabled] { color: #fff; background: #c9d3e1; }
</style>
