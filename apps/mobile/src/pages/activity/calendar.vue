<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import { buildActivityDetailUrl } from '@/features/activity/navigation/activity-routes'
import ActivityCalendarPage from '@/features/activity/presentation/calendar/ActivityCalendarPage.vue'
import ActivityUnavailable from '@/features/activity/presentation/shared/ActivityUnavailable.vue'

// 选择日期 (board CalendarPick): opened by the sign-up and reschedule pages with uni.navigateTo and an
// `events.picked` listener; the chosen session goes back over the page's event channel, so only ids
// travel in the URL.
const activityId = ref('')
const cityCode = ref('')
const sessionId = ref<string | null>(null)
const exceptSessionId = ref<string | null>(null)
const people = ref(1)
const loaded = ref(false)

interface OpenerChannel {
  emit(event: string, data: unknown): void
}

function openerChannel(): OpenerChannel | null {
  const pages = getCurrentPages()
  const current = pages[pages.length - 1] as unknown as { getOpenerEventChannel?: () => OpenerChannel } | undefined
  try {
    return current?.getOpenerEventChannel?.() ?? null
  } catch {
    return null
  }
}

function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  // Opened alone (a link, a reload): the activity it belongs to.
  if (activityId.value && cityCode.value) {
    uni.redirectTo({ url: buildActivityDetailUrl(activityId.value, cityCode.value) })
  } else {
    openHomeChannel('ACTIVITY')
  }
}

function picked(id: string): void {
  if (getCurrentPages().length === 1) {
    uni.redirectTo({ url: buildActivityDetailUrl(activityId.value, cityCode.value, id) })
    return
  }
  openerChannel()?.emit('picked', { sessionId: id })
  goBack()
}

onLoad((query) => {
  const text = (value: unknown) => (typeof value === 'string' && value ? value : null)
  activityId.value = text(query?.activityId) ?? ''
  cityCode.value = text(query?.cityCode) ?? ''
  sessionId.value = text(query?.sessionId)
  exceptSessionId.value = text(query?.exceptSessionId)
  const count = Number(query?.people)
  people.value = Number.isInteger(count) && count > 0 ? count : 1
  loaded.value = true
})
</script>

<template>
  <ActivityCalendarPage
    v-if="ACTIVITY_PREVIEW_ENABLED && loaded"
    :activity-id="activityId"
    :city-code="cityCode"
    :session-id="sessionId"
    :except-session-id="exceptSessionId"
    :people="people"
    @back="goBack"
    @picked="picked"
  />
  <ActivityUnavailable v-else-if="!ACTIVITY_PREVIEW_ENABLED" />
</template>
