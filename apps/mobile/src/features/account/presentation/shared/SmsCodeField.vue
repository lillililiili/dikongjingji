<script setup lang="ts">
import { onUnmounted, ref } from 'vue'

import { codeButtonText, digitsOnly, inputValue } from '../../domain/identity-account'

// The code input of the boards (Login, SetPhone, SetData): a soft field with 获取验证码 on the
// right, which turns grey and counts down for as long as the server says (60 s) after a send.
const props = defineProps<{
  modelValue: string
  label: string
  testId: string
  // Sends the code; resolves to the seconds to wait, rejects (and shows nothing) when it failed.
  send: () => Promise<number>
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const secondsLeft = ref(0)
const sending = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

function onInput(event: Event): void {
  emit('update:modelValue', digitsOnly(inputValue(event), 6))
}

async function sendCode(): Promise<void> {
  if (sending.value || secondsLeft.value > 0) return
  sending.value = true
  try {
    const seconds = await props.send()
    secondsLeft.value = seconds
    if (timer) clearInterval(timer)
    timer = setInterval(() => {
      secondsLeft.value = Math.max(0, secondsLeft.value - 1)
      if (secondsLeft.value === 0 && timer) {
        clearInterval(timer)
        timer = null
      }
    }, 1000)
  } catch {
    // The page shows why.
  } finally {
    sending.value = false
  }
}

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <view class="sms-field">
    <input
      class="sms-field__input"
      :value="modelValue"
      type="number"
      maxlength="6"
      placeholder="请输入验证码"
      placeholder-class="sms-field__placeholder"
      :aria-label="label"
      :data-testid="`${testId}-code`"
      @input="onInput"
    />
    <button
      :class="['sms-field__send', { 'sms-field__send--wait': secondsLeft > 0 }]"
      :disabled="secondsLeft > 0 || sending"
      :data-testid="`${testId}-send`"
      @click="sendCode"
    >
      {{ codeButtonText(secondsLeft) }}
    </button>
  </view>
</template>

<style scoped>
.sms-field { display: flex; height: 50px; padding: 0 4px 0 14px; align-items: center; gap: 8px; border-radius: 12px; background: #f5f7fc; box-sizing: border-box; }
.sms-field__input { min-width: 0; height: 50px; flex: 1; color: #202c42; font-size: 15px; }
.sms-field__placeholder { color: #91a0b4; }
.sms-field__send { flex: none; min-height: 44px; margin: 0; padding: 0 10px; border: 0; color: #ba470b; background: transparent; font-size: 14px; line-height: 44px; white-space: nowrap; }
.sms-field__send::after { border: 0; }
.sms-field__send--wait, .sms-field__send[disabled] { color: #91a0b4; background: transparent; }
</style>
