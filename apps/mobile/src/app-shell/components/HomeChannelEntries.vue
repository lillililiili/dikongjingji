<script setup lang="ts">
import UniIcons from '@dcloudio/uni-ui/lib/uni-icons/uni-icons.vue'
import { computed, ref } from 'vue'

import type {
  HomeChannelCard,
  HomeEntryTarget,
  HomeKingEntry,
  HomeKingEntryTone,
} from '@/app-shell/navigation/home-channels'

const props = defineProps<{
  cards: readonly HomeChannelCard[]
  entries: readonly HomeKingEntry[]
}>()

const emit = defineEmits<{
  select: [target: HomeEntryTarget]
}>()

const ENTRIES_PER_PAGE = 5
const TONE_ICON_COLORS: Record<HomeKingEntryTone, string> = {
  ORANGE: '#ff6a00',
  BLUE: '#2f80ed',
  GREEN: '#159a70',
  VIOLET: '#7a55c7',
  YELLOW: '#b7791f',
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
      <button
        v-for="card in cards"
        :key="card.channel"
        :class="[
          'home-entries__card',
          `home-entries__card--${card.channel.toLowerCase()}`,
        ]"
        :data-testid="`home-card-${card.channel.toLowerCase()}`"
        @click="emit('select', { type: 'CHANNEL', channel: card.channel })"
      >
        <text class="home-entries__card-title">{{ card.title }}</text>
        <text class="home-entries__card-subtitle">{{ card.subtitle }}</text>
        <view class="home-entries__card-icon">
          <UniIcons
            :type="card.channel === 'SERVICE' ? 'list' : 'videocam'"
            size="22"
            :color="card.channel === 'SERVICE' ? '#ff6a00' : '#159a70'"
          />
        </view>
      </button>
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
              v-for="entry in page.entries"
              :key="entry.code"
              class="home-entries__king-entry"
              :data-testid="`home-king-${entry.code}`"
              @click="emit('select', entry.target)"
            >
              <view
                :class="[
                  'home-entries__king-icon',
                  `home-entries__king-icon--${entry.tone.toLowerCase()}`,
                ]"
              >
                <UniIcons
                  :type="entry.icon"
                  size="24"
                  :color="TONE_ICON_COLORS[entry.tone]"
                />
              </view>
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
  display: flex;
  min-height: 92px;
  padding: 14px;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  overflow: hidden;
  border-radius: 16px;
  box-sizing: border-box;
  text-align: left;
}

.home-entries__card--service {
  color: #ba470b;
  background: linear-gradient(135deg, #fff0e3 0%, #fff8f1 100%);
}

.home-entries__card--experience {
  color: #11804a;
  background: linear-gradient(135deg, #dff5ed 0%, #f0fbf6 100%);
}

.home-entries__card-icon {
  position: absolute;
  right: 12px;
  bottom: 12px;
  display: flex;
  width: 40px;
  height: 40px;
  align-items: center;
  justify-content: center;
  border-radius: 13px;
  background: rgba(255, 255, 255, 0.85);
  transform: rotate(-4deg);
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
  color: #666b73;
  font-size: 12px;
  line-height: 17px;
  white-space: nowrap;
}

.home-entries__king {
  padding: 14px 4px 10px;
  border-radius: 16px;
  background: #ffffff;
  box-shadow: 0 3px 12px rgba(38, 60, 101, 0.06);
}

.home-entries__swiper {
  height: 72px;
}

.home-entries__king-page {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
}

.home-entries__king-entry {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: #191919;
}

.home-entries__king-icon {
  display: flex;
  width: 46px;
  height: 46px;
  align-items: center;
  justify-content: center;
  border: 1px solid rgba(255, 255, 255, 0.85);
  border-radius: 16px;
  box-sizing: border-box;
}

.home-entries__king-icon--orange {
  background: #ffe9d6;
}

.home-entries__king-icon--blue {
  background: #e4efff;
}

.home-entries__king-icon--green {
  background: #dff5ed;
}

.home-entries__king-icon--violet {
  background: #eee7ff;
}

.home-entries__king-icon--yellow {
  background: #fff2cd;
}

.home-entries__king-label {
  font-size: 13px;
  line-height: 18px;
  white-space: nowrap;
}

.home-entries__dots {
  display: flex;
  margin-top: 6px;
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
