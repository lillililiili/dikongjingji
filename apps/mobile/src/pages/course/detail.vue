<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { COURSE_PREVIEW_ENABLED } from '@/features/course/application/course-preview'
import {
  buildCourseDetailUrl,
  buildCourseLearnUrl,
  buildCourseTrialUrl,
  parseCourseId,
  wantsToBuy,
} from '@/features/course/navigation/course-routes'
import CourseDetailPage from '@/features/course/presentation/detail/CourseDetailPage.vue'
import CourseUnavailable from '@/features/course/presentation/shared/CourseUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'
import { buildCashierUrl } from '@/features/trade/navigation/trade-routes'

const courseId = ref<string | null>(null)
const openBuy = ref(false)
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function goBack(): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else openHomeChannel('MALL')
}

// Signing in replaces this page and comes back to it with the 报名 sheet open.
function openLogin(): void {
  if (!courseId.value) return
  uni.redirectTo({ url: buildInspectionLoginUrl(buildCourseDetailUrl(courseId.value, { buy: true })) })
}

function openTrial(id: string): void {
  uni.navigateTo({ url: buildCourseTrialUrl({ courseId: id }) })
}

// Right after buying (the course opened at once) the detail stays underneath the learning page.
function openLearn(enrollmentId: string): void {
  uni.navigateTo({ url: buildCourseLearnUrl(enrollmentId) })
}

// The cashier opens on top; back from it (or from the result page) shows the detail again,
// which reads 去学习 once the payment has arrived.
function openCashier(orderId: string): void {
  uni.navigateTo({ url: buildCashierUrl(orderId) })
}

onLoad((query) => {
  const parsed = query as Record<string, unknown> | undefined
  courseId.value = parseCourseId(parsed)
  openBuy.value = wantsToBuy(parsed)
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <CourseDetailPage
    v-if="COURSE_PREVIEW_ENABLED && shown"
    :course-id="courseId"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :open-buy="openBuy"
    @back="goBack"
    @login="openLogin"
    @trial="openTrial"
    @learn="openLearn"
    @pay="openCashier"
  />
  <CourseUnavailable v-else-if="!COURSE_PREVIEW_ENABLED" />
</template>
