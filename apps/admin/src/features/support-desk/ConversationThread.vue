<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref, shallowRef, watch } from 'vue'

import { newIdempotencyKey } from '@/core/http/api-client'
import { errorMessage } from '@/core/http/api-error'
import { showError } from '@/core/ui/feedback'
import { formatDateTime } from '@/core/ui/format'

import {
  CARD_TYPE_LABELS,
  COUNTERPART_LABELS,
  mergeMessages,
  REPLY_MAX,
  replyError,
  type DeskConversation,
  type SupportDeskApi,
  type SupportMessage,
} from './support-desk-api'

/**
 * 一个会话：进来读最新一页并标为已读，“更早的消息”按 before 往前翻；回复 ≤500 字，带幂等键
 * （同一次填写重试用同一个键，发出去之后换新的）。
 */
const props = defineProps<{
  api: SupportDeskApi
  conversationId: string
}>()

const conversation = shallowRef<DeskConversation | null>(null)
const messages = shallowRef<SupportMessage[]>([])
const nextBefore = ref<number | null>(null)
const loading = ref(false)
const loadingEarlier = ref(false)
const error = ref('')
const draft = ref('')
const problem = ref('')
const sending = ref(false)
let replyKey = newIdempotencyKey()

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    const page = await props.api.messages(props.conversationId)
    conversation.value = page.conversation
    messages.value = page.items
    nextBefore.value = page.hasMore ? page.nextBefore : null
    if (page.conversation.unreadCount > 0) {
      conversation.value = await props.api.read(props.conversationId)
    }
  } catch (failure) {
    error.value = errorMessage(failure)
  } finally {
    loading.value = false
  }
}

async function loadEarlier(): Promise<void> {
  if (nextBefore.value === null || loadingEarlier.value) return
  loadingEarlier.value = true
  try {
    const page = await props.api.messages(props.conversationId, nextBefore.value)
    messages.value = mergeMessages(messages.value, page.items)
    nextBefore.value = page.hasMore ? page.nextBefore : null
  } catch (failure) {
    showError(failure)
  } finally {
    loadingEarlier.value = false
  }
}

async function send(): Promise<void> {
  if (sending.value) return
  const message = replyError(draft.value)
  problem.value = message ?? ''
  if (message) return
  sending.value = true
  try {
    const sent = await props.api.reply(props.conversationId, draft.value, replyKey)
    messages.value = mergeMessages(messages.value, [sent])
    draft.value = ''
    replyKey = newIdempotencyKey()
    ElMessage.success('已回复，客户会在 App 的“消息”里看到')
  } catch (failure) {
    showError(failure)
  } finally {
    sending.value = false
  }
}

watch(draft, () => {
  if (problem.value) problem.value = ''
})

onMounted(() => {
  void load()
})

defineExpose({ load })
</script>

<template>
  <div v-loading="loading" class="thread">
    <el-result v-if="error && !conversation" icon="error" :title="error">
      <template #extra><el-button type="primary" @click="load">重新加载</el-button></template>
    </el-result>

    <template v-if="conversation">
      <div class="thread__head">
        <strong>{{ conversation.customer.name }}</strong>
        <el-tag size="small" effect="plain">{{ COUNTERPART_LABELS[conversation.counterpartKind] }}</el-tag>
        <span class="muted">会话开始于 {{ formatDateTime(conversation.createdAt) }}</span>
        <el-button size="small" @click="load">刷新</el-button>
      </div>

      <div class="thread__messages">
        <div class="thread__earlier">
          <el-button v-if="nextBefore !== null" link type="primary" :loading="loadingEarlier" @click="loadEarlier">
            更早的消息
          </el-button>
          <span v-else class="muted">没有更早的消息了</span>
        </div>
        <div
          v-for="message in messages"
          :key="message.messageId"
          class="message"
          :class="message.mine ? 'message--mine' : 'message--customer'"
        >
          <div class="message__meta">
            {{ message.senderSide === 'CUSTOMER' ? conversation.customer.name : '我方' }} · {{ formatDateTime(message.sentAt) }}
          </div>
          <div v-if="message.card" class="message__card">
            <el-tag size="small" type="warning" effect="plain">{{ CARD_TYPE_LABELS[message.card.type] }}</el-tag>
            <div class="message__card-title">{{ message.card.title }}</div>
            <div v-if="message.card.subtitle" class="muted">{{ message.card.subtitle }}</div>
          </div>
          <div v-else class="message__text">{{ message.text }}</div>
        </div>
      </div>

      <div class="thread__reply">
        <el-input
          v-model="draft"
          type="textarea"
          :rows="3"
          :maxlength="REPLY_MAX"
          show-word-limit
          placeholder="回复客户。平台不交换手机号，请不要在这里留手机号；平台只协助沟通，不裁决。"
        />
        <div class="thread__actions">
          <span v-if="problem" class="problem">{{ problem }}</span>
          <el-button type="primary" :loading="sending" @click="send">发送</el-button>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.thread {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 200px;
}

.thread__head {
  display: flex;
  align-items: center;
  gap: 10px;
}

.thread__messages {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 520px;
  padding: 12px;
  overflow-y: auto;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: var(--admin-surface-muted);
}

.thread__earlier {
  text-align: center;
}

.message {
  max-width: 70%;
  padding: 8px 12px;
  border-radius: 8px;
  background: #fff;
}

.message--mine {
  align-self: flex-end;
  background: var(--admin-brand-surface);
}

.message--customer {
  align-self: flex-start;
}

.message__meta {
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--admin-text-secondary);
}

.message__text {
  white-space: pre-wrap;
  word-break: break-word;
}

.message__card-title {
  margin-top: 4px;
  font-weight: 600;
}

.thread__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 8px;
}

.problem {
  color: var(--el-color-danger);
  font-size: 13px;
}

.muted {
  font-size: 12px;
  color: var(--admin-text-secondary);
}
</style>
