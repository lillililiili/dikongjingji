import type { HomeChannelCode, HomeMallCategory } from '@/app-shell/navigation/home-channels'

// 按类目找 on board SearchPre: fixed entries set by the platform (not a hot list), drawn with the
// icons of the home entries. Each opens a home channel, the mall ones on their category tab.
export interface SearchCategoryEntry {
  key: string
  label: string
  background: string
  color: string
  icon: string
  target: { channel: Exclude<HomeChannelCode, 'MALL' | 'RECOMMEND'> } | { mallCategory: HomeMallCategory }
}

const ORANGE = { background: '#fff0e3', color: '#ff6a00' }
const GREEN = { background: '#e2f6ed', color: '#159a70' }
const BLUE = { background: '#e6f0ff', color: '#2f80ed' }

export const SEARCH_CATEGORY_ENTRIES: readonly SearchCategoryEntry[] = [
  { key: 'service', label: '专业服务', ...ORANGE, target: { channel: 'SERVICE' }, icon: 'M9 3.5h6a1 1 0 0 1 1 1V6a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V4.5a1 1 0 0 1 1-1zM16 5h2a1.5 1.5 0 0 1 1.5 1.5v13A1.5 1.5 0 0 1 18 21H6a1.5 1.5 0 0 1-1.5-1.5v-13A1.5 1.5 0 0 1 6 5h2M8.5 13.5l2.5 2.5 4.5-5' },
  { key: 'activity', label: '赛事研学', ...BLUE, target: { channel: 'ACTIVITY' }, icon: 'M8 4h8v5a4 4 0 0 1-8 0zM12 13v4M8 20h8M8 6H5v2a3 3 0 0 0 3 3M16 6h3v2a3 3 0 0 1-3 3' },
  { key: 'experience', label: '体验', ...GREEN, target: { channel: 'EXPERIENCE' }, icon: 'M5 5h14v15H5zM5 9h14M9 3v4M15 3v4M9 14l2 2 4-4' },
  { key: 'drone', label: '无人机', ...ORANGE, target: { mallCategory: 'DRONE' }, icon: 'M10 10h4v4h-4zM10 10L7.3 7.3M14 10l2.7-2.7M14 14l2.7 2.7M10 14l-2.7 2.7M3 5.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M16 5.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M16 18.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0M3 18.5a2.5 2.5 0 1 0 5 0a2.5 2.5 0 1 0-5 0' },
  { key: 'robot', label: '机器人', ...BLUE, target: { mallCategory: 'ROBOT' }, icon: 'M6 8h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2zM12 8V5.5M10.8 4a1.2 1.2 0 1 0 2.4 0a1.2 1.2 0 1 0-2.4 0M9 12.5v1M15 12.5v1M9.5 16h5M2 12v3M22 12v3' },
  { key: 'parts', label: '配件', ...ORANGE, target: { mallCategory: 'PARTS' }, icon: 'M12 3l7.8 4.5v9L12 21l-7.8-4.5v-9zM12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z' },
  { key: 'rental', label: '租赁', ...BLUE, target: { mallCategory: 'RENTAL' }, icon: 'M4 8h16a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1zM9 8V5.5A1.5 1.5 0 0 1 10.5 4h3A1.5 1.5 0 0 1 15 5.5V8M3 13h18M10.5 13v2h3v-2' },
  { key: 'course', label: '课程', ...GREEN, target: { mallCategory: 'COURSE' }, icon: 'M2.5 9.5L12 5l9.5 4.5L12 14zM6.5 11.5V16c0 1.4 2.5 2.8 5.5 2.8s5.5-1.4 5.5-2.8v-4.5M21.5 9.5v5' },
]

// The tag colours of the suggestion rows (board SearchPre `tones`).
export const SEARCH_TONES = {
  PRODUCT: { background: '#fff0e3', color: '#ba470b' },
  SERVICE: { background: '#e6f0ff', color: '#1d63c4' },
  ACTIVITY: { background: '#eee7ff', color: '#6a4fe0' },
  CONTENT: { background: '#eef2f8', color: '#536178' },
  VENUE: { background: '#e2f6ed', color: '#11804a' },
} as const
