<script setup lang="ts">
import { EXPERIENCE_PREVIEW_ENABLED } from '@/features/experience/application/experience-preview'
import VenueBookingUnavailable from '@/features/experience/presentation/shared/VenueBookingUnavailable.vue'
import VenueDevicesPage from '@/features/experience/presentation/venue/VenueDevicesPage.vue'
</script>

<template>
  <VenueDevicesPage v-if="EXPERIENCE_PREVIEW_ENABLED" />
  <VenueBookingUnavailable v-else />
</template>
