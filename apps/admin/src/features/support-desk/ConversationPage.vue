<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { http } from '@/core/http'
import PageSection from '@/core/ui/PageSection.vue'

import ConversationThread from './ConversationThread.vue'
import { createSupportDeskApi } from './support-desk-api'

const route = useRoute()
const router = useRouter()
const api = createSupportDeskApi(http)
const conversationId = computed(() => String(route.params.conversationId ?? ''))
</script>

<template>
  <PageSection title="回复客户" description="客户只看到本机构的名称，看不到是哪个操作员回的；平台只协助沟通，不裁决。">
    <template #actions>
      <el-button @click="router.push({ name: 'workbench-conversations' })">返回会话列表</el-button>
    </template>
    <ConversationThread :key="conversationId" :api="api" :conversation-id="conversationId" />
  </PageSection>
</template>
