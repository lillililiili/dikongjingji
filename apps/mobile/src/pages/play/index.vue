<script setup lang="ts">
import { PLAY_PREVIEW_ENABLED } from '@/features/play/application/play-preview'
import PlayGamesPage from '@/features/play/presentation/PlayGamesPage.vue'
import SupportUnavailable from '@/features/support/presentation/shared/SupportUnavailable.vue'
</script>

<template>
  <PlayGamesPage v-if="PLAY_PREVIEW_ENABLED" />
  <SupportUnavailable v-else text="趣玩挑战尚未开通" />
</template>
