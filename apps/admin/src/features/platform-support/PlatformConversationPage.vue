<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import PageSection from '@/core/ui/PageSection.vue'
import ConversationThread from '@/features/support-desk/ConversationThread.vue'
import { createSupportDeskApi } from '@/features/support-desk/support-desk-api'

const route = useRoute()
const router = useRouter()
const api = createSupportDeskApi(http, 'platform')
const conversationId = computed(() => String(route.params.conversationId ?? ''))
</script>

<template>
  <PageSection
    title="回复客户"
    description="以“平台客服”的名义回复，客户看不到是哪个人回的；进来即标为已读。“关于什么”的卡片只显示客户发送时的文字摘要。平台只协助沟通，不裁决。"
  >
    <template #actions>
      <el-button @click="router.push({ name: 'support-conversations' })">返回会话列表</el-button>
    </template>
    <ConversationThread :key="conversationId" :api="api" :conversation-id="conversationId" />
  </PageSection>
</template>
