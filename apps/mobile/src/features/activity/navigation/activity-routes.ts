export const ACTIVITY_DETAIL_PATH = '/pages/activity/detail'

// Only stable ids travel in the URL; the page reads everything else from the server.
export function buildActivityDetailUrl(activityId: string, cityCode: string): string {
  return `${ACTIVITY_DETAIL_PATH}?activityId=${encodeURIComponent(activityId)}`
    + `&cityCode=${encodeURIComponent(cityCode)}`
}
