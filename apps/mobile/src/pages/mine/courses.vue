<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { buildActivityEnrollmentsUrl } from '@/features/activity/navigation/activity-routes'
import { COURSE_PREVIEW_ENABLED } from '@/features/course/application/course-preview'
import type { CourseEnrollmentBucket } from '@/features/course/domain/course'
import {
  COURSE_LEARN_PATH,
  buildCourseTrialUrl,
  buildMyCoursesUrl,
  parseCourseBucket,
} from '@/features/course/navigation/course-routes'
import MyCoursesPage from '@/features/course/presentation/mine/MyCoursesPage.vue'
import CourseUnavailable from '@/features/course/presentation/shared/CourseUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { MINE_TAB_PATH, buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'

const bucket = ref<CourseEnrollmentBucket>('ALL')
const accessToken = ref<string | null>(null)
const refreshKey = ref(0)
const shown = ref(false)

function goBack(): void {
  if (getCurrentPages().length > 1) uni.navigateBack()
  else uni.switchTab({ url: MINE_TAB_PATH })
}

function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildMyCoursesUrl(bucket.value)) })
}

function openLearn(enrollmentId: string, lessonId: string | null): void {
  const lesson = lessonId ? `&lessonId=${encodeURIComponent(lessonId)}` : ''
  uni.navigateTo({ url: `${COURSE_LEARN_PATH}?enrollmentId=${encodeURIComponent(enrollmentId)}${lesson}` })
}

function openTrial(courseId: string): void {
  uni.navigateTo({ url: buildCourseTrialUrl({ courseId }) })
}

// Offline classes are activity sign-ups (board MyCourses: “线下培训在活动报名里查看”).
function openEnrollments(): void {
  uni.navigateTo({ url: buildActivityEnrollmentsUrl() })
}

onLoad((query) => {
  bucket.value = parseCourseBucket(query as Record<string, unknown> | undefined)
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  refreshKey.value += 1
  shown.value = true
})
</script>

<template>
  <MyCoursesPage
    v-if="COURSE_PREVIEW_ENABLED && shown"
    :access-token="accessToken"
    :refresh-key="refreshKey"
    :initial-bucket="bucket"
    @back="goBack"
    @login="openLogin"
    @learn="openLearn"
    @trial="openTrial"
    @enrollments="openEnrollments"
  />
  <CourseUnavailable v-else-if="!COURSE_PREVIEW_ENABLED" />
</template>
