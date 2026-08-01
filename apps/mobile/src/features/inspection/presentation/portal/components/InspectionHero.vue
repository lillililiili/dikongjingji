<script setup lang="ts">
import PlatformHeroBanner from '@/shared/presentation/PlatformHeroBanner.vue'
import type { InspectionPortalHero } from '../../../domain/inspection-portal-content'

defineProps<{
  hero: InspectionPortalHero
  showRequestAction: boolean
}>()

defineEmits<{
  request: []
}>()
</script>

<template>
  <PlatformHeroBanner
    :image-url="hero.imageUrl"
    image-alt="无人机巡检工业设施"
    :title="hero.title"
    :highlight="hero.accent"
    :description="hero.description"
    :highlights="hero.highlights"
    :action-label="showRequestAction ? '发布巡检需求' : ''"
    @action="$emit('request')"
  />
</template>
