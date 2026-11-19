import { activityApi } from '../../activity/data/activity-api-client'
import { experienceApi } from '../../experience/data/experience-api-client'
import { inspectionServiceDetailApi } from '../../inspection/data/service-flow/uni-booking-http-client'
import { mallApi } from '../../mall/data/mall-api-client'
import { proServiceApi } from '../../proservice/data/proservice-api-client'

import type { ContentLinkedApis } from '../application/content-refs'

// The other modules' own public API clients, reused rather than copied: an article's mentions and a
// topic's activities and venues are read from where they live.
export const contentLinkedApis: ContentLinkedApis = {
  inspection: inspectionServiceDetailApi,
  proService: proServiceApi,
  mall: mallApi,
  activity: activityApi,
  experience: experienceApi,
}
