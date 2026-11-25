<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { COURSE_PREVIEW_ENABLED } from '@/features/course/application/course-preview'
import { buildCourseDetailUrl, parseTrialTarget } from '@/features/course/navigation/course-routes'
import CourseUnavailable from '@/features/course/presentation/shared/CourseUnavailable.vue'
import CourseTrialPage from '@/features/course/presentation/trial/CourseTrialPage.vue'

const target = ref<{ courseId: string } | { experienceItemId: string } | null>(null)
const loaded = ref(false)

function goBack(): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else openHomeChannel('EXPERIENCE')
}

function openBuy(courseId: string): void {
  uni.navigateTo({ url: buildCourseDetailUrl(courseId, { buy: true }) })
}

function openDetail(courseId: string): void {
  uni.navigateTo({ url: buildCourseDetailUrl(courseId) })
}

function openSimulation(url: string): void {
  uni.navigateTo({ url })
}

onLoad((query) => {
  target.value = parseTrialTarget(query as Record<string, unknown> | undefined)
  loaded.value = true
})
</script>

<template>
  <CourseTrialPage
    v-if="COURSE_PREVIEW_ENABLED && loaded"
    :target="target"
    @back="goBack"
    @buy="openBuy"
    @detail="openDetail"
    @simulation="openSimulation"
  />
  <CourseUnavailable v-else-if="!COURSE_PREVIEW_ENABLED" />
</template>
