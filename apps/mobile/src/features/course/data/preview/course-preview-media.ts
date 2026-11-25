import { resolveActivityPreviewCover } from '../../../activity/data/preview/activity-preview-covers'

const PREVIEW_SAMPLES = import.meta.env.DEV || __RELEASE_PREVIEW_BUILD__

// Pictures and clips for the sample courses of the development seed (R__dev_course.sql), keyed by
// course id. There is no video storage in development, so the server gives no play address; preview
// builds play these instead. Each clip is 8 seconds of slow pan and zoom made with ffmpeg from the
// prototype boards' own course pictures (CourseFly, CourseDetail), H.264 without sound — no outside
// footage. A cover or a play address the server returns always wins. Public builds show none of it.
const IMAGE_DIRECTORY = '/static/images/preview'
const VIDEO_DIRECTORY = '/static/videos/preview'

const COVERS: Record<string, string> = {
  'd7c00000-0000-4000-8000-000000000001': 'course-flight',
  'd7c00000-0000-4000-8000-000000000002': 'course-robot',
}

// The flight course alternates two clips chapter by chapter; the robot course has one.
const CLIPS: Record<string, readonly string[]> = {
  'd7c00000-0000-4000-8000-000000000001': ['course-flight-principle', 'course-flight-control'],
  'd7c00000-0000-4000-8000-000000000002': ['course-robot-coding'],
}

/** The preview cover of a seeded course; for an offline class, the picture of its activity. */
export function resolveCoursePreviewCover(
  course: { courseId: string; activityId?: string | null },
  previewEnabled: boolean = PREVIEW_SAMPLES,
): string | null {
  if (!previewEnabled) return null
  const name = COVERS[course.courseId]
  if (name) return `${IMAGE_DIRECTORY}/${name}.jpg`
  return course.activityId ? resolveActivityPreviewCover(course.activityId, true) : null
}

/** What the player plays: the server's address, else the preview clip of a seeded course, else nothing. */
export function coursePlaySource(
  courseId: string,
  lessonNo: number,
  playUrl: string | null,
  previewEnabled: boolean = PREVIEW_SAMPLES,
): string | null {
  if (playUrl) return playUrl
  const clips = previewEnabled ? CLIPS[courseId] : undefined
  if (!clips || clips.length === 0) return null
  // Lessons 1–3 play the first clip, 4–6 the second, and so on.
  return `${VIDEO_DIRECTORY}/${clips[Math.floor((Math.max(1, lessonNo) - 1) / 3) % clips.length]}.mp4`
}
