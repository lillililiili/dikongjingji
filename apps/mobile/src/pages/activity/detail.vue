<script setup lang="ts">
import { ACTIVITY_PREVIEW_ENABLED } from '@/features/activity/application/activity-preview'
import ActivityUnavailable from '@/features/activity/presentation/shared/ActivityUnavailable.vue'
import ActivityDetailPage from '@/features/activity/presentation/detail/ActivityDetailPage.vue'
</script>

<template>
  <ActivityDetailPage v-if="ACTIVITY_PREVIEW_ENABLED" />
  <ActivityUnavailable v-else />
</template>
