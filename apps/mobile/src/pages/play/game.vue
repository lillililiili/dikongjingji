<script setup lang="ts">
import { PLAY_PREVIEW_ENABLED } from '@/features/play/application/play-preview'
import PlayGamePage from '@/features/play/presentation/PlayGamePage.vue'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'
</script>

<template>
  <PlayGamePage v-if="PLAY_PREVIEW_ENABLED" />
  <SupportUnavailable v-else text="趣玩挑战尚未开通" />
</template>
