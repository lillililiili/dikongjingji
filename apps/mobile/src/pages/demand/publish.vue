<script setup lang="ts">
import { onLoad, onShow } from '@dcloudio/uni-app'
import { ref } from 'vue'

import { openHomeChannel } from '@/app-shell/navigation/home-entry'
import { DEMAND_PREVIEW_ENABLED } from '@/features/demand/application/demand-preview'
import {
  buildDemandPublishUrl,
  buildDemandQuotesUrl,
  buildMyDemandsUrl,
  parseDemandPublishTarget,
  type DemandListFilter,
  type DemandPublishTarget,
} from '@/features/demand/navigation/demand-routes'
import DemandPublishPage from '@/features/demand/presentation/publish/DemandPublishPage.vue'
import DemandUnavailable from '@/features/demand/presentation/shared/DemandUnavailable.vue'
import { getBookingAuthContext } from '@/features/inspection/application/service-flow/booking-auth-context'
import { buildInspectionLoginUrl } from '@/features/inspection/navigation/inspection-routes'

const target = ref<DemandPublishTarget>({})
const accessToken = ref<string | null>(null)
// The form is created on the first show, when the query and the login state are both known.
const shown = ref(false)

// Back goes where the user came from; opened on its own, back is the 专业服务 channel.
function goBack(): void {
  if (getCurrentPages().length > 1) {
    uni.navigateBack()
    return
  }
  openHomeChannel('SERVICE')
}

// Signing in replaces this page and comes back to the same form.
function openLogin(): void {
  uni.redirectTo({ url: buildInspectionLoginUrl(buildDemandPublishUrl(target.value)) })
}

// The form gives way to the list, so back from the list does not land on a sent form.
function openSubmitted(): void {
  uni.redirectTo({
    url: buildMyDemandsUrl('ALL'),
    success: () => uni.showToast({ title: '需求已提交，等待服务商报价', icon: 'none' }),
  })
}

function openList(filter: DemandListFilter): void {
  uni.navigateTo({ url: buildMyDemandsUrl(filter) })
}

function openQuotes(demandId: string): void {
  uni.navigateTo({ url: buildDemandQuotesUrl(demandId) })
}

// 重新发布: a new form filled from the closed demand, in this page's place.
function republish(demandId: string): void {
  uni.redirectTo({ url: buildDemandPublishUrl({ from: demandId }) })
}

onLoad((query) => {
  target.value = parseDemandPublishTarget(query as Record<string, unknown> | undefined)
})

onShow(() => {
  accessToken.value = getBookingAuthContext().getAccessToken()
  shown.value = true
})
</script>

<template>
  <DemandPublishPage
    v-if="DEMAND_PREVIEW_ENABLED && shown"
    :target="target"
    :access-token="accessToken"
    @back="goBack"
    @login="openLogin"
    @submitted="openSubmitted"
    @open-list="openList"
    @open-quotes="openQuotes"
    @republish="republish"
  />
  <DemandUnavailable v-else-if="!DEMAND_PREVIEW_ENABLED" />
</template>
