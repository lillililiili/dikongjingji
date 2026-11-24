import type { ContentImage } from '../../domain/content'

const PREVIEW_SAMPLES = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__

// Sample clips and posters for the six sample videos of the development seed
// (R__dev_content_video.sql), keyed by id. There is no video storage in development, so the server
// gives no play address; preview builds play these instead. Each clip is 8 seconds of slow pan
// and zoom made with ffmpeg from the prototype boards' own pictures (Discover, VideoFeed,
// ContentVideo), H.264 without sound — no outside footage. A play address or a cover the server
// returns always wins. Public builds do not show the video pages.
const VIDEO_DIRECTORY = '/static/videos/preview'
const IMAGE_DIRECTORY = '/static/images/preview'

const VIDEOS: Record<string, { slug: string; alt: string }> = {
  'f3200000-0000-4000-8000-000000000001': { slug: 'robot-coding', alt: '编程机器人演示' },
  'f3200000-0000-4000-8000-000000000002': { slug: 'dog-patrol', alt: '机器狗园区夜巡' },
  'f3200000-0000-4000-8000-000000000003': { slug: 'aerial-yellow-river', alt: '黄河口航拍' },
  'f3200000-0000-4000-8000-000000000004': { slug: 'contest-recap', alt: '编程挑战赛现场' },
  'f3200000-0000-4000-8000-000000000005': { slug: 'survey-3days', alt: '无人机航测作业' },
  'f3200000-0000-4000-8000-000000000006': { slug: 'study-camp-day', alt: '研学营的孩子们' },
}

/** The sample clip of a seeded video on preview builds; null otherwise. */
export function resolveVideoPreviewSource(videoId: string, previewEnabled: boolean = PREVIEW_SAMPLES): string | null {
  const entry = previewEnabled ? VIDEOS[videoId] : undefined
  return entry ? `${VIDEO_DIRECTORY}/${entry.slug}.mp4` : null
}

/** The poster of a seeded video (its first frame) on preview builds; null otherwise. */
export function resolveVideoPreviewCover(videoId: string, previewEnabled: boolean = PREVIEW_SAMPLES): ContentImage | null {
  const entry = previewEnabled ? VIDEOS[videoId] : undefined
  return entry ? { url: `${IMAGE_DIRECTORY}/video-${entry.slug}.jpg`, alt: entry.alt } : null
}

/** What the player plays: the server's address, else the preview sample, else nothing. */
export function videoPlaySource(
  video: { videoId: string; playUrl: string | null },
  previewEnabled: boolean = PREVIEW_SAMPLES,
): string | null {
  return video.playUrl ?? resolveVideoPreviewSource(video.videoId, previewEnabled)
}

/** The poster: the server's cover, else the preview poster. */
export function videoPoster(
  video: { videoId: string; coverImage: ContentImage | null },
  previewEnabled: boolean = PREVIEW_SAMPLES,
): ContentImage | null {
  return video.coverImage ?? resolveVideoPreviewCover(video.videoId, previewEnabled)
}
