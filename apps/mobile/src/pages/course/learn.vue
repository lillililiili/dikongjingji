<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { COURSE_PREVIEW_ENABLED } from '@/features/course/application/course-preview'
import { buildCourseLearnUrl, buildMyCoursesUrl, parseEnrollmentId } from '@/features/course/navigation/course-routes'
import CourseLearnPage from '@/features/course/presentation/learn/CourseLearnPage.vue'
import CourseUnavailable from '@/features/course/presentation/shared/CourseUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildCashierUrl, buildGoodsOrderUrl } from '@/features/trade/navigation/trade-routes'

const enrollmentId = ref<string | null>(null)
const lessonId = ref<string | null>(null)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

// Back is where the learner came from; alone (after paying, or from a link) it is 我的课程.
function goBack(): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.redirectTo({ url: buildMyCoursesUrl() })
}

function openLogin(): void {
  if (!enrollmentId.value) return
  uni.redirectTo({ url: buildInspectionLoginUrl(buildCourseLearnUrl(enrollmentId.value)) })
}

function openCashier(orderId: string): void {
  uni.navigateTo({ url: buildCashierUrl(orderId) })
}

// 来源订单 of a bundled course: the goods order it came with.
function openOrder(orderId: string): void {
  uni.navigateTo({ url: buildGoodsOrderUrl(orderId) })
}

onLoad((query) => {
  const parsed = query as Record<string, unknown> | undefined
  enrollmentId.value = parseEnrollmentId(parsed)
  const lesson = typeof parsed?.lessonId === 'string' ? parsed.lessonId : ''
  lessonId.value = /^[0-9a-f-]{36}$/i.test(lesson) ? lesson : null
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <CourseLearnPage
    v-if="COURSE_PREVIEW_ENABLED && shown"
    :enrollment-id="enrollmentId"
    :lesson-id="lessonId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    @back="goBack"
    @login="openLogin"
    @pay="openCashier"
    @open-order="openOrder"
  />
  <CourseUnavailable v-else-if="!COURSE_PREVIEW_ENABLED" />
</template>
