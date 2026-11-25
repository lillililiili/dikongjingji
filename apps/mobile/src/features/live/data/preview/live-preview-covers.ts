const PREVIEW_SAMPLES = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__

// Pictures for the sample rooms of the development seed (R__dev_live.sql), keyed by room id. They are
// the 2026-11-14 board pictures (Discover, LiveNotice, LiveRoom) already bundled for the discover
// content, and only stand in until a room carries a licensed cover of its own; a cover the server
// returns always wins. Public builds do not show the live pages.
const DIRECTORY = '/static/images/preview'

const ROOMS: Record<string, { file: string; alt: string }> = {
  'f4000000-0000-4000-8000-000000000001': { file: 'content-park-patrol.webp', alt: '机器狗园区夜巡直播画面' },
  'f4000000-0000-4000-8000-000000000002': { file: 'content-ref-flight-guide.jpg', alt: '编程无人机开箱现场' },
  'f4000000-0000-4000-8000-000000000003': { file: 'robot-classroom.jpg', alt: '编程机器人课堂' },
  'f4000000-0000-4000-8000-000000000004': { file: 'content-ref-survey-model.jpg', alt: '巡检机器狗' },
  'f4000000-0000-4000-8000-000000000005': { file: 'content-solar.webp', alt: '光伏电站现场' },
  'f4000000-0000-4000-8000-000000000006': { file: 'proservice-tech-robot.jpg', alt: '体验馆开放日机器人闯关' },
}

export interface LivePreviewCover {
  url: string
  alt: string
}

export function resolveLivePreviewCover(
  roomId: string,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): LivePreviewCover | null {
  const entry = previewEnabled ? ROOMS[roomId] : undefined
  return entry ? { url: `${DIRECTORY}/${entry.file}`, alt: entry.alt } : null
}

// The development stream service plays one of the App's own sample videos (made from board
// pictures, apps/mobile/src/static/videos/preview/live-*.mp4). Only preview builds accept such an
// address; every build accepts a https one.
const SAMPLE_VIDEO = /^\/static\/videos\/preview\/live-[a-z-]+\.mp4$/

export function acceptPlayUrl(url: string | null | undefined, previewEnabled: boolean = PREVIEW_SAMPLES): string | null {
  const value = url?.trim()
  if (!value) return null
  if (/^https:\/\/\S+$/i.test(value)) return value
  return previewEnabled && SAMPLE_VIDEO.test(value) ? value : null
}

export function isSamplePlayUrl(url: string | null): boolean {
  return Boolean(url && SAMPLE_VIDEO.test(url))
}
