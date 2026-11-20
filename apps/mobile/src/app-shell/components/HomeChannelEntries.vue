<script setup lang="ts">
import { computed, ref } from 'vue'

import type {
  HomeChannelCard,
  HomeEntryTarget,
  HomeKingEntry,
} from '@/app-shell/navigation/home-channels'
import { DEMAND_PREVIEW_ENABLED } from '@/features/demand/application/demand-preview'
import { buildDemandPublishUrl } from '@/features/demand/navigation/demand-routes'
import IconTile from '@/shared/ui/IconTile.vue'
import LineIcon from '@/shared/ui/LineIcon.vue'
import { iconTileToneAt } from '@/shared/ui/icon-tile-tones'

const props = defineProps<{
  cards: readonly HomeChannelCard[]
  entries: readonly HomeKingEntry[]
}>()

const emit = defineEmits<{
  select: [target: HomeEntryTarget]
}>()

const ENTRIES_PER_PAGE = 5
// The card icons keep the boards' own colours (HomeFull.html): orange for service, green for experience.
const CARD_ICON_COLORS: Partial<Record<HomeChannelCard['channel'], string>> = {
  SERVICE: '#ff6a00',
  EXPERIENCE: '#159a70',
}
// "发布需求 ›" on the service card leads to the demand form (M15), which only preview builds have.
const DEMAND_TARGET: HomeEntryTarget = {
  type: 'PAGE',
  url: buildDemandPublishUrl(),
  publiclyAvailable: false,
}

const activePage = ref(0)
const pages = computed(() => {
  const chunks: { id: string; entries: readonly HomeKingEntry[] }[] = []
  for (let start = 0; start < props.entries.length; start += ENTRIES_PER_PAGE) {
    const entries = props.entries.slice(start, start + ENTRIES_PER_PAGE)
    chunks.push({ id: entries[0].code, entries })
  }
  return chunks
})

function showsDemandChip(card: HomeChannelCard): boolean {
  return DEMAND_PREVIEW_ENABLED && card.channel === 'SERVICE'
}

function selectPage(event: Event): void {
  const page = (
    event as Event & {
      detail?: {
        current?: number
      }
    }
  ).detail?.current

  activePage.value = page ?? 0
}
</script>

<template>
  <view class="home-entries">
    <view
      :class="[
        'home-entries__cards',
        { 'home-entries__cards--single': cards.length === 1 },
      ]"
    >
      <view
        v-for="card in cards"
        :key="card.channel"
        :class="[
          'home-entries__card',
          `home-entries__card--${card.channel.toLowerCase()}`,
        ]"
      >
        <button
          class="home-entries__card-link"
          :data-testid="`home-card-${card.channel.toLowerCase()}`"
          @click="emit('select', { type: 'CHANNEL', channel: card.channel })"
        >
          <text class="home-entries__card-title">{{ card.title }}</text>
          <text class="home-entries__card-subtitle">{{ card.subtitle }}</text>
          <view class="home-entries__card-icon">
            <LineIcon
              :name="card.icon"
              :size="29"
              :stroke-width="1.5"
              :color="CARD_ICON_COLORS[card.channel] ?? '#ff6a00'"
            />
          </view>
        </button>
        <button
          v-if="showsDemandChip(card)"
          class="home-entries__card-chip"
          data-testid="home-card-service-demand"
          @click="emit('select', DEMAND_TARGET)"
        >
          <text class="home-entries__card-chip-label">发布需求 ›</text>
        </button>
      </view>
    </view>

    <view v-if="entries.length" class="home-entries__king">
      <swiper
        class="home-entries__swiper"
        :current="activePage"
        @change="selectPage"
      >
        <swiper-item v-for="page in pages" :key="page.id">
          <view class="home-entries__king-page">
            <button
              v-for="(entry, index) in page.entries"
              :key="entry.code"
              class="home-entries__king-entry"
              :data-testid="`home-king-${entry.code}`"
              @click="emit('select', entry.target)"
            >
              <IconTile
                :tone="iconTileToneAt(index)"
                :name="entry.icon"
                :size="46"
                :icon-size="24"
              />
              <text class="home-entries__king-label">{{ entry.label }}</text>
            </button>
          </view>
        </swiper-item>
      </swiper>
      <view v-if="pages.length > 1" class="home-entries__dots">
        <view
          v-for="(page, index) in pages"
          :key="page.id"
          :class="[
            'home-entries__dot',
            { 'home-entries__dot--active': index === activePage },
          ]"
        />
      </view>
    </view>
  </view>
</template>

<style scoped>
.home-entries {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

button {
  min-width: 0;
  margin: 0;
  padding: 0;
  border: 0;
  background: transparent;
  font-family: inherit;
  line-height: 1.2;
}

button::after {
  border: 0;
}

.home-entries__cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.home-entries__cards--single {
  grid-template-columns: minmax(0, 1fr);
}

.home-entries__card {
  position: relative;
  height: 104px;
  overflow: hidden;
  border-radius: 14px;
}

/* Gradients and shadows of light-theme.css `.theme-entry-service` / `.theme-entry-experience`. */
.home-entries__card--service {
  background: linear-gradient(120deg, #ffeddc, #fff5e9);
  box-shadow: 0 3px 12px rgba(211, 129, 26, 0.05);
}

.home-entries__card--experience {
  background: linear-gradient(120deg, #e2f7ed, #dff4f2);
  box-shadow: 0 3px 12px rgba(46, 152, 123, 0.06);
}

.home-entries__card-link {
  position: absolute;
  inset: 0;
  display: flex;
  padding: 14px;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  box-sizing: border-box;
  text-align: left;
}

.home-entries__card--service .home-entries__card-link {
  color: #ba470b;
}

.home-entries__card--experience .home-entries__card-link {
  color: #11804a;
}

/* The board's 40px icon sits on a tilted white block (light-theme.css). */
.home-entries__card-icon {
  position: absolute;
  right: 8px;
  bottom: 9px;
  display: flex;
  padding: 7px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.67);
  transform: rotate(-5deg);
}

/* On the narrowest phones the decorative icon would sit on the subtitle. */
@media (max-width: 359px) {
  .home-entries__card-icon {
    display: none;
  }
}

.home-entries__card-title {
  font-size: 18px;
  font-weight: 700;
  line-height: 25px;
  white-space: nowrap;
}

.home-entries__card-subtitle {
  color: #627187;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

/* 28 high on the board; the button around it is the 44 tap target. */
.home-entries__card-chip {
  position: absolute;
  bottom: 0;
  left: 0;
  display: flex;
  height: 44px;
  padding: 4px 14px 12px;
  align-items: flex-end;
  box-sizing: border-box;
}

.home-entries__card-chip-label {
  display: flex;
  height: 28px;
  padding: 0 10px;
  align-items: center;
  border: 1px solid #ffd8b8;
  border-radius: 999px;
  color: #ba470b;
  background: #ffffff;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
  box-sizing: border-box;
}

.home-entries__king {
  padding: 14px 4px 10px;
  border-radius: 14px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
}

.home-entries__swiper {
  height: 76px;
}

.home-entries__king-page {
  display: grid;
  padding-top: 3px;
  grid-template-columns: repeat(5, minmax(0, 1fr));
}

.home-entries__king-entry {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: #202c42;
}

.home-entries__king-label {
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.home-entries__dots {
  display: flex;
  margin-top: 12px;
  justify-content: center;
  gap: 4px;
}

.home-entries__dot {
  width: 4px;
  height: 4px;
  border-radius: 2px;
  background: #c9d3e1;
}

.home-entries__dot--active {
  width: 12px;
  background: #ff6a00;
}
</style>
