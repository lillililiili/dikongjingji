<script setup lang="ts">
import AppPageHeader from '../../../../shared/ui/AppPageHeader.vue'

// Kept for the trade pages; the head itself is the shared board page head.
defineProps<{
  title: string
  centered?: boolean
  testId: string
}>()

const emit = defineEmits<{ back: [] }>()
</script>

<template>
  <AppPageHeader :title="title" :centered="centered" :test-id="testId" @back="emit('back')" />
</template>
